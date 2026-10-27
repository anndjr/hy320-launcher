package br.hy320.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Painel da bolsa em tela cheia, com números e gráficos do dia.
 * Usado pelo descanso de tela (StocksDream) e pelo atalho "Bolsa" (StocksActivity).
 */
final class StocksBoard {

    private static final int UP = 0xFF7EE2A8;
    private static final int DOWN = 0xFFFF8A9A;
    private static final int MUTED = 0xFFA9B1C7;
    private static final int COLUMNS = 6;
    private static final Locale BR = new Locale("pt", "BR");

    private final Context context;
    private final float dp;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final LinearLayout root;
    private final TextView status;
    private final LinearLayout hero;
    private final LinearLayout grid;
    private boolean running;

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            network.execute(() -> {
                List<Stocks.Quote> quotes = Stocks.fetch();
                handler.post(() -> show(quotes));
            });
            handler.postDelayed(this, 60_000);
        }
    };

    StocksBoard(Context context) {
        this.context = context;
        this.dp = context.getResources().getDisplayMetrics().density;

        root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(px(32), px(22), px(32), px(16));
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF0E1530, 0xFF070A14}));

        LinearLayout header = new LinearLayout(context);
        header.setGravity(Gravity.BOTTOM);
        LinearLayout titles = new LinearLayout(context);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(text("Bolsa de valores", 26, 0xFFFFFFFF));
        status = text("Carregando cotações…", 14, MUTED);
        titles.addView(status);
        header.addView(titles, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        TextClock clock = new TextClock(context);
        clock.setFormat24Hour("HH:mm");
        clock.setFormat12Hour("HH:mm");
        clock.setTextSize(44);
        clock.setTextColor(0xFFFFFFFF);
        clock.setIncludeFontPadding(false);
        header.addView(clock);
        root.addView(header);

        hero = new LinearLayout(context);
        LinearLayout.LayoutParams heroParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.25f);
        heroParams.topMargin = px(14);
        root.addView(hero, heroParams);

        grid = new LinearLayout(context);
        grid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams gridParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 2f);
        gridParams.topMargin = px(10);
        root.addView(grid, gridParams);
    }

    View view() {
        return root;
    }

    void start() {
        if (running) return;
        running = true;
        handler.post(refresh);
    }

    void stop() {
        running = false;
        handler.removeCallbacks(refresh);
    }

    void destroy() {
        stop();
        network.shutdownNow();
    }

    private void show(List<Stocks.Quote> quotes) {
        // Sem internet: mantém o que já está na tela.
        if (!running || quotes.isEmpty()) {
            if (running && hero.getChildCount() == 0) status.setText("Sem conexão com a internet · tentando de novo em 1 min");
            return;
        }
        long latest = 0;
        for (Stocks.Quote quote : quotes) latest = Math.max(latest, quote.time);
        status.setText("Cotação das " + DateFormat.format("HH:mm", latest)
                + " · atualiza a cada minuto · B3 com ~15 min de atraso");

        hero.removeAllViews();
        grid.removeAllViews();
        Stocks.Quote first = quotes.get(0);
        List<Stocks.Quote> others = first.index ? quotes.subList(1, quotes.size()) : quotes;
        if (first.index) hero.addView(heroCard(first), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));

        LinearLayout row = null;
        for (int i = 0; i < others.size(); i++) {
            if (i % COLUMNS == 0) {
                row = new LinearLayout(context);
                LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1);
                rowParams.topMargin = i == 0 ? 0 : px(10);
                grid.addView(row, rowParams);
            }
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1);
            if (i % COLUMNS != COLUMNS - 1) params.rightMargin = px(10);
            row.addView(smallCard(others.get(i)), params);
        }
        // Completa a última linha pra os cartões terem o mesmo tamanho.
        for (int i = others.size() % COLUMNS; i > 0 && i < COLUMNS; i++) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, 0, 1);
            if (i != COLUMNS - 1) params.rightMargin = px(10);
            row.addView(new View(context), params);
        }
    }

    private View heroCard(Stocks.Quote quote) {
        LinearLayout card = card();
        card.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout numbers = new LinearLayout(context);
        numbers.setOrientation(LinearLayout.VERTICAL);
        numbers.setGravity(Gravity.CENTER_VERTICAL);
        numbers.addView(text(quote.label, 18, MUTED));
        numbers.addView(text(price(quote), 38, 0xFFFFFFFF));
        numbers.addView(text(change(quote), 20, color(quote)));
        numbers.addView(text("Fechamento anterior " + number(quote.previousClose, quote.index), 13, MUTED));
        card.addView(numbers, new LinearLayout.LayoutParams(px(250), LinearLayout.LayoutParams.MATCH_PARENT));
        card.addView(new Chart(context, quote.points, quote.previousClose, color(quote), 2.5f * dp),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1));
        return card;
    }

    private View smallCard(Stocks.Quote quote) {
        LinearLayout card = card();
        card.setOrientation(LinearLayout.VERTICAL);
        card.addView(text(quote.label, 14, MUTED));
        card.addView(text(price(quote), 19, 0xFFFFFFFF));
        card.addView(text(change(quote), 14, color(quote)));
        LinearLayout.LayoutParams chartParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1);
        chartParams.topMargin = px(4);
        card.addView(new Chart(context, quote.points, quote.previousClose, color(quote), 1.6f * dp), chartParams);
        return card;
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(context);
        card.setPadding(px(14), px(10), px(14), px(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xCC1A2033);
        bg.setCornerRadius(16 * dp);
        card.setBackground(bg);
        return card;
    }

    private TextView text(String value, int sp, int color) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setSingleLine(true);
        view.setEllipsize(TextUtils.TruncateAt.END);
        return view;
    }

    private static String price(Stocks.Quote quote) {
        return quote.index ? number(quote.price, true) + " pts" : "R$ " + number(quote.price, false);
    }

    private static String number(double value, boolean index) {
        NumberFormat format = NumberFormat.getNumberInstance(BR);
        format.setMinimumFractionDigits(index ? 0 : 2);
        format.setMaximumFractionDigits(index ? 0 : 2);
        return format.format(value);
    }

    private static String change(Stocks.Quote quote) {
        return (quote.changePercent >= 0 ? "▲ " : "▼ ") + String.format(BR, "%.2f%%", Math.abs(quote.changePercent));
    }

    private static int color(Stocks.Quote quote) {
        return quote.changePercent >= 0 ? UP : DOWN;
    }

    private int px(int value) {
        return (int) (value * dp);
    }

    /** Gráfico de linha do dia, com área preenchida e o fechamento anterior tracejado. */
    private static final class Chart extends View {
        private final double[] points;
        private final double reference;
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dashed = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int color;

        Chart(Context context, double[] points, double reference, int color, float stroke) {
            super(context);
            this.points = points;
            this.reference = reference;
            this.color = color;
            line.setColor(color);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(stroke);
            line.setStrokeJoin(Paint.Join.ROUND);
            dashed.setColor(0x66A9B1C7);
            dashed.setStyle(Paint.Style.STROKE);
            dashed.setStrokeWidth(stroke * 0.6f);
            dashed.setPathEffect(new DashPathEffect(new float[]{stroke * 3, stroke * 3}, 0));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (points.length < 2) return;
            float w = getWidth(), h = getHeight(), pad = line.getStrokeWidth();
            double min = reference, max = reference;
            for (double p : points) {
                min = Math.min(min, p);
                max = Math.max(max, p);
            }
            if (max - min < 1e-9) max = min + 1;
            Path path = new Path();
            for (int i = 0; i < points.length; i++) {
                float x = pad + (w - 2 * pad) * i / (points.length - 1);
                float y = (float) (pad + (h - 2 * pad) * (max - points[i]) / (max - min));
                if (i == 0) path.moveTo(x, y);
                else path.lineTo(x, y);
            }
            Path area = new Path(path);
            area.lineTo(w - pad, h);
            area.lineTo(pad, h);
            area.close();
            fill.setShader(new LinearGradient(0, 0, 0, h, (color & 0x00FFFFFF) | 0x55000000,
                    color & 0x00FFFFFF, Shader.TileMode.CLAMP));
            canvas.drawPath(area, fill);
            float refY = (float) (pad + (h - 2 * pad) * (max - reference) / (max - min));
            canvas.drawLine(0, refY, w, refY, dashed);
            canvas.drawPath(path, line);
        }
    }
}

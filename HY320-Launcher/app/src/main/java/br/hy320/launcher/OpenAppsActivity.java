package br.hy320.launcher;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Lista os apps abertos, com X pra fechar cada um e "Fechar todos". */
public class OpenAppsActivity extends Activity {

    private static final int PILL_HEIGHT = 52;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private LayoutInflater inflater;
    private LinearLayout list;
    private TextView message;
    private View closeAll;
    private float dp;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        inflater = getLayoutInflater();
        dp = getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(px(48), px(28), px(48), px(28));

        TextView title = new TextView(this);
        title.setText("Apps abertos");
        title.setTextSize(30);
        title.setTextColor(getColor(R.color.text_primary));
        root.addView(title);

        message = new TextView(this);
        message.setTextSize(15);
        message.setTextColor(getColor(R.color.text_secondary));
        message.setPadding(0, px(4), 0, px(16));
        root.addView(message);

        closeAll = pill(R.drawable.ic_close, "Fechar todos", v -> closeAll());
        root.addView(closeAll, wrap());

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, px(16), 0, 0);
        ScrollView scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    private void reload() {
        executor.execute(() -> {
            List<OpenApps.App> apps = OpenApps.list(this);
            handler.post(() -> show(apps));
        });
    }

    private void show(List<OpenApps.App> apps) {
        if (isDestroyed()) return;
        list.removeAllViews();
        if (!OpenApps.hasAccess(this)) {
            message.setText("Falta a permissão de acesso ao uso para ver os apps abertos.");
        } else if (apps.isEmpty()) {
            message.setText("Nenhum app aberto agora.");
        } else {
            message.setText(apps.size() == 1 ? "1 app aberto" : apps.size() + " apps abertos");
        }
        closeAll.setVisibility(apps.isEmpty() ? View.GONE : View.VISIBLE);

        PackageManager pm = getPackageManager();
        for (OpenApps.App app : apps) {
            LinearLayout row = new LinearLayout(this);
            row.setPadding(0, 0, 0, px(12));

            Drawable icon;
            try {
                icon = pm.getApplicationIcon(app.pkg);
            } catch (PackageManager.NameNotFoundException e) {
                icon = null;
            }
            View open = pill(0, app.label, v -> open(app.pkg));
            ImageView image = open.findViewById(R.id.icon);
            image.setImageDrawable(icon);
            image.getLayoutParams().width = px(30);
            image.getLayoutParams().height = px(30);
            LinearLayout.LayoutParams openParams = new LinearLayout.LayoutParams(px(460), px(PILL_HEIGHT));
            openParams.rightMargin = px(16);
            row.addView(open, openParams);

            View close = pill(R.drawable.ic_close, "Fechar", v -> close(app.pkg, row));
            close.setTag("close");
            row.addView(close, wrap());
            list.addView(row);
        }
        if (!apps.isEmpty()) list.getChildAt(0).findViewWithTag("close").requestFocus();
    }

    private void open(String pkg) {
        Intent intent = OpenApps.launchIntent(this, pkg);
        if (intent == null) return;
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, "Não foi possível abrir", Toast.LENGTH_SHORT).show();
        }
    }

    private void close(String pkg, View row) {
        int index = list.indexOfChild(row);
        executor.execute(() -> {
            OpenApps.close(this, pkg);
            handler.post(() -> {
                list.removeView(row);
                int left = list.getChildCount();
                message.setText(left == 0 ? "Nenhum app aberto agora."
                        : left == 1 ? "1 app aberto" : left + " apps abertos");
                if (left == 0) {
                    closeAll.setVisibility(View.GONE);
                } else {
                    list.getChildAt(Math.min(index, left - 1)).findViewWithTag("close").requestFocus();
                }
            });
        });
    }

    private void closeAll() {
        executor.execute(() -> {
            int count = OpenApps.closeAll(this);
            handler.post(() -> {
                Toast.makeText(this, count == 1 ? "1 app fechado" : count + " apps fechados", Toast.LENGTH_SHORT).show();
                reload();
            });
        });
    }

    /** Botão no mesmo estilo dos atalhos da tela inicial. */
    private View pill(int iconRes, String label, View.OnClickListener onClick) {
        View item = inflater.inflate(R.layout.item_shortcut, list, false);
        ImageView icon = item.findViewById(R.id.icon);
        if (iconRes != 0) icon.setImageResource(iconRes);
        ((TextView) item.findViewById(R.id.label)).setText(label);
        item.setOnClickListener(onClick);
        return item;
    }

    private LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(PILL_HEIGHT));
    }

    private int px(int value) {
        return (int) (value * dp);
    }
}

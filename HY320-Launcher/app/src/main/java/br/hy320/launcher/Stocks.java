package br.hy320.launcher;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Cotações da bolsa pelo Yahoo Finance (grátis, sem chave; a B3 chega com ~15 min de atraso). */
final class Stocks {

    static final class Quote {
        final String label;
        final double price;
        final double previousClose;
        final double changePercent;
        final long time;
        final boolean index;
        /** Preços do dia a cada 5 min, pro gráfico. */
        final double[] points;

        Quote(String label, double price, double previousClose, long time, boolean index, double[] points) {
            this.label = label;
            this.price = price;
            this.previousClose = previousClose;
            this.changePercent = previousClose > 0 ? (price / previousClose - 1) * 100 : 0;
            this.time = time;
            this.index = index;
            this.points = points;
        }
    }

    // {símbolo no Yahoo, nome na tela}. O primeiro é o destaque (gráfico grande).
    private static final String[][] SYMBOLS = {
            {"^BVSP", "Ibovespa"},
            {"BRL=X", "Dólar"},
            {"PETR4.SA", "PETR4"},
            {"VALE3.SA", "VALE3"},
            {"ITUB4.SA", "ITUB4"},
            {"BBDC4.SA", "BBDC4"},
            {"BBAS3.SA", "BBAS3"},
            {"ABEV3.SA", "ABEV3"},
            {"WEGE3.SA", "WEGE3"},
            {"B3SA3.SA", "B3SA3"},
            {"AXIA3.SA", "AXIA3"},
            {"SUZB3.SA", "SUZB3"},
            {"PRIO3.SA", "PRIO3"},
    };

    private Stocks() {
    }

    /** Busca todas; as que falharem ficam de fora. Rode fora da thread principal. */
    static List<Quote> fetch() {
        List<Quote> quotes = new ArrayList<>();
        for (String[] symbol : SYMBOLS) {
            try {
                JSONObject result = new JSONObject(get("https://query1.finance.yahoo.com/v8/finance/chart/"
                        + symbol[0].replace("^", "%5E") + "?range=1d&interval=5m"))
                        .getJSONObject("chart").getJSONArray("result").getJSONObject(0);
                JSONObject meta = result.getJSONObject("meta");
                double price = meta.getDouble("regularMarketPrice");
                double previous = meta.optDouble("chartPreviousClose", meta.optDouble("previousClose", price));
                quotes.add(new Quote(symbol[1], price, previous, meta.optLong("regularMarketTime") * 1000,
                        symbol[0].startsWith("^"), points(result)));
            } catch (Exception ignored) {
            }
        }
        return quotes;
    }

    private static double[] points(JSONObject result) {
        JSONArray close = result.optJSONObject("indicators") == null ? null
                : result.getJSONObject("indicators").getJSONArray("quote").getJSONObject(0).optJSONArray("close");
        if (close == null) return new double[0];
        List<Double> values = new ArrayList<>();
        for (int i = 0; i < close.length(); i++) {
            if (!close.isNull(i)) values.add(close.optDouble(i));
        }
        double[] points = new double[values.size()];
        for (int i = 0; i < points.length; i++) points[i] = values.get(i);
        return points;
    }

    private static String get(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(8000);
        connection.setRequestProperty("User-Agent", "Mozilla/5.0");
        try (InputStream in = connection.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            connection.disconnect();
        }
    }
}

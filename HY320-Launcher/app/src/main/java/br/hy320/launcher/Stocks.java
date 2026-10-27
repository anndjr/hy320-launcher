package br.hy320.launcher;

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
        final double changePercent;
        final long time;
        final boolean index;

        Quote(String label, double price, double changePercent, long time, boolean index) {
            this.label = label;
            this.price = price;
            this.changePercent = changePercent;
            this.time = time;
            this.index = index;
        }
    }

    // {símbolo no Yahoo, nome na tela}
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
            {"ELET3.SA", "ELET3"},
            {"SUZB3.SA", "SUZB3"},
    };

    private Stocks() {
    }

    /** Busca todas; as que falharem ficam de fora. Rode fora da thread principal. */
    static List<Quote> fetch() {
        List<Quote> quotes = new ArrayList<>();
        for (String[] symbol : SYMBOLS) {
            try {
                JSONObject meta = new JSONObject(get("https://query1.finance.yahoo.com/v8/finance/chart/"
                        + symbol[0].replace("^", "%5E") + "?range=1d&interval=1d"))
                        .getJSONObject("chart").getJSONArray("result").getJSONObject(0).getJSONObject("meta");
                double price = meta.getDouble("regularMarketPrice");
                double previous = meta.optDouble("chartPreviousClose", meta.optDouble("previousClose", price));
                double change = previous > 0 ? (price / previous - 1) * 100 : 0;
                quotes.add(new Quote(symbol[1], price, change,
                        meta.optLong("regularMarketTime") * 1000, symbol[0].startsWith("^")));
            } catch (Exception ignored) {
            }
        }
        return quotes;
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

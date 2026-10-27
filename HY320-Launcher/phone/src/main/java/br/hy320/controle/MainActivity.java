package br.hy320.controle;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * HY Controle no celular: acha o projetor na rede de casa e abre o controle em tela cheia.
 * O endereço muda quando o roteador quer, então procura de novo sempre que a conexão falha.
 */
public class MainActivity extends Activity {

    private static final int BG = 0xFF0B1020;
    private static final int PICK_FILE = 1;
    private static final int[] PORTS = {8080, 8081, 8082, 8083, 8084, 8085, 8086, 8087, 8088, 8089, 8090};

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService search = Executors.newSingleThreadExecutor();
    private SharedPreferences prefs;
    private WebView web;
    private View overlay;
    private ProgressBar spinner;
    private TextView message;
    private Button retry;
    private ValueCallback<Uri[]> pendingFiles;
    private boolean searching;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("controle", MODE_PRIVATE);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG);

        web = new WebView(this);
        web.setBackgroundColor(BG);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        web.addJavascriptInterface(new Bridge(), "HY");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                // A vibração do navegador não funciona dentro do app: usa a do Android.
                view.evaluateJavascript("navigator.vibrate=function(ms){HY.vibrate(ms||12);return true;};", null);
                overlay.setVisibility(View.GONE);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                // Projetor desligado ou mudou de endereço: procura de novo.
                if (request.isForMainFrame()) find(false);
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (pendingFiles != null) pendingFiles.onReceiveValue(null);
                pendingFiles = callback;
                try {
                    startActivityForResult(params.createIntent(), PICK_FILE);
                    return true;
                } catch (ActivityNotFoundException e) {
                    pendingFiles = null;
                    return false;
                }
            }
        });
        root.addView(web);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setBackgroundColor(BG);
        int pad = dp(32);
        box.setPadding(pad, pad, pad, pad);
        spinner = new ProgressBar(this);
        box.addView(spinner);
        message = new TextView(this);
        message.setTextColor(0xFFC9D0E3);
        message.setTextSize(17);
        message.setGravity(Gravity.CENTER);
        message.setPadding(0, dp(20), 0, dp(20));
        box.addView(message);
        retry = new Button(this);
        retry.setText("Tentar de novo");
        retry.setOnClickListener(v -> find(true));
        box.addView(retry, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        overlay = box;
        root.addView(overlay);

        setContentView(root);
        find(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Voltou pro app: atualiza o status e a lista de apps.
        if (overlay.getVisibility() == View.GONE) web.evaluateJavascript("if(window.loadState)loadState();", null);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        search.shutdownNow();
        web.destroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == PICK_FILE && pendingFiles != null) {
            pendingFiles.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
            pendingFiles = null;
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    /** Procura o projetor: primeiro o último endereço que funcionou, depois a rede inteira. */
    private void find(boolean fullScan) {
        if (searching) return;
        searching = true;
        show("Procurando o projetor…", true);
        search.execute(() -> {
            String saved = prefs.getString("address", null);
            String found = !fullScan && saved != null && isProjector(saved) ? saved : scan();
            handler.post(() -> {
                searching = false;
                if (isDestroyed()) return;
                if (found == null) {
                    show("Projetor não encontrado.\n\nConfira se ele está ligado e se o celular está no mesmo Wi-Fi de casa.", false);
                    return;
                }
                prefs.edit().putString("address", found).apply();
                web.loadUrl("http://" + found + "/");
            });
        });
    }

    private void show(String text, boolean busy) {
        overlay.setVisibility(View.VISIBLE);
        message.setText(text);
        spinner.setVisibility(busy ? View.VISIBLE : View.GONE);
        retry.setVisibility(busy ? View.GONE : View.VISIBLE);
    }

    /** Testa todos os vizinhos da rede (x.x.x.1 a 254) ao mesmo tempo. */
    private String scan() {
        String prefix = networkPrefix();
        if (prefix == null) return null;
        ExecutorService pool = Executors.newFixedThreadPool(48);
        try {
            List<Future<String>> results = new ArrayList<>();
            for (int host = 1; host <= 254; host++) {
                String ip = prefix + host;
                results.add(pool.submit(() -> {
                    // O HY Home quase sempre usa a 8080; as outras só se ela estiver ocupada.
                    if (!open(ip, 8080)) return null;
                    for (int port : PORTS) {
                        if (isProjector(ip + ":" + port)) return ip + ":" + port;
                    }
                    return null;
                }));
            }
            for (Future<String> result : results) {
                String address = result.get();
                if (address != null) return address;
            }
        } catch (Exception ignored) {
        } finally {
            pool.shutdownNow();
        }
        return null;
    }

    /** "192.168.15." da rede Wi-Fi (o projetor nunca está nos dados móveis). */
    private String networkPrefix() {
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm == null) return null;
        for (Network network : cm.getAllNetworks()) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            boolean local = caps != null && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));
            LinkProperties link = local ? cm.getLinkProperties(network) : null;
            if (link == null) continue;
            for (LinkAddress address : link.getLinkAddresses()) {
                if (address.getAddress() instanceof Inet4Address) {
                    String ip = address.getAddress().getHostAddress();
                    return ip.substring(0, ip.lastIndexOf('.') + 1);
                }
            }
        }
        return null;
    }

    private static boolean open(String ip, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(ip, port), 600);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** É o HY Home mesmo? (e não outro aparelho com a mesma porta aberta) */
    private static boolean isProjector(String address) {
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL("http://" + address + "/status").openConnection();
            connection.setConnectTimeout(1500);
            connection.setReadTimeout(2000);
            try (InputStream in = connection.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[1024];
                int n;
                while ((n = in.read(buffer)) > 0 && out.size() < 4096) out.write(buffer, 0, n);
                return out.toString("UTF-8").contains("HY Controle");
            } finally {
                connection.disconnect();
            }
        } catch (Exception e) {
            return false;
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    /** Ponte pro JavaScript da página. */
    private final class Bridge {
        @JavascriptInterface
        public void vibrate(int ms) {
            Vibrator vibrator = getSystemService(Vibrator.class);
            if (vibrator == null) return;
            if (Build.VERSION.SDK_INT >= 26) {
                vibrator.vibrate(VibrationEffect.createOneShot(Math.max(1, Math.min(ms, 200)), VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                vibrator.vibrate(Math.min(ms, 200));
            }
        }
    }
}

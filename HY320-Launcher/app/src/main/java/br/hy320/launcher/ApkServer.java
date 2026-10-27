package br.hy320.launcher;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * Servidor HTTP mínimo: serve a página de envio e recebe o APK direto
 * numa sessão do PackageInstaller (sem arquivo temporário).
 */
final class ApkServer extends Thread {

    interface Listener {
        void onProgress(String fileName, int percent);

        void onInstalling(String fileName);

        void onError(String message);
    }

    private static final String PAGE = """
            <!doctype html><html lang="pt-BR"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <title>HY Home - Enviar APK</title>
            <style>
            body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;background:#0c1222;color:#fff;font-family:system-ui,sans-serif}
            .card{width:min(420px,calc(100% - 32px));background:#1a2033;border-radius:20px;padding:28px;box-sizing:border-box}
            h1{margin:0 0 6px;font-size:22px}p{margin:0 0 20px;color:#a9b1c7}
            label{display:block;border:2px dashed #3a4566;border-radius:14px;padding:28px 12px;text-align:center;cursor:pointer;color:#a9b1c7;word-break:break-all}
            input{display:none}
            button{margin-top:16px;width:100%;padding:14px;border:0;border-radius:12px;background:#2b6cff;color:#fff;font-size:17px;font-weight:600}
            button:disabled{opacity:.4}
            .bar{height:8px;background:#0c1222;border-radius:4px;margin-top:18px;overflow:hidden}
            .fill{height:100%;width:0;background:#6ea8ff;transition:width .2s}
            #msg{margin-top:12px;min-height:20px;color:#a9b1c7}
            </style></head><body><div class="card">
            <h1>Enviar APK pro projetor</h1>
            <p>Escolha um arquivo .apk. A instalação aparece na tela do projetor.</p>
            <label for="file" id="pick">Toque para escolher o APK</label>
            <input type="file" id="file" accept=".apk,application/vnd.android.package-archive">
            <button id="send" disabled>Enviar</button>
            <div class="bar"><div class="fill" id="fill"></div></div>
            <div id="msg"></div>
            </div><script>
            const file=document.getElementById('file'),send=document.getElementById('send'),
              fill=document.getElementById('fill'),msg=document.getElementById('msg'),pick=document.getElementById('pick');
            file.onchange=()=>{const f=file.files[0];pick.textContent=f?f.name:'Toque para escolher o APK';
              send.disabled=!f;fill.style.width='0';msg.textContent='';};
            send.onclick=()=>{const f=file.files[0];if(!f)return;send.disabled=true;msg.textContent='Enviando...';
              const x=new XMLHttpRequest();x.open('POST','/upload?name='+encodeURIComponent(f.name));
              x.upload.onprogress=e=>{if(e.lengthComputable){const p=Math.round(e.loaded*100/e.total);
                fill.style.width=p+'%';msg.textContent='Enviando... '+p+'%';}};
              x.onload=()=>{msg.textContent=x.status==200?'Pronto! Confirme a instalação no projetor.':x.responseText;send.disabled=false;};
              x.onerror=()=>{msg.textContent='Falhou. O projetor está na tela Receber APK?';send.disabled=false;};
              x.send(f);};
            </script></body></html>
            """;

    private final Context context;
    private final Listener listener;
    private final ServerSocket server;

    ApkServer(Context context, Listener listener) throws IOException {
        super("ApkServer");
        this.context = context.getApplicationContext();
        this.listener = listener;
        this.server = openSocket();
    }

    int getPort() {
        return server.getLocalPort();
    }

    void shutdown() {
        interrupt();
        try {
            server.close();
        } catch (IOException ignored) {
        }
    }

    private static ServerSocket openSocket() throws IOException {
        IOException last = null;
        for (int port = 8080; port <= 8090; port++) {
            ServerSocket socket = new ServerSocket();
            try {
                socket.setReuseAddress(true);
                socket.bind(new InetSocketAddress(port));
                return socket;
            } catch (IOException e) {
                socket.close();
                last = e;
            }
        }
        throw last;
    }

    @Override
    public void run() {
        while (!isInterrupted()) {
            try (Socket client = server.accept()) {
                client.setSoTimeout(30000);
                handle(client);
            } catch (IOException | RuntimeException e) {
                if (server.isClosed()) return;
            }
        }
    }

    private void handle(Socket client) throws IOException {
        InputStream in = new BufferedInputStream(client.getInputStream());
        OutputStream out = client.getOutputStream();

        String requestLine = readLine(in);
        if (requestLine == null) return;
        long length = -1;
        String line;
        while ((line = readLine(in)) != null && !line.isEmpty()) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("Content-Length")) {
                length = Long.parseLong(line.substring(colon + 1).trim());
            }
        }

        String[] parts = requestLine.split(" ");
        String method = parts[0];
        String path = parts.length > 1 ? parts[1] : "/";

        if (method.equals("GET") && (path.equals("/") || path.startsWith("/?"))) {
            respond(out, 200, "text/html; charset=utf-8", PAGE);
        } else if (method.equals("POST") && path.startsWith("/upload")) {
            String name = queryParam(path, "name", "app.apk");
            if (length <= 0) {
                respond(out, 411, "text/plain; charset=utf-8", "Arquivo vazio");
                return;
            }
            try {
                install(in, length, name);
                respond(out, 200, "text/plain; charset=utf-8", "ok");
            } catch (IOException | RuntimeException e) {
                listener.onError("Falha ao receber " + name);
                respond(out, 500, "text/plain; charset=utf-8", "Falha: " + e.getMessage());
            }
        } else {
            respond(out, 404, "text/plain; charset=utf-8", "Não encontrado");
        }
    }

    private void install(InputStream in, long length, String name) throws IOException {
        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setSize(length);
        int sessionId = installer.createSession(params);

        try (PackageInstaller.Session session = installer.openSession(sessionId)) {
            try (OutputStream apk = session.openWrite("base.apk", 0, length)) {
                byte[] buffer = new byte[64 * 1024];
                long received = 0;
                int lastPercent = -1;
                while (received < length) {
                    int n = in.read(buffer, 0, (int) Math.min(buffer.length, length - received));
                    if (n < 0) throw new IOException("Conexão interrompida");
                    apk.write(buffer, 0, n);
                    received += n;
                    int percent = (int) (received * 100 / length);
                    if (percent != lastPercent) {
                        lastPercent = percent;
                        listener.onProgress(name, percent);
                    }
                }
                session.fsync(apk);
            }

            listener.onInstalling(name);
            Intent result = new Intent(context, InstallResultReceiver.class);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT
                    | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            session.commit(PendingIntent.getBroadcast(context, sessionId, result, flags).getIntentSender());
        } catch (IOException | RuntimeException e) {
            installer.abandonSession(sessionId);
            throw e;
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') break;
            if (b != '\r') line.write(b);
            if (line.size() > 8192) throw new IOException("Cabeçalho grande demais");
        }
        if (b == -1 && line.size() == 0) return null;
        return line.toString("ISO-8859-1");
    }

    private static String queryParam(String path, String key, String fallback) {
        int q = path.indexOf('?');
        if (q < 0) return fallback;
        for (String pair : path.substring(q + 1).split("&")) {
            if (pair.startsWith(key + "=")) {
                try {
                    return URLDecoder.decode(pair.substring(key.length() + 1), "UTF-8");
                } catch (IllegalArgumentException | java.io.UnsupportedEncodingException e) {
                    return fallback;
                }
            }
        }
        return fallback;
    }

    private static void respond(OutputStream out, int code, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String reason = code == 200 ? "OK" : "Error";
        String head = "HTTP/1.1 " + code + " " + reason + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        out.write(bytes);
        out.flush();
    }
}

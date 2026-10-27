package br.hy320.launcher;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

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
 * Servidor HTTP mínimo do "celular como controle": serve a página e recebe
 * comandos, APKs (direto numa sessão do PackageInstaller) e papel de parede.
 */
final class ApkServer extends Thread {

    interface Listener {
        void onProgress(String fileName, int percent);

        void onInstalling(String fileName);

        void onError(String message);
    }

    private static final int MAX_TEXT = 64 * 1024;

    private static final String PAGE = """
            <!doctype html><html lang="pt-BR"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1,user-scalable=no">
            <title>HY Home</title>
            <style>
            *{box-sizing:border-box;-webkit-tap-highlight-color:transparent}
            body{margin:0;background:#0c1222;color:#fff;font-family:system-ui,sans-serif;padding:16px 16px 80px}
            h1{font-size:20px;margin:4px 0 16px}
            .card{background:#1a2033;border-radius:18px;padding:18px;margin-bottom:14px;max-width:480px;margin-left:auto;margin-right:auto}
            h2{font-size:16px;margin:0 0 12px;color:#a9b1c7;font-weight:600}
            button{border:0;border-radius:12px;background:#2a3557;color:#fff;font-size:16px;padding:14px 10px;font-weight:600;touch-action:manipulation}
            button:active{background:#6ea8ff;color:#0c1222}
            button.main{background:#2b6cff}
            .pad{display:grid;grid-template-columns:repeat(3,1fr);gap:8px}
            .pad button{height:64px;font-size:22px}
            .row{display:flex;gap:8px;margin-top:8px}
            .row button{flex:1}
            input[type=text],input[type=url]{width:100%;padding:14px;border-radius:12px;border:1px solid #3a4566;background:#0c1222;color:#fff;font-size:17px}
            input[type=file]{display:none}
            label.pick{display:block;border:2px dashed #3a4566;border-radius:12px;padding:18px 10px;text-align:center;color:#a9b1c7;word-break:break-all}
            .bar{height:6px;background:#0c1222;border-radius:3px;margin-top:10px;overflow:hidden}
            .fill{height:100%;width:0;background:#6ea8ff}
            #toast{position:fixed;left:16px;right:16px;bottom:16px;padding:14px;border-radius:12px;background:#2b6cff;text-align:center;opacity:0;transition:opacity .2s;pointer-events:none}
            #toast.show{opacity:1}#toast.bad{background:#b3364a}
            </style></head><body>
            <h1>HY Home &middot; controle</h1>
            <div class="card" id="state" style="display:none"></div>

            <div class="card"><h2>Controle</h2>
            <div class="pad">
            <span></span><button data-k="up">&#9650;</button><span></span>
            <button data-k="left">&#9664;</button><button class="main" data-k="ok">OK</button><button data-k="right">&#9654;</button>
            <span></span><button data-k="down">&#9660;</button><span></span>
            </div>
            <div class="row"><button data-k="back">Voltar</button><button data-k="home">In&iacute;cio</button></div>
            <div class="row"><button data-k="voldown">Vol &minus;</button><button data-k="mute">Mudo</button><button data-k="volup">Vol +</button></div>
            </div>

            <div class="card"><h2>Digitar no campo selecionado</h2>
            <input type="text" id="txt" placeholder="Digite ou use o microfone do teclado" autocomplete="off">
            <div class="row"><button class="main" id="enter">Enter</button><button id="clear">Limpar</button></div>
            <div class="row"><button id="yt">Pesquisar no YouTube</button></div>
            </div>

            <div class="card"><h2>Abrir link no projetor</h2>
            <input type="url" id="url" placeholder="Cole um link do YouTube ou site">
            <div class="row"><button class="main" id="open">Abrir</button></div>
            </div>

            <div class="card"><h2>Timer de dormir</h2>
            <div class="row"><button data-min="15">15</button><button data-min="30">30</button><button data-min="60">60</button><button data-min="90">90 min</button></div>
            <div class="row"><button data-min="0">Cancelar timer</button></div>
            </div>

            <div class="card"><h2>Instalar APK</h2>
            <label class="pick" for="apk" id="apkname">Escolher arquivo .apk</label>
            <input type="file" id="apk" accept=".apk,application/vnd.android.package-archive">
            <div class="row"><button class="main" id="apksend">Enviar e instalar</button></div>
            <div class="bar"><div class="fill" id="fill"></div></div>
            </div>

            <div class="card"><h2>Papel de parede</h2>
            <label class="pick" for="wp" id="wpname">Escolher foto</label>
            <input type="file" id="wp" accept="image/*">
            <div class="row"><button class="main" id="wpsend">Usar como fundo</button><button id="wpreset">Remover</button></div>
            </div>

            <div id="toast"></div>
            <script>
            const $=id=>document.getElementById(id);
            function toast(t,bad){const m=$('toast');m.textContent=t;m.className=bad?'show bad':'show';
              clearTimeout(m.timer);m.timer=setTimeout(()=>{m.className='';},2500);}
            async function post(url,body,okMsg){
              try{const r=await fetch(url,{method:'POST',body:body});const t=await r.text();
                if(r.ok){if(okMsg)toast(okMsg);}else toast(t,true);return r.ok;}
              catch(e){toast('Sem conexão com o projetor',true);return false;}}
            document.querySelectorAll('[data-k]').forEach(b=>{b.onclick=()=>post('/key?k='+b.dataset.k,'');});
            let typing;
            $('txt').oninput=()=>{clearTimeout(typing);typing=setTimeout(()=>post('/text',$('txt').value),400);};
            $('enter').onclick=()=>post('/text?enter=1',$('txt').value,'Enviado');
            $('clear').onclick=()=>{$('txt').value='';post('/text','');};
            $('yt').onclick=()=>post('/youtube',$('txt').value,'Pesquisando no YouTube');
            $('open').onclick=()=>post('/link',$('url').value,'Abrindo no projetor');
            document.querySelectorAll('[data-min]').forEach(b=>{b.onclick=()=>post('/sleep?min='+b.dataset.min,'',
              b.dataset.min=='0'?'Timer cancelado':'Projetor dorme em '+b.dataset.min+' min');});
            $('apk').onchange=()=>{const f=$('apk').files[0];$('apkname').textContent=f?f.name:'Escolher arquivo .apk';$('fill').style.width='0';};
            $('apksend').onclick=()=>{const f=$('apk').files[0];if(!f){toast('Escolha um APK',true);return;}
              const x=new XMLHttpRequest();x.open('POST','/upload?name='+encodeURIComponent(f.name));
              x.upload.onprogress=e=>{if(e.lengthComputable)$('fill').style.width=Math.round(e.loaded*100/e.total)+'%';};
              x.onload=()=>{if(x.status==200)toast('Pronto! Confirme a instalação no projetor');else toast(x.responseText,true);};
              x.onerror=()=>toast('Falhou o envio',true);
              x.send(f);toast('Enviando...');};
            $('wp').onchange=()=>{const f=$('wp').files[0];$('wpname').textContent=f?f.name:'Escolher foto';};
            $('wpsend').onclick=()=>{const f=$('wp').files[0];if(!f){toast('Escolha uma foto',true);return;}
              toast('Enviando...');post('/wallpaper',f,'Papel de parede trocado');};
            $('wpreset').onclick=()=>post('/wallpaper/reset','','Papel de parede removido');
            fetch('/status').then(r=>r.text()).then(t=>{const s=$('state');s.textContent=t;s.style.display='block';
              s.style.whiteSpace='pre-line';s.style.color=t.indexOf('conectado e funcionando')>=0?'#7ee2a8':'#ffb4c0';});
            </script></body></html>
            """;

    // Um servidor só por processo, compartilhado entre o HY Controle e a tela Celular.
    private static ApkServer shared;
    private static int users;

    private final Context context;
    private final ServerSocket server;
    private final Listener toaster;
    private volatile Listener listener;

    private ApkServer(Context context) throws IOException {
        super("ApkServer");
        this.context = context.getApplicationContext();
        this.server = openSocket();
        Handler main = new Handler(Looper.getMainLooper());
        this.toaster = new Listener() {
            @Override
            public void onProgress(String fileName, int percent) {
            }

            @Override
            public void onInstalling(String fileName) {
                main.post(() -> Toast.makeText(ApkServer.this.context,
                        "Instalando " + fileName + "…", Toast.LENGTH_SHORT).show());
            }

            @Override
            public void onError(String message) {
                main.post(() -> Toast.makeText(ApkServer.this.context, message, Toast.LENGTH_LONG).show());
            }
        };
        this.listener = toaster;
    }

    static synchronized ApkServer acquire(Context context) throws IOException {
        if (shared == null) {
            shared = new ApkServer(context);
            shared.start();
        }
        users++;
        return shared;
    }

    static synchronized void release() {
        if (--users > 0 || shared == null) return;
        shared.shutdown();
        shared = null;
        users = 0;
    }

    /** null volta pros avisos padrão (Toast). */
    void setListener(Listener listener) {
        this.listener = listener != null ? listener : toaster;
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
        long length = 0;
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
        int q = path.indexOf('?');
        String route = q < 0 ? path : path.substring(0, q);

        if (method.equals("GET") && route.equals("/")) {
            respond(out, 200, "text/html; charset=utf-8", PAGE);
            return;
        }
        if (method.equals("GET") && route.equals("/status")) {
            respond(out, 200, "text/plain; charset=utf-8", ControlService.describeState(context));
            return;
        }
        if (!method.equals("POST")) {
            respond(out, 404, "text/plain; charset=utf-8", "Não encontrado");
            return;
        }

        String error;
        try {
            switch (route) {
                case "/upload":
                    error = upload(in, length, queryParam(path, "name", "app.apk"));
                    break;
                case "/key":
                    error = Commands.key(context, queryParam(path, "k", ""));
                    break;
                case "/text":
                    error = Commands.text(context, readText(in, length),
                            "1".equals(queryParam(path, "enter", "0")));
                    break;
                case "/youtube":
                    error = Commands.searchYouTube(context, readText(in, length));
                    break;
                case "/link":
                    error = Commands.openLink(context, readText(in, length));
                    break;
                case "/sleep":
                    error = Commands.sleep(context, Integer.parseInt(queryParam(path, "min", "0")));
                    break;
                case "/wallpaper":
                    error = Commands.saveWallpaper(context, in, length);
                    break;
                case "/wallpaper/reset":
                    error = Commands.resetWallpaper(context);
                    break;
                default:
                    respond(out, 404, "text/plain; charset=utf-8", "Não encontrado");
                    return;
            }
        } catch (IOException | RuntimeException e) {
            error = "Erro: " + e.getMessage();
        }

        if (error == null) {
            respond(out, 200, "text/plain; charset=utf-8", "ok");
        } else {
            respond(out, 409, "text/plain; charset=utf-8", error);
        }
    }

    private String upload(InputStream in, long length, String name) {
        if (length <= 0) return "Arquivo vazio";
        try {
            install(in, length, name);
            return null;
        } catch (IOException | RuntimeException e) {
            listener.onError("Falha ao receber " + name);
            return "Falha: " + e.getMessage();
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

    private static String readText(InputStream in, long length) throws IOException {
        if (length > MAX_TEXT) throw new IOException("Texto grande demais");
        byte[] body = new byte[(int) length];
        int read = 0;
        while (read < length) {
            int n = in.read(body, read, body.length - read);
            if (n < 0) throw new IOException("Conexão interrompida");
            read += n;
        }
        return new String(body, StandardCharsets.UTF_8);
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

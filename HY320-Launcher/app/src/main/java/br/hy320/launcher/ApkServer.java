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
            <meta name="viewport" content="width=device-width,initial-scale=1,user-scalable=no,viewport-fit=cover">
            <meta name="theme-color" content="#0b1020">
            <meta name="mobile-web-app-capable" content="yes">
            <link rel="manifest" href="/manifest.json"><link rel="icon" href="/icon.png"><link rel="apple-touch-icon" href="/icon.png">
            <title>HY Controle</title>
            <style>
            :root{--bg:#0b1020;--card:#161d31;--card2:#222c49;--line:#ffffff14;--muted:#9aa4bf;--accent:#5b8cff;--good:#7ee2a8;--bad:#ff8a9a}
            *{box-sizing:border-box;-webkit-tap-highlight-color:transparent;-webkit-user-select:none;user-select:none}
            [hidden]{display:none!important}
            body{margin:0;background:var(--bg);color:#fff;font-family:system-ui,Roboto,sans-serif;overscroll-behavior:none}
            header{position:sticky;top:0;z-index:2;display:flex;align-items:center;gap:10px;padding:16px 20px 10px;background:var(--bg)}
            header h1{font-size:19px;margin:0;flex:1;font-weight:700}
            #dot{width:10px;height:10px;border-radius:50%;background:#555}
            #dot.on{background:var(--good);box-shadow:0 0 10px var(--good)}#dot.off{background:var(--bad)}
            #stxt{font-size:13px;color:var(--muted)}
            main{padding:4px 16px calc(100px + env(safe-area-inset-bottom));max-width:480px;margin:0 auto}
            .tab{display:none}.tab.on{display:block}
            .card{background:var(--card);border-radius:20px;padding:16px;margin-bottom:12px}
            h2{font-size:12px;letter-spacing:.08em;text-transform:uppercase;color:var(--muted);margin:0 0 12px;font-weight:600}
            button{font:inherit;border:0;border-radius:14px;background:var(--card2);color:#fff;font-size:16px;font-weight:600;padding:14px 10px;touch-action:manipulation;transition:transform .08s,background .15s}
            button:active,button.press{transform:scale(.95);background:var(--accent)}
            button.main{background:var(--accent)}
            .row{display:flex;gap:8px;margin-top:8px}.row>*{flex:1}
            .seg{display:flex;background:var(--card);border-radius:14px;padding:4px;margin-bottom:6px}
            .seg button{flex:1;background:none;padding:10px;font-size:14px;color:var(--muted)}
            .seg button.on{background:var(--card2);color:#fff}
            .seg button:active{transform:none}
            .dpad{position:relative;width:min(78vw,300px);aspect-ratio:1;margin:14px auto 20px;border-radius:50%;background:var(--card);box-shadow:inset 0 0 0 1px var(--line)}
            .dpad button{position:absolute;width:33%;height:33%;padding:0;border-radius:50%;background:none;color:var(--muted);font-size:24px}
            .dpad button:active,.dpad button.press{transform:none;background:#ffffff18;color:#fff}
            .dpad .up{top:2%;left:33.5%}.dpad .down{bottom:2%;left:33.5%}.dpad .left{left:2%;top:33.5%}.dpad .right{right:2%;top:33.5%}
            .dpad .ok{top:30%;left:30%;width:40%;height:40%;background:var(--card2);color:#fff;font-size:19px;box-shadow:0 6px 20px #0008}
            .dpad .ok:active,.dpad .ok.press{background:var(--accent)}
            #pad{height:min(78vw,300px);margin:14px 0 20px;border-radius:24px;background:var(--card);box-shadow:inset 0 0 0 1px var(--line);display:flex;align-items:center;justify-content:center;text-align:center;color:var(--muted);font-size:14px;padding:20px;touch-action:none}
            #pad.touch{background:var(--card2)}
            .keys{display:grid;grid-template-columns:repeat(3,1fr);gap:8px}
            .keys button{padding:16px 4px;font-size:15px}
            textarea,input[type=url]{width:100%;padding:14px;border-radius:14px;border:1px solid var(--line);background:var(--bg);color:#fff;font:inherit;font-size:17px;-webkit-user-select:text;user-select:text}
            textarea{height:88px;resize:none}
            label.sw{display:flex;align-items:center;gap:10px;margin-top:12px;color:var(--muted);font-size:14px}
            label.sw input{width:20px;height:20px;accent-color:var(--accent)}
            .app{display:flex;align-items:center;gap:12px;padding:10px 0;border-bottom:1px solid var(--line)}
            .app:last-child{border-bottom:0}
            .app span{flex:1;font-size:16px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
            .app button{flex:0 0 46px;height:46px;padding:0;border-radius:50%;background:#3a2338;color:var(--bad);font-size:18px}
            .none{color:var(--muted);padding:6px 0}
            .chips{display:grid;grid-template-columns:repeat(4,1fr);gap:8px}
            input[type=file]{display:none}
            label.pick{display:block;border:2px dashed #2f3a5c;border-radius:14px;padding:16px 10px;text-align:center;color:var(--muted);word-break:break-all}
            .bar{height:6px;background:var(--bg);border-radius:3px;margin-top:10px;overflow:hidden}.fill{height:100%;width:0;background:var(--accent)}
            pre{white-space:pre-wrap;color:var(--muted);font:13px/1.5 system-ui;margin:0}
            nav{position:fixed;left:0;right:0;bottom:0;z-index:3;display:flex;gap:4px;background:#0f1528f2;backdrop-filter:blur(10px);border-top:1px solid var(--line);padding:6px 8px calc(6px + env(safe-area-inset-bottom))}
            nav button{flex:1;display:flex;flex-direction:column;align-items:center;gap:3px;background:none;color:var(--muted);font-size:12px;padding:8px 0;border-radius:12px}
            nav button:active{transform:none;background:var(--card)}
            nav button.on{color:#fff;background:var(--card)}
            nav svg{width:24px;height:24px;fill:currentColor}
            #toast{position:fixed;left:16px;right:16px;bottom:calc(84px + env(safe-area-inset-bottom));max-width:448px;margin:0 auto;padding:13px;border-radius:14px;background:var(--accent);text-align:center;opacity:0;transform:translateY(8px);transition:.2s;pointer-events:none;z-index:4}
            #toast.show{opacity:1;transform:none}#toast.bad{background:#b3364a}
            </style></head><body>
            <header><span id="dot"></span><h1>HY Controle</h1><span id="stxt">conectando…</span></header>
            <main>

            <section class="tab on" id="t-ctl">
            <div class="seg"><button id="m-btn" class="on">Botões</button><button id="m-pad">Touchpad</button></div>
            <div id="dpadwrap"><div class="dpad">
            <button class="up" data-k="up" aria-label="Cima">▲</button>
            <button class="left" data-k="left" aria-label="Esquerda">◀</button>
            <button class="ok" data-k="ok">OK</button>
            <button class="right" data-k="right" aria-label="Direita">▶</button>
            <button class="down" data-k="down" aria-label="Baixo">▼</button>
            </div></div>
            <div id="pad" hidden>Deslize para mover<br>Toque para OK</div>
            <div class="keys">
            <button data-k="back">↩ Voltar</button><button data-k="home">⌂ Início</button><button data-tab="apps">▦ Apps</button>
            <button data-k="voldown" aria-label="Diminuir volume">🔉 −</button><button data-k="mute" aria-label="Mudo">🔇</button><button data-k="volup" aria-label="Aumentar volume">🔊 +</button>
            </div>
            </section>

            <section class="tab" id="t-busca">
            <div class="card"><h2>Texto</h2>
            <textarea id="txt" placeholder="Digite ou use o microfone do teclado"></textarea>
            <div class="row"><button class="main" id="yt">YouTube</button><button class="main" id="appsearch">No app aberto</button></div>
            <div class="row"><button id="send">Enviar ao campo</button><button id="clear">Limpar</button></div>
            <label class="sw"><input type="checkbox" id="live"> Digitar ao vivo no campo selecionado</label>
            </div>
            <div class="card"><h2>Link</h2>
            <input type="url" id="url" placeholder="Cole um link do YouTube">
            <div class="row"><button class="main" id="open">Abrir no projetor</button></div>
            </div>
            </section>

            <section class="tab" id="t-apps">
            <div class="card"><h2>Apps abertos</h2>
            <div id="apps"><div class="none">Carregando…</div></div>
            <div class="row"><button class="main" id="closeall">Fechar todos e liberar memória</button></div>
            <div class="row"><button id="appsreload">Atualizar lista</button></div>
            </div>
            </section>

            <section class="tab" id="t-mais">
            <div class="card"><h2>Timer de dormir (min)</h2>
            <div class="chips"><button data-min="15">15</button><button data-min="30">30</button><button data-min="60">60</button><button data-min="90">90</button></div>
            <div class="row"><button data-min="0">Cancelar timer</button></div>
            </div>
            <div class="card"><h2>Papel de parede</h2>
            <label class="pick" for="wp" id="wpname">Escolher foto</label>
            <input type="file" id="wp" accept="image/*">
            <div class="row"><button class="main" id="wpsend">Usar como fundo</button><button id="wpreset">Remover</button></div>
            </div>
            <div class="card"><h2>Instalar APK</h2>
            <label class="pick" for="apk" id="apkname">Escolher arquivo .apk</label>
            <input type="file" id="apk" accept=".apk,application/vnd.android.package-archive">
            <div class="row"><button class="main" id="apksend">Enviar e instalar</button></div>
            <div class="bar"><div class="fill" id="fill"></div></div>
            </div>
            <div class="card"><h2>Status do projetor</h2><pre id="state">…</pre></div>
            </section>
            </main>

            <nav>
            <button data-tab="ctl" class="on"><svg viewBox="0 0 24 24"><path d="M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20zm0 6a4 4 0 1 1 0 8 4 4 0 0 1 0-8z"/></svg>Controle</button>
            <button data-tab="busca"><svg viewBox="0 0 24 24"><path d="M15.5 14h-.8l-.3-.3A6.5 6.5 0 1 0 9.5 16a6.5 6.5 0 0 0 4.2-1.6l.3.3v.8l5 5 1.5-1.5-5-5zm-6 0a4.5 4.5 0 1 1 0-9 4.5 4.5 0 0 1 0 9z"/></svg>Buscar</button>
            <button data-tab="apps"><svg viewBox="0 0 24 24"><path d="M3 13h8V3H3v10zm0 8h8v-6H3v6zm10 0h8V11h-8v10zm0-18v6h8V3h-8z"/></svg>Apps</button>
            <button data-tab="mais"><svg viewBox="0 0 24 24"><path d="M6 10a2 2 0 1 0 0 4 2 2 0 0 0 0-4zm12 0a2 2 0 1 0 0 4 2 2 0 0 0 0-4zm-6 0a2 2 0 1 0 0 4 2 2 0 0 0 0-4z"/></svg>Mais</button>
            </nav>
            <div id="toast"></div>

            <script>
            const $=id=>document.getElementById(id);
            const buzz=()=>{try{navigator.vibrate&&navigator.vibrate(12);}catch(e){}};
            const store=(k,v)=>{try{if(v===undefined)return localStorage.getItem(k);localStorage.setItem(k,v);}catch(e){return null;}};
            function toast(t,bad){const m=$('toast');m.textContent=t;m.className=bad?'show bad':'show';
              clearTimeout(m.timer);m.timer=setTimeout(()=>{m.className='';},2400);}
            async function post(url,body,okMsg){
              try{const r=await fetch(url,{method:'POST',body:body});const t=await r.text();
                if(r.ok){if(okMsg)toast(okMsg===true?t:okMsg);}else toast(t,true);return r.ok;}
              catch(e){toast('Sem conexão com o projetor',true);return false;}}

            // Teclas: uma por vez; segurando seta ou volume, repete.
            let busy=false;
            async function key(k,repeat){if(repeat&&busy)return;busy=true;buzz();await post('/key?k='+k,'');busy=false;}
            const REPEAT={up:1,down:1,left:1,right:1,volup:1,voldown:1};
            document.querySelectorAll('[data-k]').forEach(b=>{const k=b.dataset.k;let wait,loop;
              const stop=()=>{clearTimeout(wait);clearInterval(loop);b.classList.remove('press');};
              b.addEventListener('pointerdown',e=>{e.preventDefault();b.classList.add('press');key(k);
                if(REPEAT[k])wait=setTimeout(()=>{loop=setInterval(()=>key(k,true),170);},450);});
              ['pointerup','pointerleave','pointercancel'].forEach(ev=>b.addEventListener(ev,stop));
              b.addEventListener('contextmenu',e=>e.preventDefault());});

            // Abas
            function tab(name){document.querySelectorAll('.tab').forEach(s=>s.classList.toggle('on',s.id=='t-'+name));
              document.querySelectorAll('nav button').forEach(b=>b.classList.toggle('on',b.dataset.tab==name));
              store('tab',name);if(name=='apps')loadApps();if(name=='mais')loadState();window.scrollTo(0,0);}
            document.querySelectorAll('[data-tab]').forEach(b=>{b.onclick=()=>{buzz();tab(b.dataset.tab);};});

            // Botões ou touchpad
            function mode(m){$('m-btn').classList.toggle('on',m=='btn');$('m-pad').classList.toggle('on',m=='pad');
              $('dpadwrap').hidden=m!='btn';$('pad').hidden=m!='pad';store('mode',m);}
            $('m-btn').onclick=()=>mode('btn');$('m-pad').onclick=()=>mode('pad');
            const pad=$('pad'),STEP=45;let sx=null,sy=0,moved=false;
            pad.addEventListener('pointerdown',e=>{sx=e.clientX;sy=e.clientY;moved=false;pad.classList.add('touch');pad.setPointerCapture(e.pointerId);});
            pad.addEventListener('pointermove',e=>{if(sx===null)return;const dx=e.clientX-sx,dy=e.clientY-sy;
              if(Math.max(Math.abs(dx),Math.abs(dy))<STEP)return;moved=true;sx=e.clientX;sy=e.clientY;
              key(Math.abs(dx)>Math.abs(dy)?(dx>0?'right':'left'):(dy>0?'down':'up'),true);});
            const lift=()=>{pad.classList.remove('touch');sx=null;};
            pad.addEventListener('pointerup',()=>{if(sx!==null&&!moved)key('ok');lift();});
            pad.addEventListener('pointercancel',lift);

            // Buscar
            let typing;
            $('txt').oninput=()=>{if(!$('live').checked)return;clearTimeout(typing);typing=setTimeout(()=>post('/text',$('txt').value),400);};
            $('send').onclick=()=>{buzz();post('/text?enter=1',$('txt').value,'Enviado');};
            $('clear').onclick=()=>{$('txt').value='';if($('live').checked)post('/text','');};
            $('yt').onclick=()=>{buzz();post('/youtube',$('txt').value,'Pesquisando no YouTube');};
            $('appsearch').onclick=()=>{buzz();post('/appsearch',$('txt').value,'Pesquisando no app aberto');};
            $('open').onclick=()=>{buzz();post('/link',$('url').value,'Abrindo no projetor');};

            // Apps abertos
            function loadApps(){fetch('/apps').then(r=>r.json()).then(list=>{const box=$('apps');box.innerHTML='';
              if(!list.length){const n=document.createElement('div');n.className='none';n.textContent='Nenhum app aberto';box.append(n);}
              list.forEach(a=>{const row=document.createElement('div');row.className='app';
                const name=document.createElement('span');name.textContent=a.label;
                const x=document.createElement('button');x.textContent='✕';x.setAttribute('aria-label','Fechar '+a.label);
                x.onclick=async()=>{buzz();if(await post('/apps/close?pkg='+encodeURIComponent(a.pkg),'',a.label+' fechado'))loadApps();};
                row.append(name,x);box.append(row);});}).catch(()=>{});}
            $('closeall').onclick=async()=>{buzz();toast('Fechando…');if(await post('/apps/closeall','',true))loadApps();};
            $('appsreload').onclick=()=>{buzz();loadApps();};

            // Mais
            document.querySelectorAll('[data-min]').forEach(b=>{b.onclick=()=>{buzz();post('/sleep?min='+b.dataset.min,'',
              b.dataset.min=='0'?'Timer cancelado':'Projetor dorme em '+b.dataset.min+' min');};});
            $('apk').onchange=()=>{const f=$('apk').files[0];$('apkname').textContent=f?f.name:'Escolher arquivo .apk';$('fill').style.width='0';};
            $('apksend').onclick=()=>{const f=$('apk').files[0];if(!f){toast('Escolha um APK',true);return;}
              const x=new XMLHttpRequest();x.open('POST','/upload?name='+encodeURIComponent(f.name));
              x.upload.onprogress=e=>{if(e.lengthComputable)$('fill').style.width=Math.round(e.loaded*100/e.total)+'%';};
              x.onload=()=>{if(x.status==200)toast('Pronto! Confirme a instalação no projetor');else toast(x.responseText,true);};
              x.onerror=()=>toast('Falhou o envio',true);
              x.send(f);toast('Enviando…');};
            $('wp').onchange=()=>{const f=$('wp').files[0];$('wpname').textContent=f?f.name:'Escolher foto';};
            $('wpsend').onclick=()=>{const f=$('wp').files[0];if(!f){toast('Escolha uma foto',true);return;}
              toast('Enviando…');post('/wallpaper',f,'Papel de parede trocado');};
            $('wpreset').onclick=()=>post('/wallpaper/reset','','Papel de parede removido');

            // Status: bolinha no topo, detalhes na aba Mais
            function loadState(){fetch('/status').then(r=>r.text()).then(t=>{const ok=t.indexOf('conectado e funcionando')>=0;
              $('dot').className=ok?'on':'off';$('stxt').textContent=ok?'Conectado':'Controle desligado';$('state').textContent=t;})
              .catch(()=>{$('dot').className='off';$('stxt').textContent='Sem conexão';});}

            mode(store('mode')||'btn');tab(store('tab')||'ctl');loadState();setInterval(loadState,15000);
            </script></body></html>
            """;

    private static final String MANIFEST = """
            {"name":"HY Controle","short_name":"HY Controle","start_url":"/","display":"standalone",
            "background_color":"#0b1020","theme_color":"#0b1020",
            "icons":[{"src":"/icon.png","sizes":"192x192","type":"image/png"}]}
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
        if (method.equals("GET") && route.equals("/manifest.json")) {
            respond(out, 200, "application/manifest+json", MANIFEST);
            return;
        }
        if (method.equals("GET") && route.equals("/icon.png")) {
            respond(out, 200, "image/png", icon());
            return;
        }
        if (method.equals("GET") && route.equals("/apps")) {
            respond(out, 200, "application/json; charset=utf-8", OpenApps.toJson(OpenApps.list(context)));
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
                case "/apps/close":
                    OpenApps.close(context, queryParam(path, "pkg", ""));
                    error = null;
                    break;
                case "/apps/closeall":
                    respond(out, 200, "text/plain; charset=utf-8",
                            OpenApps.describe(context, OpenApps.closeAll(context)));
                    return;
                case "/appsearch":
                    error = Commands.searchInApp(context, readText(in, length));
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

    private static byte[] iconPng;

    /** Ícone do app em PNG pra tela inicial do celular (feito uma vez e guardado). */
    private synchronized byte[] icon() {
        if (iconPng == null) {
            android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(192, 192, android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);
            canvas.drawColor(0xFF0B1020);
            android.graphics.drawable.Drawable drawable = context.getDrawable(R.drawable.ic_launcher);
            drawable.setBounds(0, 0, 192, 192);
            drawable.draw(canvas);
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, png);
            bitmap.recycle();
            iconPng = png.toByteArray();
        }
        return iconPng;
    }

    private static void respond(OutputStream out, int code, String type, byte[] bytes) throws IOException {
        String reason = code == 200 ? "OK" : "Error";
        String head = "HTTP/1.1 " + code + " " + reason + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        out.write(bytes);
        out.flush();
    }

    private static void respond(OutputStream out, int code, String type, String body) throws IOException {
        respond(out, code, type, body.getBytes(StandardCharsets.UTF_8));
    }
}

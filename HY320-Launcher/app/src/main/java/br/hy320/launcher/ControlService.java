package br.hy320.launcher;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityWindowInfo;
import android.view.accessibility.AccessibilityNodeInfo;

import java.io.IOException;

/**
 * "HY Controle": serviço de acessibilidade que mantém o servidor do celular
 * ligado e executa setas, OK, voltar, digitação e o timer de dormir.
 */
public class ControlService extends AccessibilityService {

    static volatile ControlService instance;
    /** Último problema ao iniciar, mostrado no /status pra diagnóstico. */
    static volatile String lastProblem;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable sleepTask = this::sleepNow;
    private boolean holdsServer;
    private volatile long sleepAt;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        lastProblem = null;
        try {
            ApkServer.acquire(this);
            holdsServer = true;
        } catch (IOException e) {
            lastProblem = "servidor não abriu: " + e.getMessage();
        }
    }

    @Override
    public boolean onUnbind(Intent intent) {
        stop();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        stop();
        super.onDestroy();
    }

    private void stop() {
        if (instance == this) instance = null;
        handler.removeCallbacks(sleepTask);
        sleepAt = 0;
        if (holdsServer) {
            holdsServer = false;
            ApkServer.release();
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }

    // ---------- Estado (pra tela Celular e /status) ----------

    /** O usuário ligou o HY Controle em Acessibilidade? */
    static boolean isEnabledInSettings(Context context) {
        String enabled = Settings.Secure.getString(context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) return false;
        ComponentName me = new ComponentName(context, ControlService.class);
        for (String item : enabled.split(":")) {
            ComponentName name = ComponentName.unflattenFromString(item);
            if (me.equals(name)) return true;
        }
        return false;
    }

    /**
     * Religa o serviço sozinho se o firmware desligou (ele limpa a lista ao reiniciar).
     * Só funciona depois de: adb shell pm grant br.hy320.launcher android.permission.WRITE_SECURE_SETTINGS
     */
    static void ensureEnabled(Context context) {
        if (isEnabledInSettings(context)
                && Settings.Secure.getInt(context.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1) return;
        if (context.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS")
                != PackageManager.PERMISSION_GRANTED) return;
        String me = new ComponentName(context, ControlService.class).flattenToString();
        String enabled = Settings.Secure.getString(context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        String value = enabled == null || enabled.isEmpty() ? me : enabled + ":" + me;
        try {
            Settings.Secure.putString(context.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, value);
            Settings.Secure.putInt(context.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 1);
        } catch (SecurityException e) {
            lastProblem = "sem permissão pra religar: " + e.getMessage();
        }
    }

    private static boolean watching;

    /**
     * O firmware apaga a lista sempre que outro app abre por cima do launcher;
     * observa a configuração e religa na hora, com o launcher em segundo plano.
     */
    static void watch(Context context) {
        if (watching) return;
        watching = true;
        Context app = context.getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());
        ContentObserver observer = new ContentObserver(main) {
            @Override
            public void onChange(boolean selfChange) {
                ensureEnabled(app);
            }
        };
        app.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), false, observer);
        app.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ACCESSIBILITY_ENABLED), false, observer);
        ensureEnabled(app);
    }

    static String describeState(Context context) {
        boolean enabled = isEnabledInSettings(context);
        StringBuilder text = new StringBuilder("HY Controle: ");
        if (instance != null) {
            text.append("conectado e funcionando");
        } else if (enabled) {
            text.append("ligado nas configurações, mas o Android não iniciou o serviço.")
                    .append("\nTente: desligar e ligar de novo em Acessibilidade, ou reiniciar o projetor.");
        } else {
            text.append("desligado. Ative em Configurações > Acessibilidade > HY Controle.");
            if (Build.VERSION.SDK_INT >= 33) {
                text.append("\nSe estiver bloqueado: Configurações > Apps > HY Home > menu (⋮) > Permitir configurações restritas.");
            }
        }
        if (lastProblem != null) text.append("\nProblema: ").append(lastProblem);
        text.append("\nTeclas diretas: ").append(KeyInjector.available() ? "funcionando" : "indisponíveis");
        if (instance == null) {
            // Detalhes crus pra descobrir onde o firmware trava.
            String raw = Settings.Secure.getString(context.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            int on = Settings.Secure.getInt(context.getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED, -1);
            boolean listed = false;
            AccessibilityManager manager = context.getSystemService(AccessibilityManager.class);
            if (manager != null) {
                for (AccessibilityServiceInfo info : manager.getInstalledAccessibilityServiceList()) {
                    if (context.getPackageName().equals(info.getResolveInfo().serviceInfo.packageName)) listed = true;
                }
            }
            text.append("\n[diag] sistema reconhece o serviço: ").append(listed ? "sim" : "NÃO")
                    .append(" · acessibilidade geral: ").append(on)
                    .append(" · ativos: ").append(raw == null || raw.isEmpty() ? "(nenhum)" : raw);
        }
        text.append("\nAndroid ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")");
        return text.toString();
    }

    // ---------- Controle ----------

    boolean key(String key) {
        switch (key) {
            case "back":
                return performGlobalAction(GLOBAL_ACTION_BACK);
            case "home":
                return performGlobalAction(GLOBAL_ACTION_HOME);
            case "up":
                return move(View.FOCUS_UP, GLOBAL_ACTION_DPAD_UP);
            case "down":
                return move(View.FOCUS_DOWN, GLOBAL_ACTION_DPAD_DOWN);
            case "left":
                return move(View.FOCUS_LEFT, GLOBAL_ACTION_DPAD_LEFT);
            case "right":
                return move(View.FOCUS_RIGHT, GLOBAL_ACTION_DPAD_RIGHT);
            case "ok":
                return click();
            default:
                return false;
        }
    }

    /** Pacote do app que está na tela agora, ou "" se não souber. */
    String foregroundPackage() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        return root == null || root.getPackageName() == null ? "" : root.getPackageName().toString();
    }

    private boolean move(int direction, int globalAction) {
        // Android 13+ tem setas nativas; antes disso movemos o foco pela árvore de views.
        if (Build.VERSION.SDK_INT >= 33) return performGlobalAction(globalAction);
        AccessibilityNodeInfo focused = findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        AccessibilityNodeInfo next = focused != null
                ? focused.focusSearch(direction)
                : firstFocusable(getRootInActiveWindow());
        return next != null && next.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
    }

    private boolean click() {
        if (Build.VERSION.SDK_INT >= 33) return performGlobalAction(GLOBAL_ACTION_DPAD_CENTER);
        AccessibilityNodeInfo node = findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        while (node != null) {
            if (node.isClickable()) return node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            node = node.getParent();
        }
        return false;
    }

    private static AccessibilityNodeInfo firstFocusable(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isFocusable() && node.isVisibleToUser()) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = firstFocusable(node.getChild(i));
            if (found != null) return found;
        }
        return null;
    }

    /** Escreve no campo de texto selecionado. Retorna null ou a mensagem de erro. */
    String setText(String text, boolean enter) {
        AccessibilityNodeInfo field = findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (field == null || !field.isEditable()) return "Selecione um campo de texto no projetor";

        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        if (!field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            return "Esse campo não aceitou o texto";
        }
        if (enter) {
            if (Build.VERSION.SDK_INT < 30) return "Enter não funciona nessa versão do Android";
            field.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId());
        }
        return null;
    }

    /**
     * Pesquisa no app que está aberto: usa o campo de texto que estiver na tela;
     * se não tiver, toca no botão de busca do app e espera o campo aparecer.
     */
    String searchInApp(String query) {
        if (query.trim().isEmpty()) return "Digite o que quer procurar";
        // SmartTube e YouTube têm busca própria que já funciona pelo link de pesquisa.
        AccessibilityNodeInfo root = getRootInActiveWindow();
        String pkg = root == null ? "" : String.valueOf(root.getPackageName());
        if (pkg.contains("smarttube") || pkg.startsWith("com.google.android.youtube")) {
            return Commands.searchYouTube(this, query);
        }
        AccessibilityNodeInfo field = findEditable();
        AccessibilityNodeInfo button = null;
        // A busca costuma ficar no topo e some quando a lista desce: sobe até ela aparecer.
        for (int i = 0; field == null && i < 5; i++) {
            button = findSearchButton(getRootInActiveWindow());
            if (button != null || i == 4 || !KeyInjector.press(KeyInjector.code("up"))) break;
            if (!pause(400)) return "Interrompido";
            field = findEditable();
        }
        if (field == null) {
            if (button == null) return "Não achei a busca desse app. Abra a tela de busca dele e tente de novo";
            if (!button.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return "Não consegui abrir a busca desse app";
            for (int i = 0; i < 10 && field == null; i++) {
                if (!pause(300)) return "Interrompido";
                field = findEditable();
            }
            if (field == null) return "Abri a busca, mas o app não tem campo de texto que aceite digitação";
        }
        field.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, query.trim());
        if (!field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return "Esse campo não aceitou o texto";
        if (Build.VERSION.SDK_INT >= 30) {
            field.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId());
        }
        return null;
    }

    private AccessibilityNodeInfo findEditable() {
        AccessibilityNodeInfo focused = findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (focused != null && focused.isEditable()) return focused;
        return firstMatch(getRootInActiveWindow(), n -> n.isEditable() && n.isVisibleToUser());
    }

    private static boolean pause(long ms) {
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            return false;
        }
    }

    /**
     * Botão clicável que fala em busca (nome, texto ou id); se não tiver, um ícone com esse
     * nome dentro de um botão. Ignora telas e listas inteiras que só têm "search" no id.
     */
    private static AccessibilityNodeInfo findSearchButton(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo button = firstMatch(root, n -> n.isVisibleToUser() && n.isClickable()
                && isSearchWord(n.getContentDescription() + " " + n.getText() + " " + n.getViewIdResourceName()));
        if (button != null) return button;
        AccessibilityNodeInfo icon = firstMatch(root, n -> n.isVisibleToUser()
                && isSearchWord(n.getContentDescription() + " " + n.getText()));
        while (icon != null && !icon.isClickable()) icon = icon.getParent();
        return icon;
    }

    private static boolean isSearchWord(String text) {
        text = text.toLowerCase();
        return text.contains("search") || text.contains("pesquis") || text.contains("busca") || text.contains("procurar");
    }

    private static AccessibilityNodeInfo firstMatch(AccessibilityNodeInfo node,
                                                    java.util.function.Predicate<AccessibilityNodeInfo> test) {
        if (node == null) return null;
        if (test.test(node)) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = firstMatch(node.getChild(i), test);
            if (found != null) return found;
        }
        return null;
    }

    // ---------- Timer de dormir ----------

    void setSleepTimer(int minutes) {
        handler.removeCallbacks(sleepTask);
        if (minutes <= 0) {
            sleepAt = 0;
            return;
        }
        sleepAt = System.currentTimeMillis() + minutes * 60_000L;
        handler.postDelayed(sleepTask, minutes * 60_000L);
    }

    long getSleepAt() {
        return sleepAt;
    }

    private void sleepNow() {
        sleepAt = 0;
        // O launcher abre a tela escura assim que volta pro início.
        MainActivity.sleepRequested = true;
        performGlobalAction(GLOBAL_ACTION_HOME);
        // Abre o menu de energia e escolhe "Desligar"; se não achar, fica só a tela escura.
        // O firmware ignora GLOBAL_ACTION_POWER_DIALOG, mas a tecla Power abre o menu.
        handler.postDelayed(() -> {
            if (!KeyInjector.press(KeyInjector.KEY_POWER)) performGlobalAction(GLOBAL_ACTION_POWER_DIALOG);
            handler.postDelayed(this::clickPowerOff, 2000);
        }, 1500);
    }

    private void clickPowerOff() {
        boolean found = false, clicked = false;
        for (AccessibilityNodeInfo node : findPowerOff()) {
            found = true;
            while (node != null && !node.isClickable()) node = node.getParent();
            if (node != null && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                clicked = true;
                break;
            }
        }
        lastProblem = "timer: menu " + (found ? "achado" : "não achado") + (clicked ? ", clicado" : "");
        // "Desligar" já vem selecionado no menu: se o clique não resolveu, aperta OK.
        boolean seen = found;
        handler.postDelayed(() -> {
            if (!seen || !findPowerOff().isEmpty()) {
                KeyInjector.press(KeyInjector.code("ok"));
                lastProblem += ", OK apertado";
            }
        }, 1500);
    }

    private java.util.List<AccessibilityNodeInfo> findPowerOff() {
        java.util.List<AccessibilityNodeInfo> roots = new java.util.ArrayList<>();
        for (AccessibilityWindowInfo window : getWindows()) {
            AccessibilityNodeInfo root = window.getRoot();
            if (root != null) roots.add(root);
        }
        AccessibilityNodeInfo active = getRootInActiveWindow();
        if (active != null) roots.add(active);
        java.util.List<AccessibilityNodeInfo> nodes = new java.util.ArrayList<>();
        for (AccessibilityNodeInfo root : roots) {
            for (String label : new String[]{"Desligar", "Power off", "Shut down"}) {
                nodes.addAll(root.findAccessibilityNodeInfosByText(label));
            }
        }
        return nodes;
    }
}

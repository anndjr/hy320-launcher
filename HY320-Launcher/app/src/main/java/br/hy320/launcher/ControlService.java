package br.hy320.launcher;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
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
        if (Build.VERSION.SDK_INT >= 28) {
            handler.postDelayed(() -> performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN), 2000);
        }
    }
}

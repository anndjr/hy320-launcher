package br.hy320.launcher;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

import java.io.IOException;

/**
 * "HY Controle": serviço de acessibilidade que mantém o servidor do celular
 * ligado e executa setas, OK, voltar, digitação e o timer de dormir.
 */
public class ControlService extends AccessibilityService implements ApkServer.Listener {

    static volatile ControlService instance;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable sleepTask = this::sleepNow;
    private ApkServer server;
    private volatile long sleepAt;

    @Override
    protected void onServiceConnected() {
        instance = this;
        try {
            server = new ApkServer(this, this);
            server.start();
        } catch (IOException e) {
            server = null;
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
        instance = null;
        handler.removeCallbacks(sleepTask);
        sleepAt = 0;
        if (server != null) {
            server.shutdown();
            server = null;
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }

    ApkServer getServer() {
        return server;
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

    // ---------- Avisos do servidor (quando a tela Celular não está aberta) ----------

    @Override
    public void onProgress(String fileName, int percent) {
    }

    @Override
    public void onInstalling(String fileName) {
        handler.post(() -> Toast.makeText(this, "Instalando " + fileName + "…", Toast.LENGTH_SHORT).show());
    }

    @Override
    public void onError(String message) {
        handler.post(() -> Toast.makeText(this, message, Toast.LENGTH_LONG).show());
    }
}

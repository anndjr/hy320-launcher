package br.hy320.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Religa o HY Controle quando o HY Home é atualizado ou o projetor liga, mesmo com
 * outro app na tela (o firmware apaga a acessibilidade nesses momentos).
 */
public class RestartReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        ControlService.watch(context);
        ControlService.ensureEnabled(context);
    }
}

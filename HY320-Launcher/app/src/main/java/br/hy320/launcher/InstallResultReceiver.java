package br.hy320.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.widget.Toast;

/** Recebe o resultado da instalação e abre a tela de confirmação do sistema. */
public class InstallResultReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(confirm);
            }
        } else if (status == PackageInstaller.STATUS_SUCCESS) {
            Toast.makeText(context, "App instalado!", Toast.LENGTH_LONG).show();
        } else if (status != PackageInstaller.STATUS_FAILURE_ABORTED) {
            String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            Toast.makeText(context, "Falha na instalação" + (message != null ? ": " + message : ""),
                    Toast.LENGTH_LONG).show();
        }
    }
}

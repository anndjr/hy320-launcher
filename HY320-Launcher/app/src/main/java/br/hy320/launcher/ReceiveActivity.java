package br.hy320.launcher;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;

/** Tela "Receber APK": o servidor só fica ligado enquanto ela está aberta. */
public class ReceiveActivity extends Activity implements ApkServer.Listener {

    private ApkServer server;
    private TextView address;
    private TextView status;
    private View permissionBox;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_receive);
        address = findViewById(R.id.address);
        status = findViewById(R.id.status);
        permissionBox = findViewById(R.id.permission_box);
        findViewById(R.id.allow_button).setOnClickListener(v -> openInstallPermission());
    }

    @Override
    protected void onStart() {
        super.onStart();
        String ip = MainActivity.ipAddress(this);
        if (ip == null) {
            address.setText("Sem Wi-Fi");
            status.setText("Conecte o projetor ao Wi-Fi para receber arquivos");
            return;
        }
        try {
            server = new ApkServer(this, this);
            server.start();
            address.setText("http://" + ip + ":" + server.getPort());
            status.setText("Aguardando arquivo…");
        } catch (IOException e) {
            address.setText("—");
            status.setText("Não foi possível iniciar o recebimento");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean allowed = Build.VERSION.SDK_INT < 26 || getPackageManager().canRequestPackageInstalls();
        permissionBox.setVisibility(allowed ? View.GONE : View.VISIBLE);
        if (!allowed) findViewById(R.id.allow_button).requestFocus();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (server != null) {
            server.shutdown();
            server = null;
        }
    }

    private void openInstallPermission() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName())));
        } catch (ActivityNotFoundException e) {
            try {
                startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS));
            } catch (ActivityNotFoundException e2) {
                Toast.makeText(this, "Libere \"fontes desconhecidas\" nas configurações", Toast.LENGTH_LONG).show();
            }
        }
    }

    // Chamados pela thread do servidor.

    @Override
    public void onProgress(String fileName, int percent) {
        runOnUiThread(() -> status.setText("Recebendo " + fileName + "… " + percent + "%"));
    }

    @Override
    public void onInstalling(String fileName) {
        runOnUiThread(() -> status.setText("Recebido! Confirme a instalação de " + fileName));
    }

    @Override
    public void onError(String message) {
        runOnUiThread(() -> status.setText(message));
    }
}

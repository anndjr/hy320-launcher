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

/**
 * Tela "Celular": mostra o endereço da página de controle. Se o HY Controle
 * estiver ativo, usa o servidor dele (sempre ligado); senão liga um próprio
 * só enquanto esta tela está aberta (aí só o envio de APK/papel de parede funciona).
 */
public class ReceiveActivity extends Activity implements ApkServer.Listener {

    private ApkServer server;
    private TextView address;
    private TextView status;
    private TextView controlState;
    private View controlBox;
    private View permissionBox;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_receive);
        address = findViewById(R.id.address);
        status = findViewById(R.id.status);
        controlBox = findViewById(R.id.control_box);
        controlState = findViewById(R.id.control_state);
        permissionBox = findViewById(R.id.permission_box);
        findViewById(R.id.allow_button).setOnClickListener(v -> openInstallPermission());
        findViewById(R.id.control_button).setOnClickListener(v -> open(
                new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), Settings.ACTION_SETTINGS));
    }

    @Override
    protected void onStart() {
        super.onStart();
        String ip = MainActivity.ipAddress(this);
        if (ip == null) {
            address.setText("Sem Wi-Fi");
            status.setText("Conecte o projetor ao Wi-Fi");
            return;
        }

        try {
            server = ApkServer.acquire(this);
            server.setListener(this);
        } catch (IOException e) {
            address.setText("—");
            status.setText("Não foi possível iniciar a conexão");
            return;
        }
        address.setText("http://" + ip + ":" + server.getPort());
        status.setText("Pronto! Abra o endereço no celular");
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean controlOn = ControlService.instance != null;
        controlBox.setVisibility(controlOn ? View.GONE : View.VISIBLE);
        controlState.setText(ControlService.describeState(this));

        boolean installAllowed = Build.VERSION.SDK_INT < 26 || getPackageManager().canRequestPackageInstalls();
        permissionBox.setVisibility(installAllowed ? View.GONE : View.VISIBLE);

        if (!controlOn) {
            findViewById(R.id.control_button).requestFocus();
        } else if (!installAllowed) {
            findViewById(R.id.allow_button).requestFocus();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (server != null) {
            server.setListener(null);
            server = null;
            ApkServer.release();
        }
    }

    private void openInstallPermission() {
        open(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())),
                Settings.ACTION_SECURITY_SETTINGS);
    }

    private void open(Intent intent, String fallbackAction) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            try {
                startActivity(new Intent(fallbackAction));
            } catch (ActivityNotFoundException e2) {
                Toast.makeText(this, "Não encontrei essa configuração", Toast.LENGTH_LONG).show();
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

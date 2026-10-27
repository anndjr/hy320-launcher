package br.hy320.launcher;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.WindowManager;

/** Tela preta com brilho mínimo, aberta quando o timer de dormir termina. */
public class SleepActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sleep);
        WindowManager.LayoutParams params = getWindow().getAttributes();
        params.screenBrightness = 0.01f;
        getWindow().setAttributes(params);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        finish();
        return true;
    }
}

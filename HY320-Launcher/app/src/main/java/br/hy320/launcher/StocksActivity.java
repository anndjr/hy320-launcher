package br.hy320.launcher;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;

/** Painel da bolsa aberto pelo atalho "Bolsa"; OK ou Voltar fecham. */
public class StocksActivity extends Activity {

    private StocksBoard board;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        board = new StocksBoard(this);
        setContentView(board.view());
    }

    @Override
    protected void onResume() {
        super.onResume();
        board.start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        board.stop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        board.destroy();
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            finish();
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }
}

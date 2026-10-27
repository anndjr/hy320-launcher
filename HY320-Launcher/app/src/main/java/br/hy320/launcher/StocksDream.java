package br.hy320.launcher;

import android.service.dreams.DreamService;

/** Descanso de tela: painel da bolsa. Qualquer tecla do controle fecha. */
public class StocksDream extends DreamService {

    private StocksBoard board;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        board = new StocksBoard(this);
        setContentView(board.view());
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        board.start();
    }

    @Override
    public void onDreamingStopped() {
        super.onDreamingStopped();
        board.stop();
    }

    @Override
    public void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (board != null) board.destroy();
    }
}

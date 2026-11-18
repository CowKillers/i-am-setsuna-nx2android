package org.nx2a.launcher;

import android.app.NativeActivity;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;

/**
 * v22: activity do jogo. A stock android.app.NativeActivity nunca
 * pedia modo imersivo — o host nativo (front_android.c:941) so poe
 * AWINDOW_FLAG_FULLSCREEN, que esconde a barra de status mas nao a
 * barra de navegacao por gestos — por isso a pilula branca do Android
 * aparecia por cima do gameplay nas fotos do S23. Esta subclasse e a
 * mesma NativeActivity de sempre, com o mesmo setImmersive() do menu
 * do launcher, aplicado ao criar, ao voltar o foco e ao resumir.
 * O meta-data android.app.lib_name=dmhost continua carregando o host.
 */
public class GameActivity extends NativeActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setImmersive();
    }

    @Override
    protected void onResume() {
        super.onResume();
        setImmersive();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) setImmersive();
    }

    private void setImmersive() {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }
}

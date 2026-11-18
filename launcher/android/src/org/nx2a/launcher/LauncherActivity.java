package org.nx2a.launcher;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Generic nx2android launcher, one code base for every ported game.
 * The app label comes from the APK manifest, so this dex needs no
 * per-game rebuild: the game is whatever data pack lives in this
 * app's external files folder (RomFS/, shaders.dmsv, env.txt).
 * - Play: android.app.NativeActivity in this same package, only when
 *   RomFS/ and shaders.dmsv are present; otherwise Play opens Import.
 * - Import game: a SAF folder or zip whose top level is RomFS,
 *   shaders.dmsv and env.txt (the porter data pack), copied with
 *   progress into getExternalFilesDir(). No game file, key or driver
 *   is shipped in this code or in the APK: drivers are imported by
 *   the player (see Drivers below).
 * - Settings: only the keys the host reads in settings.txt
 *   (docked, fps, opacity, overlay, rumble, touchaim).
 * - Drivers: the system driver by default. An imported driver lives
 *   in getFilesDir()/gpu_drivers/<slug>/ (internal storage, never
 *   sdcard, as adrenotools requires) and is written to env.txt as
 *   DM_VK_DRIVER=<absolute path> only. The host loads it via
 *   adrenotools (libadrenotools.so beside libdmhost.so); any load
 *   failure falls back to the system driver and is logged, never
 *   silent. This launcher never writes DM_FSR, DM_RENDER_SCALE or
 *   DM_VK_HOOK_DIR: the host has no such options.
 * - About / log: package, version, data status, current driver and
 *   the last 30 lines of log.txt, inside About.
 */
public class LauncherActivity extends Activity {
    private static final int REQ_PICK_TREE = 2001;
    private static final int REQ_PICK_ZIP = 2002;
    private static final int REQ_PICK_DRIVER_SO = 2003;
    private static final int REQ_PICK_DRIVER_ZIP = 2004;
    private static final int REQ_PICK_SAVE_ZIP = 2005;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile boolean importing = false;
    private boolean artLocked = false;
    private android.app.Dialog importLoadingDialog;
    private TextView importLoadingPct;
    private TextView importLoadingDetail;
    private TextView importLoadingFile;
    private View importLoadingFill;
    private View importLoadingEmpty;
    private int importLastPct = -1;
    private String importCurrentFile = "";
    private Button playBtn;
    private TextView statusText;
    private SnowView snowView;
    // v15: largura da coluna de botoes da principal, em px — 28% da
    // largura da tela (max 320dp), calculada em onCreate a partir do
    // DisplayMetrics real. A v14 usava 45% (max 560dp) e o usuario
    // achou comprido demais; a v13 usava MATCH_PARENT e os botoes
    // cobriam a Setsuna; a referencia os tem so na coluna esquerda.
    private int menuWidthPx;

    private String driverChoice = "system";
    private String importedSlug = "";
    private String importedSoName = "";
    private LinearLayout configDriversRow;  // v20: linha plainValueRow do submenu Jogo (era Button na v18, lightRow na v19)
    private Typeface gameFont = null;
    private static final int DIALOG_TEXT = 0xFF2A2A2A;  // v13: texto escuro sobre o painel neve

    // v21: doacao via Pix (chave aleatoria do usuario).
    // O payload e o BR Code EXATO gerado pelo banco dele (decodificado
    // da foto, CRC16 0D1E conferido, round-trip ZXing provado).
    // NAO regenerar a partir da chave: o txid/CEP/cidade do banco
    // fazem parte do payload e o CRC tem que continuar valido.
    private static final String PIX_KEY = "SUA-CHAVE-PIX-AQUI";
    private static final String PIX_PAYLOAD = "SEU-PAYLOAD-PIX-AQUI";

    private Typeface gameTypeface() {
        if (gameFont != null) return gameFont;
        // Sem fonte externa: serif negrito do sistema, sem asset para faltar.
        gameFont = Typeface.create(Typeface.SERIF, Typeface.BOLD);
        return gameFont;
    }

    private void applyGameFont(TextView tv, float letterSpacing) {
        try {
            tv.setTypeface(gameTypeface(), Typeface.BOLD);
            tv.setLetterSpacing(letterSpacing);
        } catch (Exception ignored) {
            tv.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
        }
    }

    private int setDocked = 1;
    private int setFps = 1;
    private int setOpacity = 60;
    private int setOverlay = 1;
    private int setRumble = 1;
    private int setTouchaim = 0;

    // Taxa de quadros do jogo (30 ou 60 fps), via DM_FPS_TARGET no
    // env.txt. SOZINHO ele NAO destrava o jogo: o Setsuna nao tem a
    // pagina FRAME_STEP do rewriter (loader.c dm_fps_state_apply nao
    // faz nada sem ela) e nao existe linha de fpslock para o main
    // dele (o global de vsync count do Unity 5.5 nao foi localizado
    // com confianca no main — ver PORT-NOTES, Launcher v9). O que
    // destrava de verdade e DM_PRESENT_INTERVAL=display, escrito
    // junto por saveFpsTarget(): o host passa a ignorar o present
    // interval 2 que o jogo pede ao NVN e apresenta 1 frame por tick
    // do display (hle_nvn.c present_itv, o mesmo corte ja provado
    // noutro jogo). A 30, DM_PRESENT_INTERVAL=game devolve o
    // comportamento original. Vale na proxima abertura do jogo, nao
    // ao vivo. PADRAO 30 (o modo provado e estavel); 60 e opt-in —
    // o jogo nasceu travado em 30 e so o teste no aparelho diz se
    // roda a 60 de verdade, se repete frame ou se acelera em dobro.
    // v10: o env.txt e SINCRONIZADO com a Taxa em toda abertura do
    // launcher (onCreate) e em todo Jogar (playGame) — loadFpsTarget()
    // + saveFpsTarget() — sem depender de um toque no botao. A v8
    // deixava o env.txt so com DM_FPS_TARGET=60 (sem a linha
    // DM_PRESENT_INTERVAL) e o handheld ficava a 30 fps mesmo com 60
    // selecionado (log_10: "interval mode game").
    private int fpsTarget = 30;

    // Traducao PT-BR embutida (v10): os 10 arquivos da Central de
    // Traducoes viajam no APK (assets/ptbr/, mesmo layout do RomFS)
    // e sao extraidos para files/ptbr-overlay/ na primeira abertura.
    // v10: o resources.assets embutido NAO e o original traduzido —
    // o credito da Central na tela de titulo (bandeira + nome) foi
    // removido por ordem do usuario, zerando os blocos DXT5 da faixa
    // do credito na textura ui_title_logo (path_id 875), in place
    // (mesmo tamanho, 91267784 bytes). Os outros 9 arquivos sao
    // byte-identicos a traducao. O rodape de copyright foi mantido.
    // O host os le ANTES do RomFS original, sem tocar no data pack:
    // DM_ROMFS_OVERLAY=<files>/ptbr-overlay no env.txt (hle_fs.c
    // rom_overlay_path, "overlay first"). Desligar remove a linha e
    // o jogo volta ao ingles original. O estado vive em settings.txt
    // (ptbr=1/0, padrao 1); o env.txt e derivado dele em onCreate.
    private int ptbr = 1;
    private static final String[] PTBR_FILES = {
        "Data/level6", "Data/level7", "Data/level8",
        "Data/level10", "Data/level12", "Data/level13", "Data/level15",
        "Data/resources.assets",
        "Data/Managed/Metadata/global-metadata.dat",
        "Data/StreamingAssets/NX/parameter.cpk"};

    private File ptbrOverlayDir() {
        return new File(dataDir(), "ptbr-overlay");
    }

    private static class DocEntry {
        Uri uri;
        String rel;
        long size;
        boolean dir;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setImmersive();
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF050505);
        // Fundo NOVO v9: imagem limpa enviada (1210x544, Ryu com
        // katana, sem textos/paineis), cobrindo o launcher inteiro,
        // fixa, CENTER_CROP. Nada de foto mockup antiga, nada de rolagem.
        ImageView art = new ImageView(this);
        int artId = getResources().getIdentifier("launcher_art", "drawable", getPackageName());
        if (artId != 0) {
            art.setImageResource(artId);
            art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        } else {
            // Guns v2: a arte do launcher vem como asset (launcher_bg.jpg),
            // porque o mkapk empacota assets, nao drawables alem do icone.
            try {
                InputStream in = getAssets().open("launcher_bg.jpg");
                art.setImageBitmap(BitmapFactory.decodeStream(in));
                in.close();
                art.setScaleType(ImageView.ScaleType.CENTER_CROP);
            } catch (Exception ignored) {}
        }
        root.addView(art, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        // v13: SEM scrim escuro — a previa aprovada mostra a arte da
        // foto 2 limpa, com titulo e botoes pincel por cima dela.
        // Neve bem devagar SOBRE a arte e ABAIXO da coluna de botoes
        // (os dialogos sao janelas separadas, a neve nunca cobre o
        // texto deles).
        snowView = new SnowView(this);
        root.addView(snowView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // v15: largura do menu = 28% da tela (max 320dp), a partir do
        // DisplayMetrics real — nunca MATCH_PARENT. Na referencia os
        // botoes ocupam so a coluna da esquerda e a Setsuna fica 100%
        // visivel a direita. A v14 usava 45% (max 560dp); o usuario
        // achou comprido demais e aprovou a previa com 28%.
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        menuWidthPx = Math.min((int) (dm.widthPixels * 0.28f), dp(320));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        // v16: padding de topo dp(56) (era dp(8)). Um LinearLayout
        // VERTICAL ignora Gravity.CENTER_VERTICAL — a coluna empilha
        // a partir do topo, nao centraliza (a previa v15 assumiu
        // centralizacao e mentiu sobre a folga). Sem os divisores a
        // coluna tem 782px; dp(56) no topo centraliza oticamente
        // (conteudo y=147..879 de 1080, como na previa aprovada).
        // v18: topo dp(32) (era dp(56)) — com a densidade real do S23
        // (3.0, nao 2.625) o conteudo da v17 tinha 1104px > 1080px e a
        // linha de status ficava cortada na borda inferior; com dp(32)
        // o conteudo termina em y~1006 de 1080, com folga.
        col.setPadding(dp(24), dp(32), dp(24), dp(6));
        col.setGravity(Gravity.CENTER_VERTICAL);
        // v13: titulo = logo "I am Setsuna." recortado da foto 1
        // (logo_title.png, tinta preta transparente). Sem ele, volta
        // o TextView com o nome do app, como na v12.
        int logoId = getResources().getIdentifier("logo_title", "drawable", getPackageName());
        if (logoId != 0) {
            ImageView logo = new ImageView(this);
            logo.setImageResource(logoId);
            logo.setAdjustViewBounds(true);
            logo.setScaleType(ImageView.ScaleType.FIT_START);
            int logoW = (int) (getResources().getDisplayMetrics().widthPixels * 0.30);
            col.addView(logo, new LinearLayout.LayoutParams(
                    logoW, LinearLayout.LayoutParams.WRAP_CONTENT));
        } else {
            TextView title = new TextView(this);
            title.setText(String.valueOf(getApplicationInfo().loadLabel(getPackageManager())));
            title.setTextSize(TypedValue.COMPLEX_UNIT_PX, spPx(30));
            applyGameFont(title, 0.08f);
            title.setTextColor(Color.WHITE);
            title.setShadowLayer(dp(4), 0, dp(2), 0xFF000000);
            col.addView(title);
        }
        // Faixa pincel vermelha "Android Port", como na previa 1.
        TextView banner = new TextView(this);
        banner.setText("Android Port");
        banner.setTextSize(TypedValue.COMPLEX_UNIT_PX, spPx(15));
        applyGameFont(banner, 0.05f);
        banner.setTextColor(Color.WHITE);
        banner.setGravity(Gravity.CENTER);
        banner.setBackground(brushDrawable(true));
        banner.setPadding(dp(8), dp(4), dp(8), dp(4));
        // v17: altura EXPLICITA dp(40) (era WRAP_CONTENT) — mesmo
        // motivo dos botoes: o fundo pincel 1200x140 inflava a faixa
        // para 140px de altura. A tinta visivel continua ~104px.
        LinearLayout.LayoutParams bannerLp = new LinearLayout.LayoutParams(
                (int) (getResources().getDisplayMetrics().widthPixels * 0.20),
                dp(40));
        bannerLp.topMargin = dp(6);
        bannerLp.bottomMargin = dp(4);
        col.addView(banner, bannerLp);

        playBtn = makeMenuButton("Jogar", true, "ic_play", new View.OnClickListener() {
            @Override public void onClick(View v) { playGame(); }
        });
        // Pulso sutil no Jogar: brilho que vai e volta, sem asset extra.
        AlphaAnimation pulse = new AlphaAnimation(1f, 0.65f);
        pulse.setDuration(900);
        pulse.setRepeatMode(Animation.REVERSE);
        pulse.setRepeatCount(Animation.INFINITE);
        playBtn.startAnimation(pulse);
        // v14: diamantes dourados nas pontas do Jogar, como na previa 1.
        // As linhas divisoras douradas entre os botoes VOLTARAM (a v13
        // as cortou alegando falta de espaco); para caber tudo, os
        // botoes ficaram com 38dp de altura e os divisores com ~14dp.
        LinearLayout jogarRow = new LinearLayout(this);
        jogarRow.setOrientation(LinearLayout.HORIZONTAL);
        jogarRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView diaL = new TextView(this);
        diaL.setText("\u25C6");
        diaL.setTextColor(0xFFE8C45A);
        diaL.setTextSize(TypedValue.COMPLEX_UNIT_PX, spPx(13));
        jogarRow.addView(diaL);
        // v17: altura EXPLICITA dp(38) (era WRAP_CONTENT) — o botao
        // Jogar tambem tem fundo pincel; a linha jogarRow ja tem
        // altura dp(38) via menuParams(), e o botao acompanha.
        LinearLayout.LayoutParams playLp = new LinearLayout.LayoutParams(
                0, dp(38), 1f);
        playLp.leftMargin = dp(6);
        playLp.rightMargin = dp(6);
        jogarRow.addView(playBtn, playLp);
        TextView diaR = new TextView(this);
        diaR.setText("\u25C6");
        diaR.setTextColor(0xFFE8C45A);
        diaR.setTextSize(TypedValue.COMPLEX_UNIT_PX, spPx(13));
        jogarRow.addView(diaR);
        col.addView(jogarRow, menuParams());
        // v16: divisores dourados REMOVIDOS a pedido dele (print do
        // S23, 22:54: "Essas divisória remova"). Eram eles que
        // empurravam o Sobre para fora da tela: no aparelho o passo
        // botao+divisor media 194,5px (o losango 9sp inflado pelo
        // fontScale virava ~75px por divisor). Sem eles a coluna tem
        // 782px e o Sobre cabe com folga. O metodo do divisor
        // tambem foi removido — nada de codigo morto.
        // O YouTube SAIU da coluna na v14 e virou botao no canto
        // superior direito (padrao Alex Kidd v24). Importar jogo
        // segue dentro da Configuracao.
        col.addView(makeMenuButton("Configura\u00e7\u00e3o", false, "ic_gear", new View.OnClickListener() {
            @Override public void onClick(View v) { showConfigDialog(); }
        }), menuParams());
        col.addView(makeMenuButton("Controles", false, "ic_pad", new View.OnClickListener() {
            @Override public void onClick(View v) { showControlesDialog(); }
        }), menuParams());
        col.addView(makeMenuButton("Sobre", false, "ic_info", new View.OnClickListener() {
            @Override public void onClick(View v) { showSobreDialog(); }
        }), menuParams());

        statusText = new TextView(this);
        statusText.setTextSize(TypedValue.COMPLEX_UNIT_PX, spPx(12));
        statusText.setTextColor(0xFFDDDDDD);
        statusText.setShadowLayer(dp(3), 0, dp(2), 0xFF000000);
        statusText.setPadding(0, dp(4), 0, 0);
        col.addView(statusText);

        // Sem rolagem: a coluna inteira e o conteudo da tela, fixa.
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        root.addView(col, lp);
        // v16: YouTube virou PILULA tematica pequena no tema do
        // launcher — pincel preto (mesmo tom dos botoes) + badge play
        // vermelha + "@emuucheck" branco, ~150x36dp no canto superior
        // direito. O retangulo vermelho 96x64dp (o retangulo vermelho
        // da v14/v15) destoava do tema; pediu "bonito menor de acordo
        // com o tema". O clique continua abrindo
        // https://youtube.com/@emuucheck via openYouTube(), mesma
        // funcao e mesma URL da v15.
        // v18: a borda dourada fina da v16/v17 foi REMOVIDA (o stroke
        // #D4A437 lia como verde no ceu claro) — a pilula agora e so
        // o pincel preto, sem contorno.
        Button ytBtn = new Button(this);
        ytBtn.setText("@emuucheck");
        ytBtn.setTextSize(TypedValue.COMPLEX_UNIT_PX, spPx(12));
        applyGameFont(ytBtn, 0.04f);
        ytBtn.setTextColor(Color.WHITE);
        ytBtn.setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT);
        ytBtn.setMinimumHeight(dp(36));
        ytBtn.setPadding(dp(12), dp(4), dp(14), dp(4));
        ytBtn.setBackground(ytPillDrawable());
        try {
            int badgeId = getResources().getIdentifier("ic_yt_play", "drawable", getPackageName());
            if (badgeId != 0) {
                android.graphics.Bitmap bm = BitmapFactory.decodeResource(getResources(), badgeId);
                int bh = dp(20);
                int bw = (int) (bh * (float) bm.getWidth() / bm.getHeight());
                android.graphics.Bitmap scaled = android.graphics.Bitmap.createScaledBitmap(bm, bw, bh, true);
                android.graphics.drawable.BitmapDrawable d =
                        new android.graphics.drawable.BitmapDrawable(getResources(), scaled);
                ytBtn.setCompoundDrawablesWithIntrinsicBounds(d, null, null, null);
                ytBtn.setCompoundDrawablePadding(dp(8));
            }
        } catch (Exception ignored) {}
        ytBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openYouTube(); }
        });
        // v17: tamanho EXPLICITO dp(150)xdp(36) (era WRAP_CONTENT x
        // WRAP_CONTENT). Na v16 a pilula estourou para ~1200x140 no
        // S23: o fundo ytPillDrawable() carrega o pincel
        // brush_black.png (1200x140) e o Android usa o tamanho
        // intrinseco do fundo como minimo do WRAP_CONTENT. Com
        // dimensoes exatas o fundo so estica para caber, como a
        // largura dos botoes do menu ja fazia.
        FrameLayout.LayoutParams ytLp = new FrameLayout.LayoutParams(
                dp(150), dp(36),
                Gravity.TOP | Gravity.END);
        ytLp.topMargin = dp(12);
        ytLp.rightMargin = dp(12);
        root.addView(ytBtn, ytLp);
        // v21: pilula Donate — mesmo molde da pilula do YouTube (pincel
        // preto, sem contorno, tamanho EXPLICITO dp(150)xdp(36), nunca
        // WRAP_CONTENT — v16), a esquerda dela no canto superior direito
        // (rightMargin dp(174) = 12 + 150 + 12). Coracao vermelho
        // (ic_heart.png, asset limpo, sem o pontinho branco da previa)
        // + "Donate" branco. Abre o dialogo Donate via Pix.
        Button donateBtn = new Button(this);
        donateBtn.setText("Donate");
        donateBtn.setTextSize(TypedValue.COMPLEX_UNIT_PX, spPx(12));
        applyGameFont(donateBtn, 0.04f);
        donateBtn.setTextColor(Color.WHITE);
        donateBtn.setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT);
        donateBtn.setMinimumHeight(dp(36));
        donateBtn.setPadding(dp(12), dp(4), dp(14), dp(4));
        donateBtn.setBackground(ytPillDrawable());
        try {
            int heartId = getResources().getIdentifier("ic_heart", "drawable", getPackageName());
            if (heartId != 0) {
                android.graphics.Bitmap bm = BitmapFactory.decodeResource(getResources(), heartId);
                int hh = dp(22);
                int hw = (int) (hh * (float) bm.getWidth() / bm.getHeight());
                android.graphics.Bitmap scaled = android.graphics.Bitmap.createScaledBitmap(bm, hw, hh, true);
                android.graphics.drawable.BitmapDrawable d =
                        new android.graphics.drawable.BitmapDrawable(getResources(), scaled);
                donateBtn.setCompoundDrawablesWithIntrinsicBounds(d, null, null, null);
                donateBtn.setCompoundDrawablePadding(dp(8));
            }
        } catch (Exception ignored) {}
        donateBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showDonateDialog(); }
        });
        FrameLayout.LayoutParams dnLp = new FrameLayout.LayoutParams(
                dp(150), dp(36),
                Gravity.TOP | Gravity.END);
        dnLp.topMargin = dp(12);
        dnLp.rightMargin = dp(174);
        root.addView(donateBtn, dnLp);
        setContentView(root);
        loadSettings();
        loadDriverChoice();
        refreshPlay();
        ensureBundledDriver();
        ensureBundledShaders();
        ensureBundledTranslation();
        savePtbrOverlay();
        // v10: sincronizar o env.txt com a Taxa de quadros SEMPRE,
        // sem depender de um toque no botao. A v8 deixava o env.txt
        // so com DM_FPS_TARGET=60 (sem a linha DM_PRESENT_INTERVAL)
        // e o handheld ficava a 30 fps mesmo com 60 selecionado
        // (log_10: "interval mode game"). loadFpsTarget() le o valor
        // herdado do arquivo; saveFpsTarget() reescreve as duas
        // linhas preservando o resto (driver, overlay). Idempotente.
        loadFpsTarget();
        saveFpsTarget();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (snowView != null) snowView.setRunning(true);
        loadSettings();
        loadDriverChoice();
        refreshPlay();
    }

    @Override
    protected void onPause() {
        if (snowView != null) snowView.setRunning(false);
        super.onPause();
    }

    // v13: canal do YouTube do usuario (padrao Dark Souls v10).
    private void openYouTube() {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://youtube.com/@emuucheck"));
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "Nao foi possivel abrir o YouTube.", Toast.LENGTH_SHORT).show();
        }
    }

    // v15: largura fixa = 28% da tela (menuWidthPx, calculado em
    // onCreate), nunca MATCH_PARENT. Na v14 era 45%; na v13 era
    // MATCH_PARENT e os botoes cobriam a Setsuna.
    // v17: altura EXPLICITA dp(38) (era WRAP_CONTENT). O fundo pincel
    // (brush_*.png, 1200x140) inflava a altura real dos botoes para
    // 140px — so ~102px de tinta visivel + ~19px transparente em
    // cima/embaixo de cada um — e o passo medido no S23 era 152px,
    // nao ~111px; foi isso que cortou o Sobre na v16. Com altura
    // exata a tinta visivel continua a mesma (~100px); some so o
    // padding invisivel. O setMinimumHeight(dp(34)) do botao vira
    // piso, nao teto. Regra: nenhuma view da tela principal com
    // fundo pincel pode ter dimensao WRAP_CONTENT.
    private LinearLayout.LayoutParams menuParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                menuWidthPx, dp(38));
        lp.topMargin = dp(2);
        lp.bottomMargin = dp(2);
        return lp;
    }

    // Linha de 2 botoes lado a lado, mesma tonalidade escura da foto.
    private LinearLayout buttonRow(Button a, Button b) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.rightMargin = dp(4);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        rp.leftMargin = dp(4);
        row.addView(a, lp);
        row.addView(b, rp);
        return row;
    }

    // v13: fundo pincel (brush_red.png / brush_black.png, gerados por
    // gen_assets_v13.py com a mesma logica da previa aprovada). Sem o
    // drawable, cai no GradientDrawable solido da mesma cor.
    private android.graphics.drawable.Drawable brushDrawable(boolean primary) {
        int id = getResources().getIdentifier(primary ? "brush_red" : "brush_black", "drawable", getPackageName());
        if (id != 0) {
            try {
                return getResources().getDrawable(id);
            } catch (Exception ignored) {}
        }
        GradientDrawable d = new GradientDrawable();
        d.setColor(primary ? 0xFFA81616 : 0xFF1A1A1C);
        d.setCornerRadius(dp(12));
        return d;
    }

    // v16: fundo da pilula do YouTube — pincel preto (mesmo tom dos
    // botoes do menu) + borda dourada fina, raio de pilula (metade da
    // altura 36dp). LayerDrawable porque o pincel e um bitmap e o
    // contorno e um shape: o stroke fica por cima do pincel.
    // v18: SEM contorno — o stroke dourado (#D4A437) lia como verde
    // no ceu claro e o usuario mandou tirar. A pilula passa a ser so
    // o fundo pincel preto, como os botoes do menu (que tambem nao
    // tem borda). Nada de LayerDrawable com stroke no caminho da pilula.
    private android.graphics.drawable.Drawable ytPillDrawable() {
        return brushDrawable(false);
    }

    private void setButtonIcon(Button b, String iconName) {
        try {
            int id = getResources().getIdentifier(iconName, "drawable", getPackageName());
            if (id == 0) return;
            android.graphics.Bitmap bm = BitmapFactory.decodeResource(getResources(), id);
            if (bm == null) return;
            int h = dp(28);
            int w = (int) (h * (float) bm.getWidth() / bm.getHeight());
            android.graphics.Bitmap scaled = android.graphics.Bitmap.createScaledBitmap(bm, w, h, true);
            android.graphics.drawable.BitmapDrawable d =
                    new android.graphics.drawable.BitmapDrawable(getResources(), scaled);
            b.setCompoundDrawablesWithIntrinsicBounds(d, null, null, null);
            b.setCompoundDrawablePadding(dp(12));
        } catch (Exception ignored) {}
    }

    private Button makeMenuButton(String text, boolean primary) {
        return makeMenuButton(text, primary, null, null);
    }

    private Button makeMenuButton(String text, boolean primary, View.OnClickListener l) {
        return makeMenuButton(text, primary, null, l);
    }

    private Button makeMenuButton(String text, boolean primary, String iconName, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(TypedValue.COMPLEX_UNIT_PX, spPx(13));
        applyGameFont(b, 0.04f);
        b.setTextColor(Color.WHITE);
        // v14: texto alinhado A ESQUERDA (icone + gap + texto), como na
        // referencia. A v13 deixava o padrao do Button (centralizado).
        // v15: altura 34dp (era 38dp) e texto 13sp (era 16sp), para os
        // botoes ficarem bem menores como o usuario pediu e aprovado
        // na previa v15.
        // v16: texto em PX (spPx), imune ao fontScale do aparelho —
        // causa raiz do Sobre cortado na v15; divisores removidos.
        b.setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT);
        b.setMinimumHeight(dp(34));
        b.setPadding(dp(14), dp(6), dp(10), dp(6));
        b.setBackground(brushDrawable(primary));
        if (iconName != null) setButtonIcon(b, iconName);
        if (l != null) b.setOnClickListener(l);
        return b;
    }

    private void refreshPlay() {
        boolean ok = hasRomFs() && hasShaders();
        if (playBtn != null) {
            playBtn.setEnabled(true);
            playBtn.setAlpha(ok ? 1f : 0.55f);
            playBtn.setText(ok ? "Jogar" : "Jogar (sem dados)");
        }
        if (statusText != null) {
            statusText.setText(ok ? "Dados prontos: " + shaderText()
                    : "Sem dados: toque em Jogar ou em Selecionar pasta para importar.");
        }
    }

    private void playGame() {
        if (artLocked) return;
        if (hasRomFs() && hasShaders()) {
            // Antes de Jogar, garantir o estado do driver: se Turnip
            // esta escolhido mas o .so sumiu, tentar extrair de novo;
            // se ainda faltar, voltar sozinho ao Sistema (nunca travar).
            // E SEMPRE remover DM_FSR / DM_RENDER_SCALE do env.txt, para
            // o host com FSR nunca ligar FSR/escala por acidente.
            File so = driverSoFile();
            if (!"system".equals(driverChoice) && (so == null || !so.isFile())) {
                driverChoice = "system";
                writeEnvDriver();
                Toast.makeText(this, "Driver indisponivel: voltando ao driver do Sistema.", Toast.LENGTH_SHORT).show();
            }
            sanitizeEnv();
            // v10: garantir o env da Taxa no ato de Jogar, mesmo que
            // o usuario nunca abra a Configuracao (o fpsTarget em
            // memoria so e carregado la). Sem isto, o 60 herdado da
            // v8 nao destravava o handheld (log_10).
            loadFpsTarget();
            saveFpsTarget();
            Intent i = new Intent();
            i.setClassName(getPackageName(), "android.app.NativeActivity");
            startActivity(i);
        } else {
            showImportDialog();
        }
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

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) setImmersive();
    }

    private int dp(int v) {
        float d = getResources().getDisplayMetrics().density;
        return (int) (v * d + 0.5f);
    }

    // v16: tamanho de texto em PX (sp * density), IMUNE ao fontScale
    // do aparelho. Causa raiz do Sobre cortado na v15: o texto SP no
    // S23 dele renderiza ~2x maior que o padrao (losango 9sp do
    // divisor virou ~75px, botoes 13sp viraram ~104px de altura) e a
    // previa assumia fontScale=1. Com PX, previa == aparelho.
    private float spPx(float sp) {
        return sp * getResources().getDisplayMetrics().density;
    }

    private GradientDrawable dialogBg() {
        GradientDrawable bg = new GradientDrawable();
        // v20: neve MEIO TRANSLUCIDA (alfa 225/255): a arte do fundo
        // aparece levemente atraves do painel (previa v20c, aprovada).
        bg.setColor(0xE1F4F1EA);
        bg.setCornerRadius(dp(20));
        bg.setStroke(dp(2), 0xFFA81616);  // v13: borda vermelha
        return bg;
    }

    private void styleDialogButton(Button b) {
        styleDialogButton(b, false);
    }

    // v13: botoes de dialogo em pincel preto; acao primaria
    // (Fechar / Exportar log) em pincel vermelho.
    private void styleDialogButton(Button b, boolean primary) {
        b.setBackground(brushDrawable(primary));
        b.setTextColor(Color.WHITE);
        applyGameFont(b, 0.04f);
        b.setTextSize(15);
        b.setMinimumHeight(dp(44));
        b.setPadding(dp(10), dp(6), dp(10), dp(6));
    }

    private LinearLayout.LayoutParams spacedParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        lp.bottomMargin = dp(6);
        return lp;
    }

    // v19: submenus DIVIDIDOS e na COR CLARA do launcher (previa
    // previa-v19-proposta.png, aprovada 09/11 ~23:28). As linhas de
    // opcao deixam de ser botoes pincel pretos e viram linhas brancas
    // com texto escuro, divididas por linhas finas e agrupadas em
    // secoes com cabecalho vermelho. NENHUM comportamento muda:
    // os listeners sao os mesmos de antes, so a apresentacao muda.
    private LinearLayout.LayoutParams rowParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44));
        lp.topMargin = dp(2);
        lp.bottomMargin = dp(2);
        return lp;
    }

    private TextView sectionHeader(String title) {
        TextView t = new TextView(this);
        t.setText(title.toUpperCase(java.util.Locale.ROOT));
        t.setTextColor(0xFFA81616);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, spPx(11));
        t.setPadding(dp(16), dp(10), dp(16), dp(2));
        return t;
    }

    // Atualiza o valor de uma linha clara (o TextView do valor fica
    // no tag da linha). "ON..." fica vermelho, o resto escuro.
    private void updateRowValue(LinearLayout row, String value) {
        TextView v = (TextView) row.getTag();
        v.setText(value);
        v.setTextColor(value.startsWith("ON") ? 0xFFA81616 : 0xFF1A1A1A);
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(0xFFD8D2C4);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        lp.leftMargin = dp(16);
        lp.rightMargin = dp(16);
        v.setLayoutParams(lp);
        return v;
    }

    // v20: dialogos estilo Eevee (cardDialog do LetsGoLauncherActivity,
    // v0101): Dialog comum, janela TRANSPARENTE, painel neve como o
    // conteudo. NADA de AlertDialog.Builder — o chrome padrao do
    // sistema (titulo branco, painel de conteudo preto, barra de
    // botoes cinza) era a causa raiz do painel PRETO da v19 no S23:
    // dialogBg() ia para o fundo da JANELA, mas o layout do AlertDialog
    // pintava o proprio fundo escuro por cima; e o estilo do titulo
    // (findViewById(android.R.id.title)) retornava null no Android
    // moderno e era pulado em silencio. Aqui tudo (faixa de titulo,
    // Voltar, linhas, nota, botoes) e view do conteudo, entao o que
    // a previa mostra e o que o aparelho desenha.
    private android.app.Dialog cardDialog(View content) {
        final android.app.Dialog d = new android.app.Dialog(this);
        d.setContentView(content);
        android.view.Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            w.setLayout(Math.round(dm.widthPixels * 0.92f), android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        return d;
    }

    // Faixa de titulo em pincel vermelho (a assinatura do launcher,
    // como a faixa da v19) com o titulo em branco serifado centralizado.
    // Altura EXPLICITA dp(40): fundo pincel NUNCA pode ter dimensao
    // WRAP_CONTENT (v16: o PNG 1200x140 vira o tamanho da view).
    private TextView dialogTitleBar(String title) {
        TextView t = new TextView(this);
        t.setText(title);
        t.setBackground(brushDrawable(true));
        t.setTextColor(Color.WHITE);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(8), dp(6), dp(8), dp(6));
        applyGameFont(t, 0.02f);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, spPx(16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(40));
        t.setLayoutParams(lp);
        return t;
    }

    // Casca de um painel de dialogo: fundo dialogBg() (neve
    // translucida), faixa de titulo e, salvo o progresso, o "‹ Voltar"
    // em vermelho logo abaixo da faixa (como a previa do Eevee). O
    // Voltar dispensa ESTE dialogo e revela o pai por baixo (a pilha
    // de dialogos e o mecanismo de navegacao aninhada); na raiz, volta
    // a tela principal. dlgHolder resolve a referencia circular
    // (o listener e ligado antes do Dialog existir).
    private LinearLayout dialogShell(String title, final android.app.Dialog[] dlgHolder, boolean withBack) {
        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setBackground(dialogBg());
        shell.setPadding(dp(16), dp(12), dp(16), dp(12));
        shell.addView(dialogTitleBar(title));
        if (withBack) {
            TextView back = new TextView(this);
            back.setText("\u2039 Voltar");
            back.setTextColor(0xFFA81616);
            back.setTypeface(Typeface.DEFAULT_BOLD);
            back.setTextSize(TypedValue.COMPLEX_UNIT_PX, spPx(12));
            back.setPadding(dp(4), dp(10), dp(4), dp(6));
            back.setClickable(true);
            back.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { if (dlgHolder[0] != null) dlgHolder[0].dismiss(); }
            });
            shell.addView(back);
        }
        return shell;
    }

    // Monta um painel completo (casca + corpo rolavel), cria o Dialog,
    // mostra e devolve. O corpo rola se for mais alto que a tela; a
    // faixa de titulo e o Voltar ficam FIXOS fora do ScrollView.
    private android.app.Dialog showPanel(String title, LinearLayout body) {
        final android.app.Dialog[] dlg = new android.app.Dialog[1];
        LinearLayout shell = dialogShell(title, dlg, true);
        ScrollView sv = new ScrollView(this);
        sv.addView(body);
        shell.addView(sv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        dlg[0] = cardDialog(shell);
        dlg[0].show();
        return dlg[0];
    }

    // v20: linha de CATEGORIA (submenuButton do Eevee): titulo negrito
    // escuro + subtitulo cinza pequeno + "›" vermelho a direita, texto
    // direto no painel (sem cartao branco), separada por divisor fino.
    private LinearLayout catRow(String title, String sub, View.OnClickListener l) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(0xFF1A1A1A);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, spPx(13));
        col.addView(t, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        TextView s = new TextView(this);
        s.setText(sub);
        s.setTextColor(0xFF6E645F);
        s.setTextSize(TypedValue.COMPLEX_UNIT_PX, spPx(10));
        col.addView(s, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        row.addView(col, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView ch = new TextView(this);
        ch.setText("\u203a");
        ch.setTextColor(0xFFA81616);
        ch.setTypeface(Typeface.DEFAULT_BOLD);
        ch.setTextSize(TypedValue.COMPLEX_UNIT_PX, spPx(16));
        row.addView(ch);
        row.setPadding(dp(16), dp(6), dp(16), dp(6));
        row.setClickable(true);
        row.setOnClickListener(l);
        return row;
    }

    // v20: base de linha de opcao no painel (texto direto, sem cartao
    // branco — o estilo do Eevee). Label escura a esquerda, espaco
    // flexivel, controle a direita.
    private LinearLayout plainRowBase(String label) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), 0, dp(16), 0);
        TextView lab = new TextView(this);
        lab.setText(label);
        lab.setTextColor(0xFF1A1A1A);
        lab.setTextSize(TypedValue.COMPLEX_UNIT_PX, spPx(12));
        row.addView(lab, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    // Linha de opcao com valor a direita em vermelho + "›" (seletor:
    // o toque alterna o valor, como na v19). value == null mostra so
    // o "›" (navegacao). O TextView do valor fica no tag da linha.
    private LinearLayout plainValueRow(String label, String value, View.OnClickListener l) {
        LinearLayout row = plainRowBase(label);
        TextView val = new TextView(this);
        if (value == null) {
            val.setText("\u203a");
        } else {
            val.setText(value + "  \u203a");
        }
        val.setTypeface(Typeface.DEFAULT_BOLD);
        val.setTextColor(0xFFA81616);
        val.setTextSize(TypedValue.COMPLEX_UNIT_PX, spPx(12));
        row.addView(val);
        row.setTag(val);
        row.setClickable(true);
        row.setOnClickListener(l);
        return row;
    }

    // Atualiza o valor de uma linha plainValueRow (sempre vermelho,
    // como na previa v20c).
    private void updatePlainValue(LinearLayout row, String value) {
        if (row == null) return;
        TextView v = (TextView) row.getTag();
        v.setText(value + "  \u203a");
        v.setTextColor(0xFFA81616);
    }

    // v20: interruptor DESENHADO (trilho + bolinha branca): vermelho
    // ligado, cinza desligado. NAO usar CheckBox nativo: ele renderiza
    // conforme o tema do sistema (a mesma causa raiz do painel preto
    // da v19). A bolinha fica no tag do trilho; setSwitch redesenha.
    private FrameLayout switchView(boolean on) {
        FrameLayout sw = new FrameLayout(this);
        View knob = new View(this);
        GradientDrawable k = new GradientDrawable();
        k.setColor(0xFFFFFFFF);
        k.setShape(GradientDrawable.OVAL);
        knob.setBackground(k);
        int sz = dp(22);
        FrameLayout.LayoutParams klp = new FrameLayout.LayoutParams(sz, sz);
        klp.gravity = Gravity.CENTER_VERTICAL | (on ? Gravity.RIGHT : Gravity.LEFT);
        klp.leftMargin = dp(3);
        klp.rightMargin = dp(3);
        sw.addView(knob, klp);
        sw.setTag(knob);
        setSwitch(sw, on);
        LinearLayout.LayoutParams swLp = new LinearLayout.LayoutParams(dp(52), dp(28));
        sw.setLayoutParams(swLp);
        return sw;
    }

    private void setSwitch(FrameLayout sw, boolean on) {
        GradientDrawable track = new GradientDrawable();
        track.setColor(on ? 0xFFA81616 : 0xFFC9C9C9);
        track.setCornerRadius(dp(28));
        sw.setBackground(track);
        View knob = (View) sw.getTag();
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) knob.getLayoutParams();
        lp.gravity = Gravity.CENTER_VERTICAL | (on ? Gravity.RIGHT : Gravity.LEFT);
        knob.setLayoutParams(lp);
    }

    // Linha de opcao com interruptor a direita. O tag da linha e o
    // trilho (updateSwitch redesenha).
    private LinearLayout switchRow(String label, boolean on, View.OnClickListener l) {
        LinearLayout row = plainRowBase(label);
        FrameLayout sw = switchView(on);
        row.addView(sw);
        row.setTag(sw);
        row.setClickable(true);
        row.setOnClickListener(l);
        return row;
    }

    private void updateSwitch(LinearLayout row, boolean on) {
        if (row == null) return;
        setSwitch((FrameLayout) row.getTag(), on);
    }

    // v20: linha da Opacidade no estilo plano — mesma linha clara da
    // v19 (lightStepperRow), mas sem o cartao branco: valor + botoes
    // −/+ claros a direita, mesmo comportamento (−5/+5, clamp 20–80).
    private LinearLayout plainStepperRow(String label, String initialValue,
            View.OnClickListener downL, View.OnClickListener upL) {
        LinearLayout row = plainRowBase(label);
        TextView val = new TextView(this);
        val.setText(initialValue);
        val.setTypeface(Typeface.DEFAULT_BOLD);
        val.setTextColor(0xFF1A1A1A);
        val.setTextSize(TypedValue.COMPLEX_UNIT_PX, spPx(12));
        row.addView(val);
        row.setTag(val);
        Button down = new Button(this);
        down.setText("\u2212");
        down.setOnClickListener(downL);
        Button up = new Button(this);
        up.setText("+");
        up.setOnClickListener(upL);
        for (Button b : new Button[]{down, up}) {
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xFFFFFFFF);
            bg.setCornerRadius(dp(8));
            bg.setStroke(dp(1), 0xFFD8D2C4);
            b.setBackground(bg);
            b.setTextColor(0xFF1A1A1A);
            b.setTypeface(Typeface.DEFAULT_BOLD);
            b.setTextSize(TypedValue.COMPLEX_UNIT_PX, spPx(12));
            b.setMinimumHeight(dp(28));
            b.setPadding(dp(12), 0, dp(12), 0);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, dp(28));
            lp.leftMargin = dp(8);
            row.addView(b, lp);
        }
        return row;
    }

    // v13: neve caindo bem devagar sobre o fundo do launcher.
    // 38 flocos (raio 2-5 px, queda 10-18 px/s: atravessar a tela
    // leva ~60-90 s) + 6 petalas vermelhas mais lentas, com leve
    // deriva lateral. Tick de 50 ms via Handler; so anima com a
    // activity visivel (setRunning em onResume/onPause).
    private static class SnowView extends View {
        private static class Flake {
            float x, y, r, speed, drift, phase;
            int alpha;
            boolean petal;
        }
        private final java.util.ArrayList<Flake> flakes = new java.util.ArrayList<Flake>();
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private boolean running = false;
        private long lastTick = 0;
        private final Runnable tick = new Runnable() {
            @Override public void run() {
                if (!running) return;
                long now = android.os.SystemClock.uptimeMillis();
                float dt = lastTick == 0 ? 0.05f : Math.min(0.2f, (now - lastTick) / 1000f);
                lastTick = now;
                for (Flake f : flakes) {
                    f.y += f.speed * dt;
                    f.phase += dt;
                    f.x += (float) Math.sin(f.phase) * f.drift * dt;
                    if (f.y > getHeight() + 10) {
                        f.y = -10;
                        f.x = (float) (Math.random() * Math.max(1, getWidth()));
                    }
                    if (f.x < -10) f.x = getWidth() + 10;
                    if (f.x > getWidth() + 10) f.x = -10;
                }
                invalidate();
                handler.postDelayed(tick, 50);
            }
        };

        public SnowView(android.content.Context ctx) {
            super(ctx);
        }

        public void setRunning(boolean run) {
            if (run == running) return;
            running = run;
            if (run) {
                lastTick = 0;
                handler.post(tick);
            } else {
                handler.removeCallbacks(tick);
            }
        }

        @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            if (!flakes.isEmpty() || w <= 0 || h <= 0) return;
            java.util.Random rnd = new java.util.Random(13);
            for (int i = 0; i < 38; i++) {
                Flake f = new Flake();
                f.x = rnd.nextFloat() * w;
                f.y = rnd.nextFloat() * h;
                f.r = 2 + rnd.nextFloat() * 3;
                f.speed = 10 + rnd.nextFloat() * 8;
                f.drift = 4 + rnd.nextFloat() * 6;
                f.phase = rnd.nextFloat() * 6.28f;
                f.alpha = 130 + rnd.nextInt(90);
                f.petal = false;
                flakes.add(f);
            }
            for (int i = 0; i < 6; i++) {
                Flake f = new Flake();
                f.x = rnd.nextFloat() * w;
                f.y = rnd.nextFloat() * h;
                f.r = 4 + rnd.nextFloat() * 3;
                f.speed = 9 + rnd.nextFloat() * 6;
                f.drift = 6 + rnd.nextFloat() * 6;
                f.phase = rnd.nextFloat() * 6.28f;
                f.alpha = 200;
                f.petal = true;
                flakes.add(f);
            }
        }

        @Override protected void onDraw(android.graphics.Canvas c) {
            super.onDraw(c);
            for (Flake f : flakes) {
                if (f.petal) {
                    paint.setColor(android.graphics.Color.argb(f.alpha, 178, 34, 34));
                    c.drawOval(new android.graphics.RectF(f.x - f.r, f.y - f.r * 0.5f,
                            f.x + f.r, f.y + f.r * 0.5f), paint);
                } else {
                    paint.setColor(android.graphics.Color.argb(f.alpha, 255, 255, 255));
                    c.drawCircle(f.x, f.y, f.r, paint);
                }
            }
        }
    }

    private File dataDir() {
        File ext = getExternalFilesDir(null);
        return ext != null ? ext : getFilesDir();
    }

    private boolean hasRomFs() {
        return new File(dataDir(), "RomFS").isDirectory();
    }

    private boolean hasShaders() {
        return new File(dataDir(), "shaders.dmsv").isFile();
    }

    private boolean hasEnv() {
        return new File(dataDir(), "env.txt").isFile();
    }

    private String shaderText() {
        File f = new File(dataDir(), "shaders.dmsv");
        if (!f.isFile()) return "ausente";
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            byte[] h = new byte[12];
            if (in.read(h) != 12) return "ileg\u00edvel";
            if (h[0] != 'D' || h[1] != 'M' || h[2] != 'S' || h[3] != 'V') return "formato desconhecido";
            int count = (h[8] & 0xFF) | ((h[9] & 0xFF) << 8)
                    | ((h[10] & 0xFF) << 16) | ((h[11] & 0xFF) << 24);
            return count + " shaders (" + (f.length() / 1024 / 1024) + " MB)";
        } catch (Exception e) {
            return "ileg\u00edvel";
        } finally {
            try { if (in != null) in.close(); } catch (Exception ignored) {}
        }
    }

    // Guns, Gore and Cannoli 2, pacote deste port: na primeira execucao,
    // sem settings.txt, semear o HUD padrao do jogo. So para este pacote:
    // o launcher e generico e nao pode impor o HUD de um jogo a outro.
    // hidden= esconde dpad/ls/rs/minus (o host os ignora no toque e no
    // desenho, nunca no editor, e os reescreve ao salvar); ui= da ao
    // botao y 1,5x de tamanho, sem mover nada (x/y = -1).
    // O host preserva ambas as linhas ao salvar, entao a semente sobrevive
    // a primeira execucao do jogo.
    // v11 (ordem do usuario: Controles originais, sem nada forçado):
    // os defaults da v10, e so eles. hidden= esconde APENAS
    // dpad/ls/rs/minus, como na v10; stick_r fica VISIVEL no jogo e a
    // mira por arrasto fica DESLIGADA por padrao (touchaim=0), como na
    // v10. Nada aqui esconde o analogico direito nem liga o arrasto a
    // forca: esconder botao e escolha do usuario, na secao
    // "Esconder botoes" de Controles (setControlHidden), nunca semente.
    // As sementes da v5 (touchaim=1) e da v7 (hidden stick_r) foram
    // removidas nesta versao; o ui= y 1,5x da v10 foi mantido.
    private void seedGunsDefaults() {
        if (!"org.nx2a.t01006d4003bce000".equals(getPackageName())) return;
        File dest = new File(dataDir(), "settings.txt");
        String[] hiddenLines = {"hidden=phone,dpad", "hidden=phone,ls", "hidden=phone,rs", "hidden=phone,minus",
                "hidden=tablet,dpad", "hidden=tablet,ls", "hidden=tablet,rs", "hidden=tablet,minus"};
        if (dest.isFile()) {
            try {
                BufferedReader r = new BufferedReader(new FileReader(dest));
                String line;
                java.util.HashSet<String> have = new java.util.HashSet<String>();
                while ((line = r.readLine()) != null) {
                    if (line.startsWith("hidden=")) have.add(line.trim());
                }
                r.close();
                StringBuilder add = new StringBuilder();
                for (String h : hiddenLines) if (!have.contains(h)) add.append(h).append("\n");
                if (add.length() > 0) {
                    FileOutputStream out = new FileOutputStream(dest, true);
                    out.write(add.toString().getBytes("UTF-8"));
                    out.close();
                }
            } catch (Exception ignored) {}
            return;
        }
        try {
            dataDir().mkdirs();
            FileOutputStream out = new FileOutputStream(dest);
            String[] lines = {"overlay=1", "rumble=1", "docked=0", "fps=1", "opacity=60", "touchaim=0",
                    "hidden=phone,dpad", "hidden=phone,ls", "hidden=phone,rs", "hidden=phone,minus",
                    "hidden=tablet,dpad", "hidden=tablet,ls", "hidden=tablet,rs", "hidden=tablet,minus",
                    "ui=phone,y,-1,-1,1500", "ui=tablet,y,-1,-1,1500"};
            for (String l : lines) out.write((l + "\n").getBytes("UTF-8"));
            out.close();
        } catch (Exception ignored) {}
    }

    private void loadSettings() {
        setDocked = 0; setFps = 1; setOpacity = 60; setOverlay = 1; setRumble = 1; setTouchaim = 0;
        ptbr = 1;
        File f = new File(dataDir(), "settings.txt");
        if (!f.isFile()) { seedGunsDefaults(); return; }
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader(f));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("docked=")) setDocked = parseInt(line.substring(7), setDocked);
                else if (line.startsWith("fps=")) setFps = parseInt(line.substring(4), setFps);
                else if (line.startsWith("opacity=")) setOpacity = parseInt(line.substring(8), setOpacity);
                else if (line.startsWith("overlay=")) setOverlay = parseInt(line.substring(8), setOverlay);
                else if (line.startsWith("rumble=")) setRumble = parseInt(line.substring(7), setRumble);
                else if (line.startsWith("touchaim=")) setTouchaim = parseInt(line.substring(9), setTouchaim);
                else if (line.startsWith("ptbr=")) ptbr = parseInt(line.substring(5), ptbr);
            }
        } catch (Exception ignored) {
        } finally {
            try { if (r != null) r.close(); } catch (Exception ignored) {}
        }
        if (setOpacity < 20) setOpacity = 20;
        if (setOpacity > 80) setOpacity = 80;
    }

    private int parseInt(String s, int fallback) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return fallback; }
    }


    // ---- Drivers de GPU (Turnip via adrenotools) ----
    // Driver vive em getFilesDir()/gpu_drivers/<slug>/, NUNCA no
    // armazenamento externo: o adrenotools recusa driver externo
    // (foi o bug da v3 antiga). O env.txt do jogo recebe SOMENTE
    // DM_VK_DRIVER com o caminho ABSOLUTO interno. Sem Turnip
    // escolhido, a chave nao existe e o host usa o driver do Sistema
    // (comportamento identico a v7). O host com adrenotools, se o
    // Turnip falhar, volta sozinho ao Sistema e loga o fallback.
    private File driversDir() {
        return new File(getFilesDir(), "gpu_drivers");
    }

    private String driverLabel() {
        if ("imported".equals(driverChoice) && !importedSlug.isEmpty()) return "Importado: " + importedSlug;
        return "Sistema";
    }

    private File driverSoFile() {
        if ("imported".equals(driverChoice) && !importedSlug.isEmpty() && !importedSoName.isEmpty())
            return new File(new File(driversDir(), importedSlug), importedSoName);
        return null;
    }

    private java.util.List<String[]> listImportedDrivers() {
        java.util.List<String[]> out = new ArrayList<String[]>();
        File root = driversDir();
        File[] dirs = root.isDirectory() ? root.listFiles() : null;
        if (dirs == null) return out;
        java.util.Arrays.sort(dirs);
        for (File d : dirs) {
            if (!d.isDirectory()) continue;
            File[] files = d.listFiles();
            if (files == null) continue;
            for (File f : files) {
                if (f.isFile() && f.getName().endsWith(".so")) {
                    out.add(new String[]{d.getName(), f.getName()});
                    break;
                }
            }
        }
        return out;
    }

    private void loadDriverChoice() {
        driverChoice = "system";
        File env = new File(dataDir(), "env.txt");
        if (!env.isFile()) return;
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader(env));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("DM_VK_DRIVER=")) {
                    String v = line.substring("DM_VK_DRIVER=".length()).trim();
                    if (v.contains("/gpu_drivers/")) {
                        int idx = v.indexOf("/gpu_drivers/") + "/gpu_drivers/".length();
                        String rest = v.substring(idx);
                        int slash = rest.indexOf('/');
                        if (slash > 0) {
                            driverChoice = "imported";
                            importedSlug = rest.substring(0, slash);
                            importedSoName = rest.substring(slash + 1);
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        } finally {
            try { if (r != null) r.close(); } catch (Exception ignored) {}
        }
    }

    // Escreve (ou remove) DM_VK_DRIVER no env.txt, preservando as
    // outras linhas (inclusive DM_FPS_TARGET). NUNCA escreve DM_FSR,
    // DM_RENDER_SCALE ou DM_VK_HOOK_DIR.
    private void writeEnvDriver() {
        File env = new File(dataDir(), "env.txt");
        List<String> lines = new ArrayList<String>();
        if (env.isFile()) {
            BufferedReader r = null;
            try {
                r = new BufferedReader(new FileReader(env));
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.startsWith("DM_VK_DRIVER=")) continue;
                    lines.add(line);
                }
            } catch (Exception ignored) {
            } finally {
                try { if (r != null) r.close(); } catch (Exception ignored) {}
            }
        }
        File so = driverSoFile();
        if (!"system".equals(driverChoice) && so != null && so.isFile()) {
            lines.add("DM_VK_DRIVER=" + so.getAbsolutePath());
        }
        File tmp = new File(dataDir(), "env.txt.tmp");
        try {
            FileOutputStream out = new FileOutputStream(tmp);
            for (String l : lines) out.write((l + "\n").getBytes("UTF-8"));
            out.close();
            if (env.exists() && !env.delete()) throw new java.io.IOException("nao foi possivel substituir env.txt");
            if (!tmp.renameTo(env)) throw new java.io.IOException("falha ao finalizar env.txt");
        } catch (Exception e) {
            Toast.makeText(this, "Falha ao salvar driver: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void loadFpsTarget() {
        fpsTarget = 30;
        File env = new File(dataDir(), "env.txt");
        if (!env.isFile()) return;
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader(env));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("DM_FPS_TARGET=")) {
                    String v = line.substring("DM_FPS_TARGET=".length()).trim();
                    if ("30".equals(v)) fpsTarget = 30;
                    else if ("60".equals(v)) fpsTarget = 60;
                }
            }
        } catch (Exception ignored) {
        } finally {
            try { if (r != null) r.close(); } catch (Exception ignored) {}
        }
    }

    // Taxa de quadros: DM_FPS_TARGET (para o log / frame step, que
    // este jogo nao tem) E DM_PRESENT_INTERVAL (o que destrava de
    // verdade: a 60 o host ignora o interval 2 do jogo e apresenta
    // 1 frame por tick; a 30 devolve o comportamento original).
    // Preserva as outras linhas do env.txt (inclusive a overlay da
    // traducao e DM_VK_DRIVER).
    private void saveFpsTarget() {
        File env = new File(dataDir(), "env.txt");
        List<String> lines = new ArrayList<String>();
        if (env.isFile()) {
            BufferedReader r = null;
            try {
                r = new BufferedReader(new FileReader(env));
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.startsWith("DM_FPS_TARGET=") || line.startsWith("DM_PRESENT_INTERVAL=")) continue;
                    lines.add(line);
                }
            } catch (Exception ignored) {
            } finally {
                try { if (r != null) r.close(); } catch (Exception ignored) {}
            }
        }
        lines.add("DM_FPS_TARGET=" + fpsTarget);
        lines.add("DM_PRESENT_INTERVAL=" + (fpsTarget == 60 ? "display" : "game"));
        File tmp = new File(dataDir(), "env.txt.tmp");
        try {
            dataDir().mkdirs();
            FileOutputStream out = new FileOutputStream(tmp);
            for (String l : lines) out.write((l + "\n").getBytes("UTF-8"));
            out.close();
            if (env.exists() && !env.delete()) throw new java.io.IOException("nao foi possivel substituir env.txt");
            if (!tmp.renameTo(env)) throw new java.io.IOException("falha ao finalizar env.txt");
        } catch (Exception e) {
            Toast.makeText(this, "Falha ao salvar taxa de quadros: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    // Traducao PT-BR: escreve (ou remove) DM_ROMFS_OVERLAY no
    // env.txt conforme o ptbr= de settings.txt, preservando as
    // outras linhas. Sem a linha, o host le so o RomFS original.
    private void savePtbrOverlay() {
        File env = new File(dataDir(), "env.txt");
        List<String> lines = new ArrayList<String>();
        if (env.isFile()) {
            BufferedReader r = null;
            try {
                r = new BufferedReader(new FileReader(env));
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.startsWith("DM_ROMFS_OVERLAY=")) continue;
                    lines.add(line);
                }
            } catch (Exception ignored) {
            } finally {
                try { if (r != null) r.close(); } catch (Exception ignored) {}
            }
        }
        if (ptbr != 0) lines.add("DM_ROMFS_OVERLAY=" + ptbrOverlayDir().getAbsolutePath());
        File tmp = new File(dataDir(), "env.txt.tmp");
        try {
            dataDir().mkdirs();
            FileOutputStream out = new FileOutputStream(tmp);
            for (String l : lines) out.write((l + "\n").getBytes("UTF-8"));
            out.close();
            if (env.exists() && !env.delete()) throw new java.io.IOException("nao foi possivel substituir env.txt");
            if (!tmp.renameTo(env)) throw new java.io.IOException("falha ao finalizar env.txt");
        } catch (Exception e) {
            Toast.makeText(this, "Falha ao salvar traducao: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private int ptbrReadyCount() {
        int n = 0;
        File root = ptbrOverlayDir();
        for (String rel : PTBR_FILES) if (new File(root, rel).isFile()) n++;
        return n;
    }

    // Remove DM_FSR / DM_RENDER_SCALE / DM_VK_HOOK_DIR do env.txt,
    // preservando o resto (inclusive DM_VK_DRIVER). Chamado antes de
    // todo Jogar: FSR e escala ficam desligados mesmo se alguem
    // editou o env.txt a mao.
    private void sanitizeEnv() {
        File env = new File(dataDir(), "env.txt");
        if (!env.isFile()) { writeEnvDriver(); return; }
        List<String> lines = new ArrayList<String>();
        boolean changed = false;
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader(env));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("DM_FSR=") || line.startsWith("DM_RENDER_SCALE=")
                        || line.startsWith("DM_VK_HOOK_DIR=")) { changed = true; continue; }
                lines.add(line);
            }
        } catch (Exception ignored) {
            return;
        } finally {
            try { if (r != null) r.close(); } catch (Exception ignored) {}
        }
        if (!changed) return;
        File tmp = new File(dataDir(), "env.txt.tmp");
        try {
            FileOutputStream out = new FileOutputStream(tmp);
            for (String l : lines) out.write((l + "\n").getBytes("UTF-8"));
            out.close();
            if (env.exists() && !env.delete()) return;
            tmp.renameTo(env);
        } catch (Exception ignored) {}
    }

    private void showDriversDialog() {
        loadDriverChoice();
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView cur = new TextView(this);
        cur.setTextSize(14);
        cur.setTextColor(DIALOG_TEXT);
        cur.setText("Driver atual: " + driverLabel());
        col.addView(cur, spacedParams());
        // Importacao NO TOPO (v10): impossivel de nao achar, sem limite
        // de drivers mostrados. Scroll liberado so aqui e em Configuracao.
        // v20: linhas plainValueRow (texto direto no painel, sem cartao
        // branco); o Voltar volta ao submenu Jogo, por baixo.
        col.addView(sectionHeader("Importar"));
        col.addView(plainValueRow("Importar driver (.so)", null, new View.OnClickListener() {
            @Override public void onClick(View v) { pickDriverSo(); }
        }), rowParams());
        col.addView(divider());
        col.addView(plainValueRow("Importar driver (.zip)", null, new View.OnClickListener() {
            @Override public void onClick(View v) { pickDriverZip(); }
        }), rowParams());
        col.addView(divider());
        col.addView(sectionHeader("Usar"));
        col.addView(plainValueRow("Sistema (driver do celular)", null, new View.OnClickListener() {
            @Override public void onClick(View v) {
                driverChoice = "system";
                writeEnvDriver();
                Toast.makeText(LauncherActivity.this, "Driver: Sistema", Toast.LENGTH_SHORT).show();
                if (configDriversRow != null) updatePlainValue(configDriversRow, "Sistema");
                refreshPlay();
            }
        }), rowParams());
        col.addView(divider());
        java.util.List<String[]> imported = listImportedDrivers();
        for (final String[] d : imported) {
            col.addView(plainValueRow("Usar importado: " + d[0], null, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    driverChoice = "imported";
                    importedSlug = d[0];
                    importedSoName = d[1];
                    writeEnvDriver();
                    Toast.makeText(LauncherActivity.this, "Driver: " + d[0], Toast.LENGTH_SHORT).show();
                    if (configDriversRow != null) updatePlainValue(configDriversRow, "Importado: " + d[0]);
                    refreshPlay();
                }
            }), rowParams());
            col.addView(divider());
        }
        TextView note = new TextView(this);
        note.setTextSize(12);
        note.setTextColor(DIALOG_TEXT);
        note.setText("Importado vai para gpu_drivers/<nome>/ na pasta interna. Se nao carregar, o host volta ao Sistema. Aceita qualquer .so; nome fora do padrao libvulkan_*.so gera aviso, mas importa mesmo assim.");
        col.addView(note);
        showPanel("Drivers de GPU", col);
    }

    private void pickDriverSo() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, REQ_PICK_DRIVER_SO);
    }

    private void pickDriverZip() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/zip");
        startActivityForResult(i, REQ_PICK_DRIVER_ZIP);
    }

    private String driverFileName(Uri uri) {
        String name = null;
        android.database.Cursor c = null;
        try {
            c = getContentResolver().query(uri, new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (c != null && c.moveToFirst()) name = c.getString(0);
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        if (name == null || name.isEmpty()) {
            String path = uri.getLastPathSegment();
            name = path != null ? path : "driver.so";
        }
        return name;
    }

    private boolean looksLikeVulkanDriver(String name) {
        return name != null && name.endsWith(".so") && name.contains("vulkan");
    }

    private void importDriverSo(Uri uri) {
        String name = driverFileName(uri);
        if (name.contains("..") || name.contains("/")) {
            Toast.makeText(this, "Nome de arquivo invalido.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!name.endsWith(".so")) {
            Toast.makeText(this, "Falha ao importar driver: o arquivo nao e .so (" + name + "). Escolha um arquivo .so ou um zip com .so dentro.", Toast.LENGTH_LONG).show();
            return;
        }
        if (!name.contains("vulkan")) {
            Toast.makeText(this, "Aviso: nome fora do padrao libvulkan_*.so, importado mesmo assim.", Toast.LENGTH_LONG).show();
        }
        String slug = name.replace(".so", "");
        if (slug.startsWith("lib")) slug = slug.substring(3);
        if (slug.isEmpty()) slug = "importado";
        try {
            File dir = new File(driversDir(), slug);
            dir.mkdirs();
            File dest = new File(dir, name);
            InputStream in = getContentResolver().openInputStream(uri);
            if (in == null) throw new java.io.IOException("nao foi possivel abrir o arquivo");
            File tmp = new File(dir, name + ".tmp");
            FileOutputStream out = new FileOutputStream(tmp);
            byte[] buf = new byte[262144];
            int n;
            while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
            in.close();
            out.close();
            if (dest.exists() && !dest.delete()) throw new java.io.IOException("nao foi possivel substituir o driver");
            if (!tmp.renameTo(dest)) throw new java.io.IOException("falha ao finalizar o driver");
            driverChoice = "imported";
            importedSlug = slug;
            importedSoName = name;
            writeEnvDriver();
            Toast.makeText(this, "Driver importado: " + slug + ". Toque em Jogar.", Toast.LENGTH_SHORT).show();
            if (configDriversRow != null) updatePlainValue(configDriversRow, "Importado: " + slug);
            refreshPlay();
        } catch (Exception e) {
            Toast.makeText(this, "Falha ao importar driver: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void importDriverZip(Uri uri) {
        String zipName = driverFileName(uri);
        String slug = zipName.replace(".zip", "").replace(".ZIP", "");
        if (slug.isEmpty() || slug.contains("..") || slug.contains("/")) slug = "importado";
        try {
            InputStream raw = getContentResolver().openInputStream(uri);
            if (raw == null) throw new java.io.IOException("nao foi possivel abrir o zip");
            ZipInputStream zin = new ZipInputStream(raw);
            java.util.List<String> soNames = new java.util.ArrayList<String>();
            java.util.List<byte[]> soBytes = new java.util.ArrayList<byte[]>();
            ZipEntry ze;
            while ((ze = zin.getNextEntry()) != null) {
                String name = ze.getName();
                if (!ze.isDirectory() && name.endsWith(".so") && !name.contains("..")) {
                    java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[262144];
                    int n;
                    while ((n = zin.read(buf)) >= 0) baos.write(buf, 0, n);
                    int slash = name.lastIndexOf('/');
                    soNames.add(slash >= 0 ? name.substring(slash + 1) : name);
                    soBytes.add(baos.toByteArray());
                }
                zin.closeEntry();
            }
            zin.close();
            if (soNames.isEmpty()) {
                Toast.makeText(this, "Falha ao importar driver: zip sem nenhum arquivo .so dentro.", Toast.LENGTH_LONG).show();
                return;
            }
            int chosen = 0;
            for (int i = 0; i < soNames.size(); i++) {
                if (soNames.get(i).contains("vulkan")) { chosen = i; break; }
            }
            String foundName = soNames.get(chosen);
            byte[] found = soBytes.get(chosen);
            if (!foundName.contains("vulkan")) {
                Toast.makeText(this, "Aviso: nenhum libvulkan_*.so no zip; importado o primeiro .so (" + foundName + ") mesmo assim.", Toast.LENGTH_LONG).show();
            }
            File dir = new File(driversDir(), slug);
            dir.mkdirs();
            File dest = new File(dir, foundName);
            File tmp = new File(dir, foundName + ".tmp");
            FileOutputStream out = new FileOutputStream(tmp);
            out.write(found);
            out.close();
            if (dest.exists() && !dest.delete()) throw new java.io.IOException("nao foi possivel substituir o driver");
            if (!tmp.renameTo(dest)) throw new java.io.IOException("falha ao finalizar o driver");
            driverChoice = "imported";
            importedSlug = slug;
            importedSoName = foundName;
            writeEnvDriver();
            Toast.makeText(this, "Driver importado: " + slug + ". Toque em Jogar.", Toast.LENGTH_SHORT).show();
            if (configDriversRow != null) updatePlainValue(configDriversRow, "Importado: " + slug);
            refreshPlay();
        } catch (Exception e) {
            Toast.makeText(this, "Falha ao importar driver: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void saveSettings() {
        File dest = new File(dataDir(), "settings.txt");
        List<String> lines = new ArrayList<String>();
        if (dest.isFile()) {
            BufferedReader r = null;
            try {
                r = new BufferedReader(new FileReader(dest));
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.startsWith("docked=") || line.startsWith("fps=")
                            || line.startsWith("opacity=") || line.startsWith("overlay=")
                            || line.startsWith("rumble=") || line.startsWith("touchaim=")
                            || line.startsWith("ptbr=")) continue;
                    lines.add(line);
                }
            } catch (Exception ignored) {
            } finally {
                try { if (r != null) r.close(); } catch (Exception ignored) {}
            }
        }
        lines.add(0, "opacity=" + setOpacity);
        lines.add(0, "fps=" + setFps);
        lines.add(0, "docked=" + setDocked);
        lines.add(0, "rumble=" + setRumble);
        lines.add(0, "touchaim=" + setTouchaim);
        lines.add(0, "overlay=" + setOverlay);
        lines.add(0, "ptbr=" + ptbr);
        File tmp = new File(dataDir(), "settings.txt.tmp");
        try {
            FileOutputStream out = new FileOutputStream(tmp);
            for (String l : lines) out.write((l + "\n").getBytes("UTF-8"));
            out.close();
            if (dest.exists() && !dest.delete()) throw new java.io.IOException("nao foi possivel substituir settings.txt");
            if (!tmp.renameTo(dest)) throw new java.io.IOException("falha ao finalizar settings.txt");
        } catch (Exception e) {
            postStatus("Falha ao salvar configuracoes: " + e.getMessage());
        }
        refreshPlay();
    }

    // v20: Configuracao vira um menu de CATEGORIAS aninhado, como o
    // buildSettings do Let's Go Eevee (v0013): tocar numa categoria
    // abre o proprio submenu como um NOVO dialogo POR CIMA deste
    // (a raiz nao e dispensada); o Voltar do submenu revela a raiz.
    // A nota longa da v19 sai: o conteudo dela agora e autoevidente;
    // so a dica do fps fica, no submenu Video.
    private void showConfigDialog() {
        loadSettings();
        loadFpsTarget();
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(catRow("Jogo", "Importar jogo, drivers de GPU", new View.OnClickListener() {
            @Override public void onClick(View v) { showJogoSubmenu(); }
        }), rowParams());
        col.addView(divider());
        col.addView(catRow("V\u00eddeo", "Modo, taxa de quadros, mostrar FPS", new View.OnClickListener() {
            @Override public void onClick(View v) { showVideoSubmenu(); }
        }), rowParams());
        col.addView(divider());
        col.addView(catRow("Tradu\u00e7\u00e3o", "Tradu\u00e7\u00e3o PT-BR", new View.OnClickListener() {
            @Override public void onClick(View v) { showTraducaoSubmenu(); }
        }), rowParams());
        col.addView(divider());
        col.addView(catRow("Controle", "Gamepad na tela, vibra\u00e7\u00e3o, opacidade", new View.OnClickListener() {
            @Override public void onClick(View v) { showControleSubmenu(); }
        }), rowParams());
        col.addView(divider());
        col.addView(catRow("Controles", "Editar layout dos bot\u00f5es", new View.OnClickListener() {
            @Override public void onClick(View v) { showControlesDialog(); }
        }), rowParams());
        col.addView(divider());
        col.addView(catRow("Sobre", "Vers\u00e3o, shaders, exportar log", new View.OnClickListener() {
            @Override public void onClick(View v) { showSobreDialog(); }
        }), rowParams());
        col.addView(divider());
        TextView hint = new TextView(this);
        hint.setText("Toca num item para abrir o submenu dele.");
        hint.setTextSize(TypedValue.COMPLEX_UNIT_PX, spPx(10));
        hint.setTextColor(0xFF6E645F);
        hint.setPadding(dp(16), dp(10), dp(16), dp(4));
        col.addView(hint);
        showPanel("Configura\u00e7\u00e3o", col);
    }

    private void showJogoSubmenu() {
        loadDriverChoice();
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        // v13: Importar jogo saiu da tela principal e virou linha da
        // Configuracao (previa 2); na v20 mora aqui, no submenu Jogo.
        col.addView(plainValueRow("Importar jogo", null, new View.OnClickListener() {
            @Override public void onClick(View v) { showImportDialog(); }
        }), rowParams());
        col.addView(divider());
        configDriversRow = plainValueRow("Drivers de GPU", driverLabel(), new View.OnClickListener() {
            @Override public void onClick(View v) { showDriversDialog(); }
        });
        col.addView(configDriversRow, rowParams());
        col.addView(divider());
        showPanel("Jogo", col);
    }

    private void showVideoSubmenu() {
        loadSettings();
        loadFpsTarget();
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        final LinearLayout[] modeRow = new LinearLayout[1];
        modeRow[0] = plainValueRow("Modo", setDocked == 0 ? "Handheld" : "Docked", new View.OnClickListener() {
            @Override public void onClick(View v) {
                setDocked = setDocked == 0 ? 1 : 0;
                saveSettings();
                updatePlainValue(modeRow[0], setDocked == 0 ? "Handheld" : "Docked");
            }
        });
        col.addView(modeRow[0], rowParams());
        col.addView(divider());
        final LinearLayout[] frameRateRow = new LinearLayout[1];
        frameRateRow[0] = plainValueRow("Taxa de quadros", fpsTarget + " fps", new View.OnClickListener() {
            @Override public void onClick(View v) {
                fpsTarget = fpsTarget == 30 ? 60 : 30;
                saveFpsTarget();
                updatePlainValue(frameRateRow[0], fpsTarget + " fps");
                Toast.makeText(LauncherActivity.this, "Taxa de quadros: " + fpsTarget + " fps (vale na proxima abertura do jogo)", Toast.LENGTH_SHORT).show();
            }
        });
        col.addView(frameRateRow[0], rowParams());
        col.addView(divider());
        final LinearLayout[] fpsRow = new LinearLayout[1];
        fpsRow[0] = switchRow("Mostrar FPS", setFps != 0, new View.OnClickListener() {
            @Override public void onClick(View v) {
                setFps = setFps == 0 ? 1 : 0;
                saveSettings();
                updateSwitch(fpsRow[0], setFps != 0);
            }
        });
        col.addView(fpsRow[0], rowParams());
        col.addView(divider());
        TextView hint = new TextView(this);
        hint.setText("A taxa de quadros vale na pr\u00f3xima abertura do jogo.");
        hint.setTextSize(TypedValue.COMPLEX_UNIT_PX, spPx(10));
        hint.setTextColor(0xFF6E645F);
        hint.setPadding(dp(16), dp(10), dp(16), dp(4));
        col.addView(hint);
        showPanel("V\u00eddeo", col);
    }

    private void showTraducaoSubmenu() {
        loadSettings();
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        final LinearLayout[] ptbrRow = new LinearLayout[1];
        ptbrRow[0] = switchRow("Tradu\u00e7\u00e3o PT-BR", ptbr != 0, new View.OnClickListener() {
            @Override public void onClick(View v) {
                ptbr = ptbr == 0 ? 1 : 0;
                saveSettings();
                savePtbrOverlay();
                if (ptbr != 0) ensureBundledTranslation();
                updateSwitch(ptbrRow[0], ptbr != 0);
                Toast.makeText(LauncherActivity.this, "Tradu\u00e7\u00e3o PT-BR: " + (ptbr == 0 ? "OFF (ingles original)" : "ON") + " (vale na proxima abertura do jogo)", Toast.LENGTH_SHORT).show();
            }
        });
        col.addView(ptbrRow[0], rowParams());
        col.addView(divider());
        TextView info = new TextView(this);
        info.setText(ptbrReadyCount() + "/10 arquivos prontos na overlay (sem tocar no data pack).");
        info.setTextSize(TypedValue.COMPLEX_UNIT_PX, spPx(10));
        info.setTextColor(0xFF6E645F);
        info.setPadding(dp(16), dp(10), dp(16), dp(4));
        col.addView(info);
        showPanel("Tradu\u00e7\u00e3o", col);
    }

    private void showControleSubmenu() {
        loadSettings();
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        final LinearLayout[] overlayRow = new LinearLayout[1];
        overlayRow[0] = switchRow("Gamepad na tela", setOverlay != 0, new View.OnClickListener() {
            @Override public void onClick(View v) {
                setOverlay = setOverlay == 0 ? 1 : 0;
                saveSettings();
                updateSwitch(overlayRow[0], setOverlay != 0);
            }
        });
        col.addView(overlayRow[0], rowParams());
        col.addView(divider());
        final LinearLayout[] rumbleRow = new LinearLayout[1];
        rumbleRow[0] = switchRow("Vibra\u00e7\u00e3o", setRumble != 0, new View.OnClickListener() {
            @Override public void onClick(View v) {
                setRumble = setRumble == 0 ? 1 : 0;
                saveSettings();
                updateSwitch(rumbleRow[0], setRumble != 0);
            }
        });
        col.addView(rumbleRow[0], rowParams());
        col.addView(divider());
        final LinearLayout[] opRow = new LinearLayout[1];
        opRow[0] = plainStepperRow("Opacidade do gamepad", setOpacity + "%", new View.OnClickListener() {
            @Override public void onClick(View v) {
                setOpacity = Math.max(20, setOpacity - 5);
                saveSettings();
                updateRowValue(opRow[0], setOpacity + "%");
            }
        }, new View.OnClickListener() {
            @Override public void onClick(View v) {
                setOpacity = Math.min(80, setOpacity + 5);
                saveSettings();
                updateRowValue(opRow[0], setOpacity + "%");
            }
        });
        col.addView(opRow[0], rowParams());
        col.addView(divider());
        showPanel("Controle", col);
    }

    // v11: "Esconder botoes" (ordem do usuario, Controles originais
    // intactos). O host ja entende hidden=<layout>,<controle>:
    // settings_load o le, ctl_at o ignora no toque e o desenho o pula
    // fora do editor, mas o editor de layout continua mostrando o
    // controle escondido, entao nada aqui trava o usuario para fora.
    // O launcher so edita as linhas hidden= de settings.txt, nos dois
    // layouts, sem tocar em nenhuma outra linha e sem duplicar.
    // O botao de menu do host NAO entra na lista: esconde-lo poderia
    // tirar do usuario o caminho para as configuracoes.
    private static final String[] HIDEABLE_CONTROLS = {"stick_l", "stick_r", "dpad",
            "a", "b", "x", "y", "l", "r", "zl", "zr", "plus", "minus", "ls", "rs"};

    private String hideLabel(String ctl) {
        if ("stick_l".equals(ctl)) return "Analogico esquerdo (mover)";
        if ("stick_r".equals(ctl)) return "Analogico direito (mira)";
        if ("dpad".equals(ctl)) return "Direcional (D-pad)";
        if ("a".equals(ctl)) return "Botao A";
        if ("b".equals(ctl)) return "Botao B";
        if ("x".equals(ctl)) return "Botao X";
        if ("y".equals(ctl)) return "Botao Y";
        if ("l".equals(ctl)) return "Botao L";
        if ("r".equals(ctl)) return "Botao R";
        if ("zl".equals(ctl)) return "Botao ZL";
        if ("zr".equals(ctl)) return "Botao ZR";
        if ("plus".equals(ctl)) return "Botao + (pausa)";
        if ("minus".equals(ctl)) return "Botao -";
        if ("ls".equals(ctl)) return "Botao L3";
        if ("rs".equals(ctl)) return "Botao R3";
        return ctl;
    }

    private java.util.HashSet<String> hiddenControls() {
        java.util.HashSet<String> hidden = new java.util.HashSet<String>();
        File f = new File(dataDir(), "settings.txt");
        if (!f.isFile()) return hidden;
        try {
            BufferedReader r = new BufferedReader(new FileReader(f));
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("hidden=phone,")) hidden.add(line.substring(13));
                else if (line.startsWith("hidden=tablet,")) hidden.add(line.substring(14));
            }
            r.close();
        } catch (Exception ignored) {}
        return hidden;
    }

    private void setControlHidden(String ctl, boolean hide) {
        File dest = new File(dataDir(), "settings.txt");
        List<String> lines = new ArrayList<String>();
        if (dest.isFile()) {
            try {
                BufferedReader r = new BufferedReader(new FileReader(dest));
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.equals("hidden=phone," + ctl) || line.equals("hidden=tablet," + ctl)) continue;
                    lines.add(line);
                }
                r.close();
            } catch (Exception ignored) {}
        }
        if (hide) {
            lines.add("hidden=phone," + ctl);
            lines.add("hidden=tablet," + ctl);
        }
        File tmp = new File(dataDir(), "settings.txt.tmp");
        try {
            dataDir().mkdirs();
            FileOutputStream out = new FileOutputStream(tmp);
            for (String l : lines) out.write((l + "\n").getBytes("UTF-8"));
            out.close();
            if (dest.exists() && !dest.delete()) throw new java.io.IOException("nao foi possivel substituir settings.txt");
            if (!tmp.renameTo(dest)) throw new java.io.IOException("falha ao finalizar settings.txt");
        } catch (Exception e) {
            postStatus("Falha ao salvar esconder botao: " + e.getMessage());
        }
    }

    private void showControlesDialog() {
        loadSettings();
        String text = "Controles no jogo: o gamepad na tela e o mapeamento seguem o tema original do port.\n"
                + "Mirar arrastando na tela: com ela ligada, o analogico direito some do jogo (continua no editor) "
                + "e arrastar num espaco livre da metade direita da tela mira na direcao do arraste; soltar o dedo "
                + "para a mira. Atirar e pular continuam em dedos separados.\n"
                + "Editar layout: no jogo, pausa > Gamepad.\n"
                + "O launcher nao edita o layout direto.";
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(13);
        tv.setTextColor(DIALOG_TEXT);
        col.addView(tv);
        // v20: interruptor desenhado (liga/desliga) no lugar do
        // texto ON/OFF da v19; mesmo comportamento e mesmo save.
        final LinearLayout[] touchaimBox = new LinearLayout[1];
        touchaimBox[0] = switchRow("Mirar arrastando na tela", setTouchaim != 0, new View.OnClickListener() {
            @Override public void onClick(View v) {
                setTouchaim = setTouchaim == 0 ? 1 : 0;
                saveSettings();
                updateSwitch(touchaimBox[0], setTouchaim != 0);
            }
        });
        col.addView(touchaimBox[0], rowParams());
        col.addView(divider());
        // v11: Esconder botoes. Estado inicial = o da v10: dpad/ls/rs/
        // minus escondidos, todo o resto visivel. Tocar alterna o
        // estado e grava na hora; o botao de menu nao aparece aqui.
        col.addView(sectionHeader("Esconder bot\u00f5es"));
        TextView hideNote = new TextView(this);
        hideNote.setText("Botao escondido some do jogo, mas continua visivel no editor de layout (pausa > Gamepad), onde ele pode voltar. Nada aqui muda os Controles originais.");
        hideNote.setTextSize(13);
        hideNote.setTextColor(DIALOG_TEXT);
        col.addView(hideNote);
        final java.util.HashSet<String> hiddenNow = hiddenControls();
        for (final String ctl : HIDEABLE_CONTROLS) {
            final LinearLayout[] hideBox = new LinearLayout[1];
            hideBox[0] = switchRow(hideLabel(ctl), hiddenNow.contains(ctl), new View.OnClickListener() {
                @Override public void onClick(View v) {
                    boolean nowHidden = !hiddenControls().contains(ctl);
                    setControlHidden(ctl, nowHidden);
                    updateSwitch(hideBox[0], nowHidden);
                }
            });
            col.addView(hideBox[0], rowParams());
            col.addView(divider());
        }
        LinearLayout playRow = plainValueRow("Editar controles no jogo", null, new View.OnClickListener() {
            @Override public void onClick(View v) { playGame(); }
        });
        if (!hasRomFs() || !hasShaders()) {
            playRow.setEnabled(false);
            playRow.setAlpha(0.5f);
        }
        col.addView(playRow, rowParams());
        col.addView(divider());
        showPanel("Controles", col);
    }

    private void showSobreDialog() {
        final android.app.Dialog[] sobreDlg = new android.app.Dialog[1];
        String ver = "desconhecida";
        int code = -1;
        try {
            android.content.pm.PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            ver = pi.versionName;
            code = pi.versionCode;
        } catch (Exception ignored) {}
        // v11: o log fica DENTRO do Sobre, e o launcher CRIA o
        // log.txt se ele nao existir, em vez de so dizer "ausente".
        File log = new File(dataDir(), "log.txt");
        String logTail;
        try {
            dataDir().mkdirs();
            if (!log.isFile()) {
                new FileOutputStream(log, true).close();
                logTail = "(log criado agora, vazio - rode o jogo uma vez para gerar o log.)";
            } else {
                java.util.List<String> lines = new ArrayList<String>();
                BufferedReader r = new BufferedReader(new FileReader(log));
                String line;
                while ((line = r.readLine()) != null) lines.add(line);
                r.close();
                if (lines.isEmpty()) {
                    logTail = "(log vazio - rode o jogo uma vez para gerar o log.)";
                } else {
                    int start = Math.max(0, lines.size() - 30);
                    StringBuilder sb = new StringBuilder();
                    for (int i = start; i < lines.size(); i++) sb.append(lines.get(i)).append("\n");
                    logTail = sb.toString();
                }
            }
        } catch (Exception e) {
            logTail = "Nao foi possivel ler o log: " + e.getMessage();
        }
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView info = new TextView(this);
        info.setTextSize(13);
        info.setTextColor(DIALOG_TEXT);
        info.setText(String.valueOf(getApplicationInfo().loadLabel(getPackageManager())) + " - Android Port\n"
                + "Versao: " + ver + " (versionCode " + code + ")\n"
                + "RomFS: " + (hasRomFs() ? "presente" : "ausente")
                + "  \u2022  shaders: " + shaderText() + "\n"
                + "Driver: " + driverLabel() + "\n"
                + "Tradu\u00e7\u00e3o PT-BR: " + (ptbr == 0 ? "OFF" : "ON (" + ptbrReadyCount() + "/10 arquivos)") + "\n"
                + "Host with custom GPU driver support (DM_VK_DRIVER).\n"
                + "nx2android port, personal use, own dump.");
        col.addView(info);
        // Setsuna v1: Exportar log no TOPO do Sobre (padrao SteamWorld v15):
        // o log mora na pasta privada do app, que o gerenciador nao deixa
        // abrir; o botao copia para Downloads (publico) e abre o compartilhar.
        Button expBtn = new Button(this);
        expBtn.setText("Exportar log");
        styleDialogButton(expBtn, true);  // v13: acao primaria, pincel vermelho
        expBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (sobreDlg[0] != null) sobreDlg[0].dismiss();
                exportLog();
            }
        });
        LinearLayout.LayoutParams expLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        expLp.topMargin = dp(10);
        expLp.bottomMargin = dp(6);
        col.addView(expBtn, expLp);
        TextView logTitle = new TextView(this);
        logTitle.setTextSize(13);
        logTitle.setTextColor(0xFFA81616);  // v13: vermelho sobre o painel neve
        logTitle.setText("Log (ultimas 30 linhas):");
        col.addView(logTitle);
        TextView logTv = new TextView(this);
        logTv.setText(logTail);
        logTv.setTextSize(10);
        logTv.setTextColor(DIALOG_TEXT);
        // Altura fixa, sem ScrollView: o que nao couber e truncado
        // na exibicao (o arquivo completo continua na pasta do jogo).
        LinearLayout.LayoutParams logLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(180));
        col.addView(logTv, logLp);
        // v20: "Exportar log" continua em pincel vermelho no topo;
        // o Voltar fecha (o Sobre e um submenu da Configuracao).
        sobreDlg[0] = showPanel("Sobre", col);
    }

    // v21: copiar para a area de transferencia (doacao via Pix).
    private void copyPixToClipboard(String label, String text, String toastMsg) {
        try {
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText(label, text));
            Toast.makeText(this, toastMsg, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Nao foi possivel copiar.", Toast.LENGTH_SHORT).show();
        }
    }

    // v21: dialogo Donate via Pix (previa v21 aprovada 17/11: "Pode
    // buildar assim"). QR do payload EXATO do banco do usuario (ver
    // PIX_PAYLOAD — NAO regenerar), nome, chave, copia-e-cola e os
    // botoes de copiar. Mesmo molde v20 (cardDialog/showPanel, painel
    // neve alfa 225, faixa pincel, Voltar); nada de AlertDialog.
    // O corpo rola se for mais alto que a tela (faixa e Voltar fixos).
    private void showDonateDialog() {
        final android.app.Dialog[] dlg = new android.app.Dialog[1];
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        // QR em cartao branco, centralizado (o PNG e o QR do payload
        // exato; se o asset faltar, o dialogo segue so com o texto).
        try {
            InputStream qin = getAssets().open("donate_pix_qr.png");
            android.graphics.Bitmap qrBm = BitmapFactory.decodeStream(qin);
            qin.close();
            if (qrBm != null) {
                LinearLayout qrCard = new LinearLayout(this);
                qrCard.setOrientation(LinearLayout.VERTICAL);
                qrCard.setGravity(Gravity.CENTER);
                GradientDrawable cardBg = new GradientDrawable();
                cardBg.setColor(Color.WHITE);
                cardBg.setCornerRadius(dp(8));
                qrCard.setBackground(cardBg);
                qrCard.setPadding(dp(10), dp(10), dp(10), dp(10));
                ImageView qrIv = new ImageView(this);
                qrIv.setImageBitmap(qrBm);
                qrCard.addView(qrIv, new LinearLayout.LayoutParams(dp(140), dp(140)));
                LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                cardLp.gravity = Gravity.CENTER_HORIZONTAL;
                cardLp.topMargin = dp(4);
                cardLp.bottomMargin = dp(4);
                col.addView(qrCard, cardLp);
            }
        } catch (Exception ignored) {}
        TextView nameTv = new TextView(this);
        nameTv.setText("SEU NOME AQUI");
        nameTv.setTextColor(DIALOG_TEXT);
        nameTv.setTypeface(Typeface.DEFAULT_BOLD);
        nameTv.setTextSize(TypedValue.COMPLEX_UNIT_PX, spPx(13));
        nameTv.setGravity(Gravity.CENTER);
        col.addView(nameTv);
        TextView keyLab = new TextView(this);
        keyLab.setText("Chave Pix");
        keyLab.setTextColor(0xFF6E645F);
        keyLab.setTextSize(TypedValue.COMPLEX_UNIT_PX, spPx(10));
        keyLab.setGravity(Gravity.CENTER);
        keyLab.setPadding(0, dp(8), 0, 0);
        col.addView(keyLab);
        TextView keyTv = new TextView(this);
        keyTv.setText(PIX_KEY);
        keyTv.setTextColor(DIALOG_TEXT);
        keyTv.setTextSize(TypedValue.COMPLEX_UNIT_PX, spPx(11));
        keyTv.setGravity(Gravity.CENTER);
        col.addView(keyTv);
        TextView copyLab = new TextView(this);
        copyLab.setText("Copia e cola");
        copyLab.setTextColor(0xFF6E645F);
        copyLab.setTextSize(TypedValue.COMPLEX_UNIT_PX, spPx(10));
        copyLab.setGravity(Gravity.CENTER);
        copyLab.setPadding(0, dp(8), 0, 0);
        col.addView(copyLab);
        TextView payloadTv = new TextView(this);
        payloadTv.setText(PIX_PAYLOAD);
        payloadTv.setTextColor(DIALOG_TEXT);
        payloadTv.setTextSize(TypedValue.COMPLEX_UNIT_PX, spPx(9));
        payloadTv.setGravity(Gravity.CENTER);
        GradientDrawable payBg = new GradientDrawable();
        payBg.setColor(0xFFE8E4DC);
        payBg.setCornerRadius(dp(6));
        payloadTv.setBackground(payBg);
        payloadTv.setPadding(dp(8), dp(8), dp(8), dp(8));
        LinearLayout.LayoutParams payLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        payLp.topMargin = dp(4);
        col.addView(payloadTv, payLp);
        // Botoes de copiar, lado a lado: fundo branco, texto vermelho.
        LinearLayout copyRow = new LinearLayout(this);
        copyRow.setOrientation(LinearLayout.HORIZONTAL);
        GradientDrawable copyBg = new GradientDrawable();
        copyBg.setColor(Color.WHITE);
        copyBg.setCornerRadius(dp(18));
        copyBg.setStroke(dp(1), 0xFFBEB9B0);
        Button copyCodeBtn = new Button(this);
        copyCodeBtn.setText("Copiar código");
        copyCodeBtn.setTextColor(0xFFA81616);
        copyCodeBtn.setTypeface(Typeface.DEFAULT_BOLD);
        copyCodeBtn.setTextSize(TypedValue.COMPLEX_UNIT_PX, spPx(12));
        copyCodeBtn.setBackground(copyBg);
        copyCodeBtn.setMinimumHeight(dp(36));
        copyCodeBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                copyPixToClipboard("Pix copia e cola", PIX_PAYLOAD, "Código Pix copiado");
            }
        });
        LinearLayout.LayoutParams cbLp = new LinearLayout.LayoutParams(
                0, dp(36), 1f);
        cbLp.rightMargin = dp(6);
        copyRow.addView(copyCodeBtn, cbLp);
        Button copyKeyBtn = new Button(this);
        copyKeyBtn.setText("Copiar chave");
        copyKeyBtn.setTextColor(0xFFA81616);
        copyKeyBtn.setTypeface(Typeface.DEFAULT_BOLD);
        copyKeyBtn.setTextSize(TypedValue.COMPLEX_UNIT_PX, spPx(12));
        copyKeyBtn.setBackground(copyBg);
        copyKeyBtn.setMinimumHeight(dp(36));
        copyKeyBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                copyPixToClipboard("Chave Pix", PIX_KEY, "Chave Pix copiada");
            }
        });
        LinearLayout.LayoutParams kbLp = new LinearLayout.LayoutParams(
                0, dp(36), 1f);
        kbLp.leftMargin = dp(6);
        copyRow.addView(copyKeyBtn, kbLp);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = dp(10);
        col.addView(copyRow, rowLp);
        Button fecharBtn = new Button(this);
        fecharBtn.setText("Fechar");
        styleDialogButton(fecharBtn, true);
        fecharBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { if (dlg[0] != null) dlg[0].dismiss(); }
        });
        LinearLayout.LayoutParams fLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        fLp.topMargin = dp(10);
        col.addView(fecharBtn, fLp);
        dlg[0] = showPanel("Donate via Pix", col);
    }

    private void exportLog() {
        File log = new File(dataDir(), "log.txt");
        if (!log.isFile() || log.length() == 0) {
            Toast.makeText(this, "Sem log ainda: abre o jogo uma vez primeiro.", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Downloads.DISPLAY_NAME, "IAmSetsuna-log.txt");
            cv.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
            cv.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) throw new java.io.IOException("nao foi possivel criar o arquivo em Downloads");
            OutputStream out = getContentResolver().openOutputStream(uri);
            if (out == null) throw new java.io.IOException("nao foi possivel escrever em Downloads");
            InputStream in = new FileInputStream(log);
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
            in.close();
            out.close();
            Toast.makeText(this, "Log exportado para Downloads/IAmSetsuna-log.txt", Toast.LENGTH_LONG).show();
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("text/plain");
            share.putExtra(Intent.EXTRA_STREAM, uri);
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(share, "Enviar log"));
        } catch (Exception e) {
            Toast.makeText(this, "Falha ao exportar log: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // Setsuna v1: Turnip embutido (padrao Heaven Dust v14): na primeira
    // execucao extrai assets/turnip-8g2.zip para gpu_drivers/turnip-8g2/;
    // depois aparece em Configuracao > Drivers de GPU como "Usar importado".
    private void ensureBundledDriver() {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    File dir = new File(driversDir(), "turnip-8g2");
                    if (new File(dir, "libvulkan_freedreno.so").isFile()) return;
                    InputStream in = getAssets().open("turnip-8g2.zip");
                    extractDriverZip(in, "turnip-8g2");
                    in.close();
                } catch (Exception ignored) {}
            }
        }).start();
    }

    // Setsuna v5+v11+v12: bundle 866 embutido (assets/shaders.dmsv, 706 do
    // split + 16 capturadas no S23 (v4+v9) + 144 novas extraidas do
    // StreamingAssets/NX/assets.cpk na v12 — o CPK guarda a forma 2001 B
    // da control section que o splitter (so arquivos soltos) nunca viu):
    // na abertura, se o files/shaders.dmsv instalado nao for o bundle
    // 866 (contagem no header DMSV, o mesmo parse de shaderText()),
    // copia o embutido por cima (via .tmp + rename, como writeFile).
    // Sem reimportar o data pack (1,4 GB); o host le files/shaders.dmsv
    // a cada boot (front_android.c: DM_VK_SHADERS). Nunca rebaixa um
    // bundle mais novo: contagem maior que a embutida fica como esta.
    // A copia roda em thread de fundo (como ensureBundledDriver): se o
    // usuario tocar Jogar no primeiro segundo, aquela sessao ainda usa
    // o bundle antigo; na proxima abertura ja e o 866.
    private static final int BUNDLED_SHADER_COUNT = 866;

    private void ensureBundledShaders() {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    File dst = new File(dataDir(), "shaders.dmsv");
                    if (installedShaderCount(dst) >= BUNDLED_SHADER_COUNT) return;
                    InputStream in = getAssets().open("shaders.dmsv");
                    File tmp = new File(dataDir(), "shaders.dmsv.tmp");
                    FileOutputStream out = new FileOutputStream(tmp);
                    byte[] buf = new byte[262144];
                    int n;
                    try {
                        while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
                    } finally {
                        try { in.close(); } catch (Exception ignored) {}
                        try { out.close(); } catch (Exception ignored) {}
                    }
                    if (dst.exists() && !dst.delete()) return;
                    tmp.renameTo(dst);
                } catch (Exception ignored) {}
            }
        }).start();
    }

    // Setsuna v10: traducao PT-BR embutida (assets/ptbr/, 10 arquivos,
    // 113 MB): na abertura, se o ptbr estiver ligado, copia para
    // files/ptbr-overlay/ o que faltar ou divergir em tamanho do
    // asset (via .tmp + rename, como writeFile). O host le a overlay
    // antes do RomFS original (DM_ROMFS_OVERLAY, hle_fs.c) — o data
    // pack nao e tocado e nada precisa de backup. Copia em thread de
    // fundo (como ensureBundledDriver/Shaders): se o usuario tocar
    // Jogar no primeiro segundo apos instalar, aquela sessao ainda
    // pode estar em ingles; na proxima abertura ja e PT-BR. O toast
    // avisa quando a copia termina.
    // v10: alem do tamanho, a VERSAO do manifesto decide — o
    // resources.assets da v10 tem o MESMO tamanho que o da v9 (o patch
    // do credito zerou blocos DXT5 in place), entao so o tamanho nao
    // detecta a troca. A versao instalada vive em
    // files/ptbr-overlay/version.txt; se diferir da do manifesto (ou
    // nao existir, caso de quem vem da v9), tudo e recopilado.
    private void ensureBundledTranslation() {
        if (ptbr == 0) return;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    File root = ptbrOverlayDir();
                    boolean copiedAny = false;
                    java.util.Map<String, Long> sizes = ptbrManifest();
                    String manifestVersion = ptbrManifestVersion;
                    String installedVersion = readInstalledPtbrVersion(root);
                    boolean versionChanged = manifestVersion != null && !manifestVersion.equals(installedVersion);
                    for (String rel : PTBR_FILES) {
                        File dst = new File(root, rel);
                        Long want = sizes.get(rel);
                        if (!versionChanged && dst.isFile() && want != null && dst.length() == want.longValue()) continue;
                        InputStream in = getAssets().open("ptbr/" + rel);
                        dst.getParentFile().mkdirs();
                        File tmp = new File(dst.getParentFile(), dst.getName() + ".tmp");
                        FileOutputStream out = new FileOutputStream(tmp);
                        byte[] buf = new byte[262144];
                        int n;
                        try {
                            while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
                        } finally {
                            try { in.close(); } catch (Exception ignored) {}
                            try { out.close(); } catch (Exception ignored) {}
                        }
                        if (dst.exists() && !dst.delete()) return;
                        if (!tmp.renameTo(dst)) return;
                        copiedAny = true;
                    }
                    if (versionChanged && ptbrReadyCount() == PTBR_FILES.length) {
                        writeInstalledPtbrVersion(root, manifestVersion);
                    }
                    if (copiedAny) {
                        final int ready = ptbrReadyCount();
                        mainHandler.post(new Runnable() {
                            @Override public void run() {
                                Toast.makeText(LauncherActivity.this,
                                        "Traducao PT-BR pronta (" + ready + "/10 arquivos) — vale na proxima abertura do jogo.",
                                        Toast.LENGTH_LONG).show();
                            }
                        });
                    }
                } catch (Exception ignored) {}
            }
        }).start();
    }

    // Versao do manifesto da traducao lida por ptbrManifest()
    // (ex. "v10"); ensureBundledTranslation compara com a versao
    // instalada em files/ptbr-overlay/version.txt.
    private String ptbrManifestVersion = null;

    // Manifesto da traducao (assets/ptbr/manifest.tsv, gerado no
    // build): "version\tv10" + "rel\ttamanho" por arquivo. A versao
    // marca a traducao: se ela mudar, tudo e recopilado; sem ela,
    // o tamanho decide (a copia por .tmp + rename e atomica, entao
    // um arquivo presente com o tamanho certo esta completo).
    private java.util.Map<String, Long> ptbrManifest() {
        java.util.Map<String, Long> m = new java.util.HashMap<String, Long>();
        BufferedReader r = null;
        try {
            r = new BufferedReader(new java.io.InputStreamReader(getAssets().open("ptbr/manifest.tsv"), "UTF-8"));
            String line;
            boolean versionOk = false;
            while ((line = r.readLine()) != null) {
                int t = line.indexOf('\t');
                if (t <= 0) continue;
                String k = line.substring(0, t);
                String v = line.substring(t + 1).trim();
                if ("version".equals(k)) { ptbrManifestVersion = v; versionOk = "v10".equals(v); continue; }
                if (!versionOk) continue;
                try { m.put(k, Long.parseLong(v)); } catch (Exception ignored) {}
            }
            if (!versionOk) m.clear();
        } catch (Exception ignored) {
            m.clear();
        } finally {
            try { if (r != null) r.close(); } catch (Exception ignored) {}
        }
        return m;
    }

    // Versao da traducao instalada em files/ptbr-overlay/version.txt
    // (uma linha, ex. "v10"). Sem ela (a v9 e anteriores nao
    // gravavam), ensureBundledTranslation recopila tudo na primeira
    // abertura da v10 — e o que faz o resources.assets sem o credito
    // chegar na overlay mesmo tendo o mesmo tamanho que o da v9.
    private String readInstalledPtbrVersion(File root) {
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader(new File(root, "version.txt")));
            String v = r.readLine();
            return v == null ? null : v.trim();
        } catch (Exception ignored) {
            return null;
        } finally {
            try { if (r != null) r.close(); } catch (Exception ignored) {}
        }
    }

    private void writeInstalledPtbrVersion(File root, String v) {
        if (v == null) return;
        File tmp = new File(root, "version.txt.tmp");
        try {
            root.mkdirs();
            FileOutputStream out = new FileOutputStream(tmp);
            out.write((v + "\n").getBytes("UTF-8"));
            out.close();
            File dst = new File(root, "version.txt");
            if (dst.exists() && !dst.delete()) return;
            tmp.renameTo(dst);
        } catch (Exception ignored) {}
    }

    private int installedShaderCount(File f) {
        if (!f.isFile()) return -1;
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            byte[] h = new byte[12];
            if (in.read(h) != 12) return -1;
            if (h[0] != 'D' || h[1] != 'M' || h[2] != 'S' || h[3] != 'V') return -1;
            return (h[8] & 0xFF) | ((h[9] & 0xFF) << 8)
                    | ((h[10] & 0xFF) << 16) | ((h[11] & 0xFF) << 24);
        } catch (Exception e) {
            return -1;
        } finally {
            try { if (in != null) in.close(); } catch (Exception ignored) {}
        }
    }

    private File extractDriverZip(InputStream in, String wantSlug) throws Exception {
        ZipInputStream zin = new ZipInputStream(in);
        String slug = wantSlug != null ? wantSlug : "driver";
        File dir = new File(driversDir(), slug);
        if (wantSlug == null) {
            int n = 2;
            while (dir.exists()) { dir = new File(driversDir(), slug + "-" + n); n++; }
        }
        dir.mkdirs();
        ZipEntry e;
        File soFile = null;
        long total = 0;
        while ((e = zin.getNextEntry()) != null) {
            String name = e.getName();
            if (name.contains("..") || name.startsWith("/")) continue;
            String base = name.substring(name.lastIndexOf('/') + 1);
            if (!base.endsWith(".so") && !base.equals("meta.json")) continue;
            File out = new File(dir, base);
            FileOutputStream fout = new FileOutputStream(out);
            byte[] buf = new byte[8192];
            int r;
            while ((r = zin.read(buf)) > 0) {
                total += r;
                if (total > 64L * 1024 * 1024) { fout.close(); throw new Exception("zip maior que 64 MB"); }
                fout.write(buf, 0, r);
            }
            fout.close();
            if (base.endsWith(".so")) {
                if (soFile != null) throw new Exception("zip com mais de um .so");
                soFile = out;
            }
        }
        zin.close();
        if (soFile == null) throw new Exception("nenhum .so dentro do zip");
        return dir;
    }

    private void showImportDialog() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        // v20: linhas plainValueRow; o Voltar volta ao submenu Jogo.
        col.addView(plainValueRow("Importar pasta do jogo extraida", null, new View.OnClickListener() {
            @Override public void onClick(View v) {
                confirmImport(new Runnable() {
                    @Override public void run() { pickTree(); }
                });
            }
        }), rowParams());
        col.addView(divider());
        col.addView(plainValueRow("Importar zip do data pack", null, new View.OnClickListener() {
            @Override public void onClick(View v) {
                confirmImport(new Runnable() {
                    @Override public void run() { pickZip(); }
                });
            }
        }), rowParams());
        col.addView(divider());
        col.addView(plainValueRow("Importar save (.zip)", null, new View.OnClickListener() {
            @Override public void onClick(View v) {
                pickSaveZip();
            }
        }), rowParams());
        col.addView(divider());
        TextView note = new TextView(this);
        note.setTextSize(13);
        note.setTextColor(DIALOG_TEXT);
        note.setText("Escolha a pasta/zip cujo conteudo e RomFS, shaders.dmsv e env.txt (nao a pasta-mae dela). O save (.zip com os .dat) vai para saves/ e e aplicado no jogo, em pausa > Import save — ele lista a pasta e importa na proxima abertura, com backup automatico do save atual.");
        col.addView(note);
        showPanel("Importar jogo", col);
    }

    private void pickSaveZip() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/zip");
        startActivityForResult(i, REQ_PICK_SAVE_ZIP);
    }

    private void confirmImport(final Runnable action) {
        if (importing) {
            showImportPhase("Importacao em andamento...");
            return;
        }
        if (!hasRomFs() && !hasShaders()) {
            action.run();
            return;
        }
        // v20: mesmo painel (faixa pincel + conteudo); Importar e
        // Cancelar sao botoes do conteudo, nao do dialogo.
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView msg = new TextView(this);
        msg.setText("Ja existem dados do jogo nesta pasta. Importar vai "
                + "substituir os arquivos com o mesmo nome. Continuar?");
        msg.setTextSize(TypedValue.COMPLEX_UNIT_PX, spPx(12));
        msg.setTextColor(DIALOG_TEXT);
        col.addView(msg);
        final android.app.Dialog[] dlg = new android.app.Dialog[1];
        Button pos = new Button(this);
        pos.setText("Importar");
        styleDialogButton(pos, true);
        pos.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (dlg[0] != null) dlg[0].dismiss();
                action.run();
            }
        });
        LinearLayout.LayoutParams posLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        posLp.topMargin = dp(12);
        col.addView(pos, posLp);
        Button neg = new Button(this);
        neg.setText("Cancelar");
        styleDialogButton(neg, false);
        neg.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { if (dlg[0] != null) dlg[0].dismiss(); }
        });
        LinearLayout.LayoutParams negLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        negLp.topMargin = dp(8);
        col.addView(neg, negLp);
        dlg[0] = showPanel("Importar dados", col);
    }

    private void pickTree() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(i, REQ_PICK_TREE);
    }

    private void pickZip() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/zip");
        startActivityForResult(i, REQ_PICK_ZIP);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        if (requestCode == REQ_PICK_TREE) {
            final Uri tree = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(tree,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {}
            copyTreeInBackground(tree);
        } else if (requestCode == REQ_PICK_ZIP) {
            copyZipInBackground(data.getData());
        } else if (requestCode == REQ_PICK_DRIVER_SO) {
            importDriverSo(data.getData());
        } else if (requestCode == REQ_PICK_DRIVER_ZIP) {
            importDriverZip(data.getData());
        } else if (requestCode == REQ_PICK_SAVE_ZIP) {
            importSaveZipInBackground(data.getData());
        }
    }

    private void listTree(Uri treeUri, String parentId, String rel,
                          List<DocEntry> out, ContentResolver cr) throws Exception {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId);
        Cursor c = null;
        try {
            c = cr.query(children, new String[]{
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE}, null, null, null);
            if (c == null) return;
            while (c.moveToNext()) {
                String id = c.getString(0);
                String name = c.getString(1);
                String mime = c.getString(2);
                long size = c.isNull(3) ? 0 : c.getLong(3);
                if (name == null || name.equals(".") || name.equals("..")) continue;
                DocEntry e = new DocEntry();
                e.uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id);
                e.rel = rel.isEmpty() ? name : rel + "/" + name;
                e.dir = DocumentsContract.Document.MIME_TYPE_DIR.equals(mime);
                e.size = size;
                out.add(e);
                if (e.dir) listTree(treeUri, id, e.rel, out, cr);
            }
        } finally {
            if (c != null) c.close();
        }
    }

    private void writeFile(InputStream in, File dest, long[] copied, long total, boolean closeIn) throws Exception {
        dest.getParentFile().mkdirs();
        File tmp = new File(dest.getParentFile(), dest.getName() + ".tmp");
        OutputStream out = new FileOutputStream(tmp);
        byte[] buf = new byte[262144];
        int n;
        try {
            while ((n = in.read(buf)) >= 0) {
                out.write(buf, 0, n);
                copied[0] += n;
                postImportProgress(copied[0], total);
            }
        } finally {
            try { if (closeIn) in.close(); } catch (Exception ignored) {}
            try { out.close(); } catch (Exception ignored) {}
        }
        if (dest.exists() && !dest.delete()) {
            throw new java.io.IOException("Nao foi possivel substituir " + dest.getName());
        }
        if (!tmp.renameTo(dest)) {
            throw new java.io.IOException("Falha ao finalizar " + dest.getName());
        }
    }

    private void finishImport() {
        if (!hasEnv()) { try { new java.io.File(dataDir(), "env.txt").createNewFile(); } catch (Exception ignored) {} }
        if (hasRomFs() && hasShaders()) {
            postStatus("Copia concluida. Pronto para jogar.");
        } else {
            StringBuilder missing = new StringBuilder();
            if (!hasRomFs()) missing.append("RomFS/ ");
            if (!hasShaders()) missing.append("shaders.dmsv ");
            if (!hasEnv()) missing.append("env.txt ");
            postStatus("Copia concluida, mas ainda falta: " + missing.toString()
                    + ". Verifique se escolheu a pasta/zip cujo conteudo e RomFS, "
                    + "shaders.dmsv e env.txt.");
        }
    }

    private void ensureImportDialog() {
        if (importLoadingDialog != null && importLoadingDialog.isShowing()) return;
        LinearLayout col = new LinearLayout(LauncherActivity.this);
        col.setOrientation(LinearLayout.VERTICAL);
        importLoadingPct = new TextView(LauncherActivity.this);
        importLoadingPct.setTextSize(24);
        importLoadingPct.setTypeface(null, android.graphics.Typeface.BOLD);
        importLoadingPct.setTextColor(0xFFA81616);  // v13: vermelho, legivel sobre o painel neve
        importLoadingPct.setText("\u2026");
        col.addView(importLoadingPct);
        LinearLayout track = new LinearLayout(LauncherActivity.this);
        track.setOrientation(LinearLayout.HORIZONTAL);
        GradientDrawable trackBg = new GradientDrawable();
        trackBg.setColor(0xFF333333);
        trackBg.setCornerRadius(dp(8));
        track.setBackground(trackBg);
        importLoadingFill = new View(LauncherActivity.this);
        GradientDrawable fillBg = new GradientDrawable();
        fillBg.setColor(0xFFD97A2B);  // Setsuna: laranja outono (folhas da key art)
        float r = dp(8);
        fillBg.setCornerRadii(new float[]{r, r, 0, 0, 0, 0, r, r});
        importLoadingFill.setBackground(fillBg);
        importLoadingEmpty = new View(LauncherActivity.this);
        track.addView(importLoadingFill, new LinearLayout.LayoutParams(0, dp(14), 0f));
        track.addView(importLoadingEmpty, new LinearLayout.LayoutParams(0, dp(14), 100f));
        col.addView(track);
        importLoadingDetail = new TextView(LauncherActivity.this);
        importLoadingDetail.setTextSize(14);
        importLoadingDetail.setTextColor(DIALOG_TEXT);
        col.addView(importLoadingDetail);
        importLoadingFile = new TextView(LauncherActivity.this);
        importLoadingFile.setTextSize(13);
        importLoadingFile.setTextColor(0xFF5A4A48);  // v13: cinza escuro sobre o painel neve
        col.addView(importLoadingFile);
        // v20: mesmo painel (faixa pincel + conteudo), SEM Voltar e
        // nao-cancelavel: o progresso so e dispensado por codigo.
        final android.app.Dialog[] dlg = new android.app.Dialog[1];
        LinearLayout shell = dialogShell("Importando...", dlg, false);
        ScrollView sv = new ScrollView(LauncherActivity.this);
        sv.addView(col);
        shell.addView(sv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        dlg[0] = cardDialog(shell);
        dlg[0].setCancelable(false);
        dlg[0].show();
        importLoadingDialog = dlg[0];
    }

    private void showImportPhase(final String msg) {
        mainHandler.post(new Runnable() {
            @Override public void run() {
                ensureImportDialog();
                importLoadingPct.setText("\u2026");
                importLoadingDetail.setText(msg);
                importLoadingFile.setText("");
                importLoadingFill.setLayoutParams(new LinearLayout.LayoutParams(0, dp(14), 0f));
                importLoadingEmpty.setLayoutParams(new LinearLayout.LayoutParams(0, dp(14), 100f));
            }
        });
    }

    private void dismissImportLoading() {
        mainHandler.post(new Runnable() {
            @Override public void run() {
                if (importLoadingDialog != null && importLoadingDialog.isShowing()) {
                    importLoadingDialog.dismiss();
                }
                importLoadingDialog = null;
                importLoadingPct = null;
                importLoadingDetail = null;
                importLoadingFile = null;
                importLoadingFill = null;
                importLoadingEmpty = null;
            }
        });
    }

    private void postImportProgress(long copied, long total) {
        if (total <= 0) return;
        final int pct = (int) (copied * 100 / total);
        if (pct == importLastPct) return;
        importLastPct = pct;
        final String detail = (copied / 1024 / 1024) + " MB de " + (total / 1024 / 1024) + " MB";
        final String file = importCurrentFile;
        mainHandler.post(new Runnable() {
            @Override public void run() {
                ensureImportDialog();
                importLoadingPct.setText(pct + "%");
                importLoadingDetail.setText(detail);
                importLoadingFile.setText(file);
                importLoadingFill.setLayoutParams(new LinearLayout.LayoutParams(0, dp(14), pct));
                importLoadingEmpty.setLayoutParams(new LinearLayout.LayoutParams(0, dp(14), 100 - pct));
            }
        });
    }

    private void showImportError(final String msg) {
        mainHandler.post(new Runnable() {
            @Override public void run() {
                LinearLayout col = new LinearLayout(LauncherActivity.this);
                col.setOrientation(LinearLayout.VERTICAL);
                TextView tv = new TextView(LauncherActivity.this);
                tv.setText(msg);
                tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, spPx(12));
                tv.setTextColor(DIALOG_TEXT);
                col.addView(tv);
                // v20: o Voltar fecha (nao ha botao Fechar).
                showPanel("Falha na importacao", col);
            }
        });
    }

    private void setImporting(final boolean on) {
        importing = on;
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                artLocked = on;
            }
        });
    }

    private void copyTreeInBackground(final Uri tree) {
        setImporting(true);
        showImportPhase("Lendo a pasta escolhida...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final File destRoot = dataDir();
                try {
                    ContentResolver cr = getContentResolver();
                    String rootId = DocumentsContract.getTreeDocumentId(tree);
                    List<DocEntry> entries = new ArrayList<DocEntry>();
                    listTree(tree, rootId, "", entries, cr);
                    boolean hasTopRomFs = false;
                    for (DocEntry e : entries) if (e.rel.equals("RomFS") && e.dir) hasTopRomFs = true;
                    if (!hasTopRomFs) {
                        throw new java.io.IOException("A pasta escolhida nao contem RomFS/ "
                                + "no topo. Escolha a pasta cujo conteudo e RomFS, "
                                + "shaders.dmsv e env.txt (nao a pasta-mae dela).");
                    }
                    long total = 0;
                    for (DocEntry e : entries) if (!e.dir) total += e.size;
                    if (total > 0) {
                        StatFs stat = new StatFs(destRoot.getAbsolutePath());
                        if (stat.getAvailableBytes() < total + 10L * 1024 * 1024) {
                            throw new java.io.IOException("Espaco livre insuficiente para "
                                    + (total / 1024 / 1024) + " MB.");
                        }
                    }
                    long[] copied = new long[]{0};
                    for (DocEntry e : entries) {
                        File dest = new File(destRoot, e.rel);
                        if (e.dir) {
                            dest.mkdirs();
                            continue;
                        }
                        InputStream in = cr.openInputStream(e.uri);
                        if (in == null) throw new java.io.IOException("Nao foi possivel abrir " + e.rel);
                        importCurrentFile = e.rel;
                        writeFile(in, dest, copied, total, true);
                    }
                    finishImport();
                } catch (final Exception ex) {
                    showImportError("Falha na copia: " + ex.getMessage());
                } finally {
                    dismissImportLoading();
                    setImporting(false);
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            refreshPlay();
                        }
                    });
                }
            }
        }).start();
    }

    private void copyZipInBackground(final Uri zipUri) {
        setImporting(true);
        showImportPhase("Lendo o zip escolhido...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final File destRoot = dataDir();
                try {
                    InputStream raw = getContentResolver().openInputStream(zipUri);
                    if (raw == null) throw new java.io.IOException("Nao foi possivel abrir o zip.");
                    List<String> names = new ArrayList<String>();
                    ZipInputStream zin = new ZipInputStream(raw);
                    ZipEntry ze;
                    long total = 0;
                    while ((ze = zin.getNextEntry()) != null) {
                        String name = ze.getName();
                        if (name.contains("..") || name.startsWith("/") || name.startsWith("\\")) {
                            throw new java.io.IOException("Entrada invalida no zip: " + name);
                        }
                        names.add(name);
                        if (ze.getSize() > 0) total += ze.getSize();
                    }
                    zin.close();
                    String prefix = "";
                    boolean allSharePrefix = !names.isEmpty();
                    String first = names.get(0);
                    int slash = first.indexOf('/');
                    if (slash > 0) {
                        String cand = first.substring(0, slash + 1);
                        for (String nm : names) if (!nm.startsWith(cand)) allSharePrefix = false;
                        if (allSharePrefix) prefix = cand;
                    }
                    boolean hasTopRomFs = false;
                    for (String nm : names) {
                        String rel = prefix.isEmpty() ? nm : nm.substring(prefix.length());
                        if (rel.equals("RomFS/") || rel.equals("RomFS")) hasTopRomFs = true;
                    }
                    if (!hasTopRomFs) {
                        throw new java.io.IOException("O zip nao contem RomFS/ no topo. "
                                + "Escolha o zip do data pack (conteudo: RomFS, "
                                + "shaders.dmsv, env.txt).");
                    }
                    if (total > 0) {
                        StatFs stat = new StatFs(destRoot.getAbsolutePath());
                        if (stat.getAvailableBytes() < total + 10L * 1024 * 1024) {
                            throw new java.io.IOException("Espaco livre insuficiente para "
                                    + (total / 1024 / 1024) + " MB.");
                        }
                    }
                    InputStream raw2 = getContentResolver().openInputStream(zipUri);
                    if (raw2 == null) throw new java.io.IOException("Nao foi possivel reabrir o zip.");
                    ZipInputStream zin2 = new ZipInputStream(raw2);
                    long[] copied = new long[]{0};
                    ZipEntry ze2;
                    while ((ze2 = zin2.getNextEntry()) != null) {
                        String name = ze2.getName();
                        String rel = prefix.isEmpty() ? name : name.substring(prefix.length());
                        if (rel.isEmpty()) continue;
                        File dest = new File(destRoot, rel);
                        if (ze2.isDirectory() || name.endsWith("/")) {
                            dest.mkdirs();
                            continue;
                        }
                        importCurrentFile = rel;
                        writeFile(zin2, dest, copied, total, false);
                        zin2.closeEntry();
                    }
                    zin2.close();
                    finishImport();
                } catch (final Exception ex) {
                    showImportError("Falha na copia: " + ex.getMessage());
                } finally {
                    dismissImportLoading();
                    setImporting(false);
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            refreshPlay();
                        }
                    });
                }
            }
        }).start();
    }

    // Setsuna v9: importar save (.zip). O zip do jogador tem os 20
    // slots (setsuna00-19.dat) dentro de pastas aninhadas ("I am
    // Setsuna/Original/"); a importacao tira o prefixo comum e
    // achata UM nivel de pasta unica, deixando os .dat na raiz de
    // saves/<nome>/. O menu de pausa lista a pasta (Import save) e
    // aplica na proxima abertura, com backup automatico do save
    // atual (<stamp>-before-import, saves_android.c). O nome da pasta
    // vem do nome do zip, limitado a 48 caracteres (o host lista
    // nomes de ate 63); se ja existir, ganha -2, -3... Nada do save
    // vai para o APK: ele fica so em saves/, fora do data pack.
    private String saveFolderName(Uri uri) {
        String name = driverFileName(uri);
        if (name.toLowerCase().endsWith(".zip")) name = name.substring(0, name.length() - 4);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.') sb.append(c);
            else sb.append('_');
        }
        String out = sb.toString();
        while (out.startsWith(".") || out.startsWith("_")) out = out.substring(1);
        while (out.endsWith(".") || out.endsWith("_")) out = out.substring(0, out.length() - 1);
        if (out.isEmpty()) out = "save-importado";
        if (out.length() > 48) out = out.substring(0, 48);
        return out;
    }

    private void importSaveZipInBackground(final Uri zipUri) {
        setImporting(true);
        showImportPhase("Lendo o zip do save...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final File savesRoot = new File(dataDir(), "saves");
                try {
                    InputStream raw = getContentResolver().openInputStream(zipUri);
                    if (raw == null) throw new java.io.IOException("Nao foi possivel abrir o zip.");
                    List<String> names = new ArrayList<String>();
                    ZipInputStream zin = new ZipInputStream(raw);
                    ZipEntry ze;
                    long total = 0;
                    while ((ze = zin.getNextEntry()) != null) {
                        String name = ze.getName();
                        if (name.contains("..") || name.startsWith("/") || name.startsWith("\\")) {
                            throw new java.io.IOException("Entrada invalida no zip: " + name);
                        }
                        names.add(name);
                        if (ze.getSize() > 0) total += ze.getSize();
                    }
                    zin.close();
                    String prefix = "";
                    boolean allSharePrefix = !names.isEmpty();
                    String first = names.get(0);
                    int slash = first.indexOf('/');
                    if (slash > 0) {
                        String cand = first.substring(0, slash + 1);
                        for (String nm : names) if (!nm.startsWith(cand)) allSharePrefix = false;
                        if (allSharePrefix) prefix = cand;
                    }
                    List<String> rels = new ArrayList<String>();
                    for (String nm : names) {
                        String rel = prefix.isEmpty() ? nm : nm.substring(prefix.length());
                        if (!rel.isEmpty()) rels.add(rel);
                    }
                    // Achata UM nivel de pasta unica ("Original/" com
                    // tudo dentro): os .dat ficam na raiz da pasta.
                    boolean allInOneDir = !rels.isEmpty();
                    String single = null;
                    for (String rel : rels) {
                        int s = rel.indexOf('/');
                        if (s <= 0) { allInOneDir = false; break; }
                        String top = rel.substring(0, s + 1);
                        if (single == null) single = top;
                        else if (!single.equals(top)) { allInOneDir = false; break; }
                    }
                    if (allInOneDir && single != null) {
                        List<String> flat = new ArrayList<String>();
                        for (String rel : rels) flat.add(rel.substring(single.length()));
                        rels = flat;
                    }
                    int dats = 0;
                    for (String rel : rels) if (rel.toLowerCase().endsWith(".dat")) dats++;
                    if (dats == 0) {
                        throw new java.io.IOException("O zip nao tem nenhum arquivo .dat dentro. "
                                + "Escolha o zip com os saves (setsuna00.dat ... setsuna19.dat).");
                    }
                    if (total > 0) {
                        StatFs stat = new StatFs(dataDir().getAbsolutePath());
                        if (stat.getAvailableBytes() < total + 10L * 1024 * 1024) {
                            throw new java.io.IOException("Espaco livre insuficiente para "
                                    + (total / 1024 / 1024) + " MB.");
                        }
                    }
                    String folder = saveFolderName(zipUri);
                    File destRoot = new File(savesRoot, folder);
                    for (int k = 2; destRoot.exists() && k < 100; k++) {
                        destRoot = new File(savesRoot, folder + "-" + k);
                    }
                    if (destRoot.exists()) {
                        throw new java.io.IOException("Ja existem muitas pastas com esse nome em saves/.");
                    }
                    destRoot.mkdirs();
                    InputStream raw2 = getContentResolver().openInputStream(zipUri);
                    if (raw2 == null) throw new java.io.IOException("Nao foi possivel reabrir o zip.");
                    ZipInputStream zin2 = new ZipInputStream(raw2);
                    long[] copied = new long[]{0};
                    ZipEntry ze2;
                    while ((ze2 = zin2.getNextEntry()) != null) {
                        String name = ze2.getName();
                        String rel = prefix.isEmpty() ? name : name.substring(prefix.length());
                        if (allInOneDir && single != null && rel.startsWith(single)) rel = rel.substring(single.length());
                        if (rel.isEmpty()) continue;
                        File dest = new File(destRoot, rel);
                        if (ze2.isDirectory() || name.endsWith("/")) {
                            dest.mkdirs();
                            continue;
                        }
                        importCurrentFile = rel;
                        writeFile(zin2, dest, copied, total, false);
                        zin2.closeEntry();
                    }
                    zin2.close();
                    final String doneName = destRoot.getName();
                    final int doneDats = dats;
                    mainHandler.post(new Runnable() {
                        @Override public void run() {
                            Toast.makeText(LauncherActivity.this,
                                    "Save importado: " + doneName + " (" + doneDats + " arquivos). "
                                            + "No jogo, pausa > Import save e escolha " + doneName + ".",
                                    Toast.LENGTH_LONG).show();
                        }
                    });
                } catch (final Exception ex) {
                    showImportError("Falha ao importar o save: " + ex.getMessage());
                } finally {
                    dismissImportLoading();
                    setImporting(false);
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            refreshPlay();
                        }
                    });
                }
            }
        }).start();
    }

    private void postStatus(final String text) {
        mainHandler.post(new Runnable() {
            @Override public void run() {
                Toast.makeText(LauncherActivity.this, text, Toast.LENGTH_SHORT).show();
            }
        });
    }
}

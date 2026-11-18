#!/usr/bin/env python3
# AVISO — HISTORICO (v1): este script gerou o LauncherActivity.java
# INICIAL, a partir do launcher generico do fork. Ele NAO reproduz o
# arquivo atual (v21, 21 versoes de edicoes depois) e NAO deve ser rodado
# sobre ele: o DST aponta para launcher-v1, que nao existe neste
# repositorio. Guardado como registro de como o launcher comecou.
"""Gera o LauncherActivity.java de I am Setsuna a partir do launcher
generico do fork-local, com:
- paleta Setsuna (neve/azul-gelo + laranja de outono da key art);
- botao "Exportar log" no topo do Sobre (copia log.txt p/ Downloads
  via MediaStore + share, padrao SteamWorld v15);
- Turnip embutido: extrai assets/turnip-8g2.zip p/ gpu_drivers/
  na primeira execucao (padrao Heaven Dust v14).
Falha se qualquer substituicao nao bater exatamente 1 vez."""
import sys

SRC = "/home/hatch/workspace/nx2android-v0.2.0/nxnative/dm/android/launcher/LauncherActivity.java"
DST = "/home/hatch/workspace/nx2a-iamsetsuna/launcher-v1/android/src/org/nx2a/launcher/LauncherActivity.java"

s = open(SRC, encoding="utf-8").read()

def rep(old, new):
    global s
    n = s.count(old)
    assert n == 1, "esperava 1 ocorrencia de %r, achei %d" % (old[:80], n)
    s = s.replace(old, new)

# --- paleta Setsuna (neve/azul-gelo + laranja outono) ---
rep("sub.setTextColor(0xFFC9A227);", "sub.setTextColor(0xFFA8CCE8);  // Setsuna: azul-gelo palido")
rep("d.setColor(primary ? 0xDD6B4E1A : 0xCC141414);",
    "d.setColor(primary ? 0xDD2E5A88 : 0xCC0E1B26);  // Setsuna: azul aco gelo / ardósia escura")
rep("""        d.setColor(primary ? 0xDD2E5A88 : 0xCC0E1B26);  // Setsuna: azul aco gelo / ardósia escura
        d.setCornerRadius(dp(12));
        d.setStroke(dp(2), 0xFF8C6A1F);
        b.setBackground(d);""",
    """        d.setColor(primary ? 0xDD2E5A88 : 0xCC0E1B26);  // Setsuna: azul aco gelo / ardósia escura
        d.setCornerRadius(dp(12));
        d.setStroke(dp(2), 0xFFBFD9EA);  // Setsuna: borda gelo palido
        b.setBackground(d);""")
rep("""        bg.setColor(0xFF141414);
        bg.setCornerRadius(dp(20));
        bg.setStroke(dp(2), 0xFF8C6A1F);""",
    """        bg.setColor(0xFF0E1B26);  // Setsuna: azul-ardosia profundo
        bg.setCornerRadius(dp(20));
        bg.setStroke(dp(2), 0xFF7FB3D5);""")
rep("""        d.setColor(0xFF6B4E1A);
        d.setCornerRadius(dp(12));
        d.setStroke(dp(2), 0xFF8C6A1F);""",
    """        d.setColor(0xFF2E5A88);  // Setsuna: azul aco
        d.setCornerRadius(dp(12));
        d.setStroke(dp(2), 0xFFBFD9EA);""")
rep("importLoadingPct.setTextColor(0xFFC9A227);",
    "importLoadingPct.setTextColor(0xFFE8A34C);  // Setsuna: ambar outono")
rep("fillBg.setColor(0xFF8C6A1F);",
    "fillBg.setColor(0xFFD97A2B);  // Setsuna: laranja outono (folhas da key art)")

# --- imports p/ Exportar log ---
rep("import android.content.ContentResolver;",
    "import android.content.ContentResolver;\nimport android.content.ContentValues;")
rep("import android.os.Bundle;",
    "import android.os.Bundle;\nimport android.os.Environment;")
rep("import android.provider.DocumentsContract;",
    "import android.provider.DocumentsContract;\nimport android.provider.MediaStore;")

# --- Sobre: botao Exportar log no TOPO + ScrollView + sobreDlg ---
rep("""    private void showSobreDialog() {
        String ver = "desconhecida";""",
    """    private void showSobreDialog() {
        final AlertDialog[] sobreDlg = new AlertDialog[1];
        String ver = "desconhecida";""")
rep("""        col.addView(info);
        TextView logTitle = new TextView(this);
        logTitle.setTextSize(13);
        logTitle.setTextColor(0xFFC9A227);""",
    """        col.addView(info);
        // Setsuna v1: Exportar log no TOPO do Sobre (padrao SteamWorld v15):
        // o log mora na pasta privada do app, que o gerenciador nao deixa
        // abrir; o botao copia para Downloads (publico) e abre o compartilhar.
        Button expBtn = new Button(this);
        expBtn.setText("Exportar log");
        styleDialogButton(expBtn);
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
        logTitle.setTextColor(0xFFA8CCE8);  // Setsuna: azul-gelo palido""")
rep("""        LinearLayout.LayoutParams logLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(180));
        col.addView(logTv, logLp);
        themed(new AlertDialog.Builder(this).setTitle("Sobre").setView(col)
                .setPositiveButton("Fechar", null));
    }""",
    """        LinearLayout.LayoutParams logLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(180));
        col.addView(logTv, logLp);
        ScrollView sobreSv = new ScrollView(this);
        sobreSv.addView(col);
        sobreDlg[0] = themed(new AlertDialog.Builder(this).setTitle("Sobre").setView(sobreSv)
                .setPositiveButton("Fechar", null));
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
    }""")

# --- extrair o Turnip embutido na primeira abertura ---
rep("""        setContentView(root);
        loadSettings();
        loadDriverChoice();
        refreshPlay();
    }""",
    """        setContentView(root);
        loadSettings();
        loadDriverChoice();
        refreshPlay();
        ensureBundledDriver();
    }""")

open(DST, "w", encoding="utf-8").write(s)
print("OK: %s (%d bytes, %d linhas)" % (DST, len(s.encode()), s.count("\n") + 1))

#!/bin/bash
# I am Setsuna — launcher (nx2android).
# Package org.nx2a.t0100849000bda000. Build pipeline: aapt2 + javac + d8,
# injection of dex/.so/theme, realign.py (16 KB .so alignment) and check.py.
# Sign with YOUR OWN keystore and the mkapk.py v2 signer from your
# nx2android checkout (see BUILD.md) — NEVER apksigner here: it rewrites
# the local headers and breaks the dex alignment realign.py established.
# Host: build it first from ../host (see host/README.md); this script
# expects it at ../host/out/libdmhost.so (HOST_SO overrides).
# versionCode only ever increases (last used: 1794915277, launcher v21).
set -e
ROOT="$(cd "$(dirname "$0")" && pwd)"
JDK="${JDK:-/usr/lib/jvm/java-17-openjdk-amd64}"
[ -x "$JDK/bin/javac" ] || JDK="$HOME/jdk-dl/jdk-17.0.20.1+1"
SDK="${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}"
BT="$SDK/build-tools/36.1.0"
ANDROID_JAR="$SDK/platforms/android-36/android.jar"
THEME="$ROOT/theme-setsuna"
HOST_SO="${HOST_SO:-$ROOT/../host/out/libdmhost.so}"
EXPECTED_HOST_SHA="${EXPECTED_HOST_SHA:-}"
ADRENO_DIR="${ADRENO_DIR:-}"
[ -n "$ADRENO_DIR" ] && [ -d "$ADRENO_DIR" ] || { echo "ADRENO_DIR nao definido ou inexistente (veja BUILD.md)"; exit 1; }
TURNIP_ZIP="$ROOT/android/assets/turnip-8g2.zip"
BG_JPG="$ROOT/android/assets/launcher_bg.jpg"
SHADERS_DMSV="$ROOT/android/assets/shaders.dmsv"
PTBR_DIR="$ROOT/android/assets/ptbr"
QR_PNG="$ROOT/android/assets/donate_pix_qr.png"
export PATH="$JDK/bin:$PATH"

VERCODE="${VERCODE:-$(date +%s)}"
BUILD="$ROOT/build"
rm -rf "$BUILD" && mkdir -p "$BUILD/classes" "$BUILD/dex"
sed "s/@VERCODE@/$VERCODE/" "$ROOT/android/AndroidManifest.xml" > "$BUILD/AndroidManifest.xml"
echo "versionCode=$VERCODE"

echo "[0/6] conferir host com adrenotools e seus marcadores"
[ -f "$HOST_SO" ] || { echo "HOST_SO ausente: $HOST_SO (compile o host primeiro, veja host/README.md)"; exit 1; }
cp "$HOST_SO" "$BUILD/libdmhost.so"
HOST_SHA="$(sha256sum "$BUILD/libdmhost.so" | cut -d' ' -f1)"
echo "host sha=$HOST_SHA bytes=$(stat -c%s "$BUILD/libdmhost.so")"
if [ -n "$EXPECTED_HOST_SHA" ]; then [ "$HOST_SHA" = "$EXPECTED_HOST_SHA" ] || { echo "FAIL: host nao e o host esperado"; exit 1; }; fi
strings "$BUILD/libdmhost.so" | grep -q "custom GPU driver via adrenotools" || { echo "FAIL: host sem marcador adrenotools"; exit 1; }
strings "$BUILD/libdmhost.so" | grep -q "DM_VK_DRIVER" || { echo "FAIL: host sem marcador DM_VK_DRIVER"; exit 1; }

echo "[1/6] aapt2 compile res"
"$BT/aapt2" compile --dir "$ROOT/android/res" -o "$BUILD/compiled_res.zip"

echo "[2/6] javac + d8"
"$JDK/bin/javac" -encoding UTF-8 --release 8 -cp "$ANDROID_JAR" -d "$BUILD/classes" \
  "$ROOT/android/src/org/nx2a/launcher/LauncherActivity.java"
"$BT/d8" --min-api 29 --lib "$ANDROID_JAR" --output "$BUILD/dex" \
  "$BUILD"/classes/org/nx2a/launcher/*.class

echo "[3/6] aapt2 link"
"$BT/aapt2" link -I "$ANDROID_JAR" --manifest "$BUILD/AndroidManifest.xml" \
  -o "$BUILD/base.apk" "$BUILD/compiled_res.zip"

echo "[4/6] inject dex + host .so + adrenotools + turnip + tema + fundo"
echo "      (+ shaders.dmsv, ptbr e QR do Pix, se presentes — nenhum deles vem no repo)"
python3 - "$BUILD/base.apk" "$BUILD/injected.apk" "$BUILD/libdmhost.so" "$THEME" "$BUILD/dex/classes.dex" "$ADRENO_DIR" "$TURNIP_ZIP" "$BG_JPG" "$SHADERS_DMSV" "$PTBR_DIR" "$QR_PNG" << 'PY'
import sys, zipfile, os
base, out, host_so, theme, dex_path, at_dir, turnip_zip, bg, shaders_dmsv, ptbr_dir, qr_png = sys.argv[1:12]
zin = zipfile.ZipFile(base)
entries = [(i.filename, zin.read(i.filename), i.compress_type) for i in zin.infolist()]
zin.close()
with zipfile.ZipFile(out, "w") as z:
    for name, data, method in entries:
        z.writestr(name, data, compress_type=method)
    with open(dex_path, "rb") as f:
        z.writestr("classes.dex", f.read(), compress_type=zipfile.ZIP_DEFLATED)
    with open(host_so, "rb") as f:
        z.writestr("lib/arm64-v8a/libdmhost.so", f.read(), compress_type=zipfile.ZIP_STORED)
    for at in ("libadrenotools.so", "libhook_impl.so", "libmain_hook.so", "libfile_redirect_hook.so", "libgsl_alloc_hook.so"):
        src = at_dir + ("/" + at if at == "libadrenotools.so" else "/src/hook/" + at)
        with open(src, "rb") as f:
            z.writestr("lib/arm64-v8a/" + at, f.read(), compress_type=zipfile.ZIP_STORED)
    with open(turnip_zip, "rb") as f:
        z.writestr("assets/turnip-8g2.zip", f.read(), compress_type=zipfile.ZIP_STORED)
    with open(bg, "rb") as f:
        z.writestr("assets/launcher_bg.jpg", f.read(), compress_type=zipfile.ZIP_STORED)
    if os.path.isfile(shaders_dmsv):
        with open(shaders_dmsv, "rb") as f:
            z.writestr("assets/shaders.dmsv", f.read(), compress_type=zipfile.ZIP_STORED)
        print("shaders.dmsv: injetado")
    else:
        print("shaders.dmsv: ausente, nao injetado (importe o data pack pelo launcher)")
    # QR do Pix (doacao): so entra se voce gerar o seu e colocar o PNG
    # em android/assets/donate_pix_qr.png (veja BUILD.md). Sem ele, o
    # dialogo Donate abre so com o texto — nao quebra o launcher.
    if os.path.isfile(qr_png):
        with open(qr_png, "rb") as f:
            z.writestr("assets/donate_pix_qr.png", f.read(), compress_type=zipfile.ZIP_STORED)
        print("donate_pix_qr.png: injetado")
    else:
        print("donate_pix_qr.png: ausente, dialogo Donate sem QR")
    for asset in ("theme.tsv", "phone.png", "tablet.png"):
        with open(theme + "/" + asset, "rb") as f:
            z.writestr("assets/overlay/" + asset, f.read(), compress_type=zipfile.ZIP_STORED)
    # Traducao PT-BR embutida: os arquivos, no mesmo layout do RomFS,
    # como ZIP_DEFLATED + o manifesto (STORED) com a versao e o tamanho
    # de cada arquivo, que o launcher usa para decidir o que copiar.
    # So entra se voce tiver os arquivos em android/assets/ptbr/.
    if os.path.isdir(ptbr_dir) and os.listdir(ptbr_dir):
        man = ["version\tv10"]
        files = []
        for dp, _dns, fns in os.walk(ptbr_dir):
            for fn in sorted(fns):
                p = os.path.join(dp, fn)
                files.append((os.path.relpath(p, ptbr_dir), p))
        files.sort(key=lambda t: t[0])
        for rel, p in files:
            man.append("%s\t%d" % (rel, os.path.getsize(p)))
        z.writestr("assets/ptbr/manifest.tsv", ("\n".join(man) + "\n").encode(), compress_type=zipfile.ZIP_STORED)
        for rel, p in files:
            with open(p, "rb") as f:
                z.writestr("assets/ptbr/" + rel, f.read(), compress_type=zipfile.ZIP_DEFLATED)
        print("ptbr: %d arquivos injetados" % len(files))
    else:
        print("ptbr: ausente, nao injetado (o jogo abre em ingles)")
PY

echo "[5/6] realign (16 KB para os .so, 4 bytes para o dex)"
python3 "$ROOT/realign.py" "$BUILD/injected.apk" "$BUILD/aligned.apk"

echo "[6/6] gates (check.py) no APK alinhado — ainda SEM assinatura"
python3 "$ROOT/check.py" "$BUILD/aligned.apk"
"$BT/aapt2" dump badging "$BUILD/aligned.apk" | grep -E "package:|launchable-activity:|native-code:|application-label:"
ls -l "$BUILD/aligned.apk"
sha256sum "$BUILD/aligned.apk"
echo
echo "Falta assinar (veja BUILD.md, secao Assinatura): use o signer v2 do"
echo "mkapk.py do seu checkout do nx2android com a SUA keystore — nunca"
echo "apksigner, que quebra o alinhamento do dex."
echo "Exemplo: python3 sign.py build/aligned.apk IAmSetsuna-launcher.apk"

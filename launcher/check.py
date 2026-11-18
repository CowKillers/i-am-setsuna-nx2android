#!/usr/bin/env python3
"""Gates do launcher de I am Setsuna, a partir do APK final:
offset real dos dados via headers locais, SHA do .so dentro do APK
contra o host buildado, presenca dos assets, alinhamento.
Nada aqui confia na ferramenta de build.

Versao do repositorio (adaptada da v21): o host e conferido contra
../host/out/libdmhost.so (ou EXPECTED_HOST_SHA); o adrenotools, contra
$ADRENO_DIR; e cada asset so e conferido quando a fonte local existe
(shaders.dmsv, ptbr e o QR do Pix nao vem no repo — sem a fonte, o gate
vira SKIP, nao FAIL)."""
import hashlib
import os
import struct
import sys
import zipfile

ROOT = os.path.dirname(os.path.abspath(__file__))
SO = "lib/arm64-v8a/libdmhost.so"


def sha(path):
    return hashlib.sha256(open(path, "rb").read()).hexdigest()


def local_offsets(path):
    out = {}
    with open(path, "rb") as f:
        data = f.read()
    off = 0
    while off + 30 <= len(data):
        (sig,) = struct.unpack_from("<I", data, off)
        if sig != 0x04034B50:
            break
        (_sig, _ver, _flag, method, _t, _d, _crc, _csize, _usize,
         nlen, elen) = struct.unpack_from("<IHHHHHIIIHH", data, off)
        name = data[off + 30:off + 30 + nlen].decode()
        out[name] = (off, off + 30 + nlen + elen, method)
        off = off + 30 + nlen + elen + _csize
    return out


def main():
    path = sys.argv[1]
    offs = local_offsets(path)
    ok = True
    for name, (hoff, doff, method) in sorted(offs.items()):
        print("  %-45s header=%d data=%d method=%d" % (name, hoff, doff, method))
    z = zipfile.ZipFile(path)
    if SO not in offs:
        print("FAIL: %s ausente" % SO)
        return 1
    doff = offs[SO][1]
    print("  .so data offset=%d mod 16384=%d" % (doff, doff % 16384))
    if doff % 16384 != 0:
        print("FAIL: .so desalinhado")
        ok = False
    so = z.read(SO)
    so_sha = hashlib.sha256(so).hexdigest()
    print("  .so bytes=%d sha=%s" % (len(so), so_sha))
    host_src = os.path.join(ROOT, "..", "host", "out", "libdmhost.so")
    want_sha = os.environ.get("EXPECTED_HOST_SHA", "")
    if os.path.isfile(host_src):
        if so_sha != sha(host_src):
            print("FAIL: .so dentro do APK nao e o host buildado")
            ok = False
    elif want_sha:
        if so_sha != want_sha:
            print("FAIL: .so dentro do APK nao e o host esperado")
            ok = False
    else:
        print("  (sem host/out nem EXPECTED_HOST_SHA: SHA so reportado, nao conferido)")
    # adrenotools: comparar com os builds do fonte ($ADRENO_DIR);
    # sem ele, so presenca + alinhamento sao conferidos
    at_dir = os.environ.get("ADRENO_DIR", "")
    for at in ("libadrenotools.so", "libhook_impl.so", "libmain_hook.so",
               "libfile_redirect_hook.so", "libgsl_alloc_hook.so"):
        atpath = "lib/arm64-v8a/" + at
        if atpath not in offs:
            print("FAIL: ausente: %s" % atpath); ok = False; continue
        if offs[atpath][1] % 16384 != 0:
            print("FAIL: %s desalinhado (mod 16384)" % at); ok = False
        src = at_dir + ("/" + at if at == "libadrenotools.so" else "/src/hook/" + at) if at_dir else ""
        h = hashlib.sha256(z.read(atpath)).hexdigest()
        print("  %s sha=%s" % (at, h))
        if src and os.path.isfile(src):
            if h != sha(src):
                print("FAIL: %s nao e o build do fonte" % at); ok = False
        elif src:
            print("  (fonte %s ausente: SHA so reportado)" % src)
    # assets: comparar com as fontes do launcher
    ptbr = ("Data/level6", "Data/level7", "Data/level8",
            "Data/level10", "Data/level12", "Data/level13", "Data/level15",
            "Data/resources.assets",
            "Data/Managed/Metadata/global-metadata.dat",
            "Data/StreamingAssets/NX/parameter.cpk")
    asset_pairs = [
            ("assets/turnip-8g2.zip", os.path.join(ROOT, "android/assets/turnip-8g2.zip")),
            ("assets/launcher_bg.jpg", os.path.join(ROOT, "android/assets/launcher_bg.jpg")),
            ("assets/shaders.dmsv", os.path.join(ROOT, "android/assets/shaders.dmsv")),
            ("assets/donate_pix_qr.png", os.path.join(ROOT, "android/assets/donate_pix_qr.png")),
            ("assets/overlay/theme.tsv", os.path.join(ROOT, "theme-setsuna/theme.tsv")),
            ("assets/overlay/phone.png", os.path.join(ROOT, "theme-setsuna/phone.png")),
            ("assets/overlay/tablet.png", os.path.join(ROOT, "theme-setsuna/tablet.png")),
    ]
    for rel in ptbr:
        asset_pairs.append(("assets/ptbr/" + rel,
                            os.path.join(ROOT, "android/assets/ptbr", rel)))
    for asset, src in asset_pairs:
        if asset not in offs:
            if os.path.isfile(src):
                print("FAIL: ausente: %s" % asset); ok = False
            else:
                print("  SKIP: %s ausente no APK (fonte local tambem ausente)" % asset)
            continue
        h = hashlib.sha256(z.read(asset)).hexdigest()
        print("  %s sha=%s" % (asset, h))
        if os.path.isfile(src):
            if h != sha(src):
                print("FAIL: %s nao bate com a fonte" % asset); ok = False
        else:
            print("  (fonte %s ausente: SHA so reportado)" % src)
    # ptbr: os 10 tem que estar DEFLATED (o APK tem que caber no
    # chat, < 100 MB) e o manifesto STORED, com a versao e os
    # tamanhos certos (o launcher decide a copia por ele)
    for asset, _src in asset_pairs:
        if asset not in offs:
            continue
        if asset.startswith("assets/ptbr/") and asset != "assets/ptbr/manifest.tsv" and offs[asset][2] != 8:
            print("FAIL: %s nao comprimido (method=%d), tem que ser DEFLATED" % (asset, offs[asset][2])); ok = False
    ptbr_local = os.path.join(ROOT, "android/assets/ptbr")
    if "assets/ptbr/manifest.tsv" in offs:
        if offs["assets/ptbr/manifest.tsv"][2] != 0:
            print("FAIL: manifest.tsv comprimido, tem que ser STORED"); ok = False
        if os.path.isdir(ptbr_local) and os.listdir(ptbr_local):
            man = ["version\tv10"]
            man_files = []
            for dp, _dns, fns in os.walk(ptbr_local):
                for fn in sorted(fns):
                    pth = os.path.join(dp, fn)
                    man_files.append((os.path.relpath(pth, ptbr_local), pth))
            man_files.sort(key=lambda t: t[0])
            for rel, pth in man_files:
                man.append("%s\t%d" % (rel, os.path.getsize(pth)))
            want_man = ("\n".join(man) + "\n").encode()
            if z.read("assets/ptbr/manifest.tsv") != want_man:
                print("FAIL: manifest.tsv nao bate com as fontes"); ok = False
            else:
                print("  manifest.tsv ok (version v10, %d arquivos)" % len(man_files))
        else:
            print("  (sem ptbr local: conteudo do manifesto nao conferido)")
    elif os.path.isdir(ptbr_local) and os.listdir(ptbr_local):
        print("FAIL: ausente: assets/ptbr/manifest.tsv"); ok = False
    else:
        print("  SKIP: ptbr ausente (sem traducao local, o jogo abre em ingles)")
    if "classes.dex" in offs:
        if offs["classes.dex"][1] % 4 != 0:
            print("FAIL: classes.dex desalinhado (mod 4)")
            ok = False
        dex = z.read("classes.dex")
        for marker in (b"Lorg/nx2a/launcher/LauncherActivity;",
                       b"Exportar log", b"IAmSetsuna-log.txt",
                       b"turnip-8g2", b"launcher_bg.jpg",
                       b"shaders.dmsv.tmp",
                       b"DM_PRESENT_INTERVAL=", b"DM_ROMFS_OVERLAY=",
                       b"ptbr-overlay", b"ptbr/",
                       b"manifest.tsv",
                       b"Importar save", b"Import save",
                       b"https://youtube.com/@emuucheck", b"@emuucheck",
                       b"ic_yt_play", b"SnowView",
                       b"logo_title", b"brush_red", b"brush_black",
                       b"Tradu",
                       b"Donate via Pix", b"donate_pix_qr", b"ic_heart",
                       b"Copiar c\xc3\xb3digo", b"Copiar chave",
                       b"Chave Pix copiada", b"C\xc3\xb3digo Pix copiado"):
            if marker not in dex:
                print("FAIL: dex sem marcador: %r" % marker); ok = False
            else:
                print("  dex tem: %r" % marker)
    else:
        print("FAIL: classes.dex ausente"); ok = False
    print("CHECK: %s" % ("OK" if ok else "FAIL"))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())

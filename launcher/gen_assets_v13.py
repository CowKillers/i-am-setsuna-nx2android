#!/usr/bin/env python3
"""Gera os assets visuais do launcher (redesign da v13).
- brush_red.png / brush_black.png: faixa pincel, para botoes e faixa
  "Android Port" (sempre gerados, sem precisar de arte externa).
- ic_play/ic_gear/ic_pad/ic_info.png: icones brancos 96x96 (idem).
- launcher_bg.jpg e logo_title.png: SO gerados quando voce aponta a
  sua propria arte nas variaveis SETSUNA_BG_SRC (foto 1920x1080) e
  SETSUNA_LOGO_SRC (foto com o titulo). Sem elas, os placeholders
  neutros que ja estao no repo ficam no lugar — o build funciona
  assim mesmo. A arte oficial do jogo NAO vem no repo: traga a sua.
- btn_yt.png: passo legado (o launcher atual usa ic_yt_play.png);
  so roda se SETSUNA_YT_SRC apontar para um arquivo existente.

Versao do repositorio (adaptada da v13): os caminhos da arte, que eram
fixos na maquina do autor, viraram variaveis de ambiente e os passos
de arte sao pulados quando a fonte nao existe.
"""
import hashlib
import math
import os
import random
import shutil

from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(ROOT, "android/res/drawable-nodpi")
BG_SRC = os.environ.get("SETSUNA_BG_SRC", "")
LOGO_SRC = os.environ.get("SETSUNA_LOGO_SRC", "")
YT_SRC = os.environ.get("SETSUNA_YT_SRC", "")


def sha(p):
    return hashlib.sha256(open(p, "rb").read()).hexdigest()


def extract_logo():
    img = Image.open(LOGO_SRC).convert("RGB")
    px = img.load()
    w, h = img.size
    mask = Image.new("L", (w, h), 0)
    mp = mask.load()
    for y in range(110, 350):
        for x in range(80, 1020):
            lum = sum(px[x, y]) // 3
            if lum < 70:
                mp[x, y] = max(0, min(255, (70 - lum) * 6))
    bbox = mask.getbbox()
    logo = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    logo.paste(Image.new("RGBA", (w, h), (18, 16, 16, 255)), (0, 0), mask)
    return logo.crop(bbox)


def brush_png(path, color, w=1200, h=140):
    img = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    rnd = random.Random(7)
    x0, y0, x1, y1 = 30, 18, w - 30, h - 18
    top, bot = [], []
    xs = list(range(x0, x1 + 1, 26)) + [x1]
    for x in xs:
        top.append((x, y0 + rnd.randint(-6, 6)))
        bot.append((x, y1 + rnd.randint(-6, 6)))
    d.polygon(top + bot[::-1], fill=color + (255,))
    d.polygon([(x0, y0 + 8), (4, (y0 + y1) // 2), (x0, y1 - 8)], fill=color + (255,))
    d.polygon([(x1, y0 + 8), (w - 4, (y0 + y1) // 2), (x1, y1 - 8)], fill=color + (255,))
    img.save(path)
    return img.size


def icon_png(path, kind):
    img = Image.new("RGBA", (96, 96), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    c = (255, 255, 255, 255)
    cx, cy, s = 48, 48, 30
    if kind == "play":
        d.polygon([(cx - s * 0.45, cy - s * 0.6), (cx - s * 0.45, cy + s * 0.6),
                   (cx + s * 0.65, cy)], fill=c)
    elif kind == "gear":
        for a in range(0, 360, 45):
            r = math.radians(a)
            d.line([(cx + math.cos(r) * s * 0.42, cy + math.sin(r) * s * 0.42),
                    (cx + math.cos(r) * s * 0.72, cy + math.sin(r) * s * 0.72)],
                   fill=c, width=int(s * 0.22))
        d.ellipse([cx - s * 0.48, cy - s * 0.48, cx + s * 0.48, cy + s * 0.48],
                  outline=c, width=int(s * 0.18))
        d.ellipse([cx - s * 0.16, cy - s * 0.16, cx + s * 0.16, cy + s * 0.16], fill=c)
    elif kind == "pad":
        d.rounded_rectangle([cx - s * 0.75, cy - s * 0.45, cx + s * 0.75, cy + s * 0.45],
                            radius=int(s * 0.4), outline=c, width=int(s * 0.14))
        d.rectangle([cx - s * 0.55, cy - s * 0.12, cx - s * 0.28, cy + s * 0.12], fill=c)
        d.rectangle([cx - s * 0.46, cy - s * 0.21, cx - s * 0.37, cy + s * 0.21], fill=c)
        d.ellipse([cx + s * 0.30, cy - s * 0.22, cx + s * 0.48, cy - s * 0.04], fill=c)
        d.ellipse([cx + s * 0.48, cy + s * 0.04, cx + s * 0.66, cy + s * 0.22], fill=c)
    elif kind == "info":
        d.ellipse([cx - s * 0.5, cy - s * 0.5, cx + s * 0.5, cy + s * 0.5],
                  outline=c, width=int(s * 0.14))
        d.ellipse([cx - s * 0.07, cy - s * 0.42, cx + s * 0.07, cy - s * 0.28], fill=c)
        d.rectangle([cx - s * 0.07, cy - s * 0.12, cx + s * 0.07, cy + s * 0.42], fill=c)
    img.save(path)


def main():
    os.makedirs(RES, exist_ok=True)
    # 1. fundo = sua arte (SETSUNA_BG_SRC), byte a byte; sem ela, o
    # placeholder neutro do repo fica no lugar.
    if BG_SRC and os.path.isfile(BG_SRC):
        dst_bg = os.path.join(ROOT, "android/assets/launcher_bg.jpg")
        shutil.copyfile(BG_SRC, dst_bg)
        bg = Image.open(dst_bg)
        print("launcher_bg.jpg: %s sha=%s" % (bg.size, sha(dst_bg)[:16]))
        assert bg.size == (1920, 1080)
        assert sha(dst_bg) == sha(BG_SRC)
        # 2. logo transparente, extraido da sua arte (SETSUNA_LOGO_SRC)
        if LOGO_SRC and os.path.isfile(LOGO_SRC):
            logo = extract_logo()
            lp = os.path.join(RES, "logo_title.png")
            logo.save(lp)
            print("logo_title.png: %s sha=%s" % (logo.size, sha(lp)[:16]))
        else:
            print("logo_title.png: SKIP (SETSUNA_LOGO_SRC ausente — placeholder fica)")
    else:
        print("launcher_bg.jpg: SKIP (SETSUNA_BG_SRC ausente — placeholder fica)")
    # 3. pinceis
    print("brush_red.png:", brush_png(os.path.join(RES, "brush_red.png"), (168, 22, 22)))
    print("brush_black.png:", brush_png(os.path.join(RES, "brush_black.png"), (26, 26, 28)))
    # 4. icones
    for kind in ("play", "gear", "pad", "info"):
        p = os.path.join(RES, "ic_%s.png" % kind)
        icon_png(p, kind)
        print("ic_%s.png: 96x96" % kind)
    # 5. botao YouTube (passo legado; o launcher atual usa
    # ic_yt_play.png). So roda com SETSUNA_YT_SRC apontando para um arquivo.
    if YT_SRC and os.path.isfile(YT_SRC):
        yt = os.path.join(RES, "btn_yt.png")
        shutil.copyfile(YT_SRC, yt)
        print("btn_yt.png: %s sha=%s" % (Image.open(yt).size, sha(yt)[:16]))
        assert sha(yt) == sha(YT_SRC)
    else:
        print("btn_yt.png: SKIP (legado, SETSUNA_YT_SRC ausente)")


if __name__ == "__main__":
    main()

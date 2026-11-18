# Host — arquivos alterados para I am Setsuna

Estes 3 arquivos são as alterações deste jogo sobre o
`nxnative/dm/android/` do nx2android. Copie-os por cima dos originais
no seu checkout do nx2android (faça backup dos originais antes) e
compile como descrito em `../BUILD.md`:

```sh
DM_GAMES_DIR=<este-repo>/profile \
  python3 <nx2android>/nxnative/dm/android/build.py \
  --game 0100849000BDA000 --ndk "$ANDROID_SDK_ROOT/ndk/27.2.12479018" \
  --lib --release --out <este-repo>/host/out
```

## O que cada arquivo faz

- `gfx_vk_present.c` — o present/overlay do host com o tema do launcher:
  paleta neve (`{244,241,234}`) + vermelho (`{168,22,22}`) + dourado,
  menu interno e teclado no tema do launcher, fonte AA com amostragem
  bilinear (`cv_text`) e os rótulos do gamepad desenhados pela mesma
  fonte (nada do bitmap 5x7 original). É a exceção game-specific
  estabelecida do fork (a paleta vive aqui desde a v7).
- `font_aa.h` — a fonte AA embutida (DejaVu Sans Bold 32 px, 191 glifos,
  ASCII + Latin-1, decodificação UTF-8), usada por `cv_text`/`cv_text_w`
  para o menu, o teclado e o contador de FPS.
- `overlay_layout.h` — o layout do menu interno (abas Gamepad/Game,
  itens, modos Handheld/Docked) e do teclado (44 teclas). Este arquivo
  está byte-idêntico ao do upstream — ele está aqui porque é o arquivo
  com que o host da v21 foi buildado, não porque foi alterado.

## Limite honesto

O host da v21 (SHA `0578c08f…`, 1.712.184 bytes) foi buildado a partir
de um fork-local do nx2android que carrega outras alterações não
commitadas de outros projetos. Em particular, as correções de áudio
do Setsuna — o catch-up da v5 em `nxnative/dm/host/hle_audren.c`
(+473 linhas, "service catch-up (I am Setsuna, v5)") e o diagnóstico
de stall em `nxnative/dm/host/hle_audio.c` — **não** estão neste
repositório. Um host buildado só com estes 3 arquivos sobre um
nx2android limpo **não** reproduz o hash da v21 e pode não ter o
catch-up de áudio. O caminho acima é o documentado, não um build
provado.

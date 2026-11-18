# Host — arquivos alterados para I am Setsuna

Estes 5 arquivos são as alterações deste jogo sobre o nx2android.
Os 3 primeiros vão em `nxnative/dm/android/`; os 2 de áudio vão em
`nxnative/dm/host/`. Copie-os por cima dos originais no seu checkout
do nx2android (faça backup dos originais antes) e compile como
descrito em `../BUILD.md`:

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
- `hle_audren.c` — o serviço de áudio audren:u para SDK 1 (o jogo é
  SDK 1_3_1 e não carrega código de renderer no lado do servidor):
  o mixer do UpdateData (vozes PCM16 → estéreo), o estado do
  renderer, e o catch-up da v5 — uma thread a cada 5 ms re-renderiza
  240 amostras do último UpdateData e alimenta a saída PCM, porque o
  relógio de áudio do jogo roda a ~0,37x do tempo real. Inclui também
  a detecção de renderer server-side (`audren_server_side()`) e o
  parâmetro de 0x34 bytes do SDK 1.
- `hle_audio.c` — o diagnóstico de stall da v7 no watchdog de áudio
  (200 ms sem callback do dispositivo) + o sinal de hold que o
  catch-up lê (`dm_audio_is_suspended()`): com o jogo pausado, em
  menu ou em entrada de texto, o catch-up congela a voz em vez de
  avançar o áudio segundos à frente.

## Limite honesto

O host da v21 (SHA `0578c08f…`, 1.712.184 bytes) foi buildado a partir
de um fork-local do nx2android que carrega outras alterações não
commitadas de outros projetos. As correções de áudio do Setsuna
**estão** neste repositório desde o segundo commit: o catch-up da v5
em `hle_audren.c` e o diagnóstico de stall da v7 em `hle_audio.c`.

Mas um host buildado só com estes 5 arquivos sobre um nx2android
limpo **ainda não** reproduz o hash da v21, e o build a partir do repo
segue não provado. Falta a cola que liga os dois arquivos de áudio ao
resto do host: as declarações em `nxnative/dm/host/dm.h` (que no fork
está misturada com os gates de observação de outros projetos — Guns,
Gore and Cannoli 2 e Astroneer — e por isso não pode ser copiada
verbatim), o despachante IPC `hle_audren_sf.c` (arquivo novo no fork,
145 linhas, sem o qual o jogo nunca alcança o serviço), o gancho
`audren:u` em `hle_sf.c` e as notas de evento em `front_android.c` /
`hle_oe.c`. O caminho acima é o documentado, não um build provado.

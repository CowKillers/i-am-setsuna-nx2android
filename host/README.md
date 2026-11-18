# Host — arquivos alterados para I am Setsuna

Estes arquivos são as alterações deste jogo sobre o nx2android:
6 arquivos completos + 6 patches. Os 3 primeiros vão em
`nxnative/dm/android/`; os 3 de áudio vão em `nxnative/dm/host/`; os
patches aplicam por cima dos originais no seu checkout do nx2android
(faça backup dos originais antes). A partir da raiz do checkout:

```sh
cp <este-repo>/host/gfx_vk_present.c <este-repo>/host/font_aa.h \
  <este-repo>/host/overlay_layout.h nxnative/dm/android/
cp <este-repo>/host/hle_audren.c <este-repo>/host/hle_audio.c \
  <este-repo>/host/hle_audren_sf.c nxnative/dm/host/
for p in dm.h hle_sf.c hle_oe.c hle_thread.c hle_svc.c front_android.c; do
  git apply <este-repo>/host/$p.setsuna.patch
done
```

E compile como descrito em `../BUILD.md`:

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
- `hle_audren_sf.c` — o despachante IPC do serviço audren:u (arquivo
  novo no fork, 145 linhas, lido e copiado verbatim): o manager
  responde OpenAudioRenderer e GetAudioRendererWorkBufferSize; o
  renderer (objeto 2) responde os comandos IAudioRenderer e mistura
  o UpdateData do SDK 1 em PCM estéreo. Numa sessão simples o renderer
  volta como move handle (uma sessão própria servindo o objeto 2);
  num domínio, como o objeto 2 do domínio.
- `dm.h.setsuna.patch` — as declarações do serviço audren:u em
  `nxnative/dm/host/dm.h`: `hle_create_event`,
  `hle_create_transfer_memory`, `hle_signal_event`, `hle_clear_event`,
  as `audren_svc_*`, `audren_sf_handle` e `sf_new_session_obj`, mais
  o estado de suspensão de áudio (`dm_audio_is_suspended`, `DM_AU_EV_*`,
  `dm_audio_note_event`). Só os hunks do Setsuna: os hunks de outros
  projetos foram excluídos (ver o limite honesto abaixo).
- `hle_sf.c.setsuna.patch` — o gancho `audren:u` em
  `nxnative/dm/host/hle_sf.c`: sessões com objeto (0 = manager,
  2 = renderer), `"audren:u"` na lista de serviços servidos,
  `sf_new_session_obj`, e o despacho para `audren_sf_handle` em
  `h_sf_Send`.
- `hle_oe.c.setsuna.patch` — a nota de evento de foco em
  `nxnative/dm/host/hle_oe.c`: `dm_audio_note_event(DM_AU_EV_FOCUS)`
  no `dm_focus_set`.
- `hle_thread.c.setsuna.patch` — as implementações que o serviço
  audren:u chama, em `nxnative/dm/host/hle_thread.c`:
  `hle_create_event`, `hle_create_transfer_memory` (SVC 0x15, tipo
  `O_TMEM` no CloseHandle), `hle_signal_event` e `hle_clear_event`.
  Sem este patch o host não linka.
- `hle_svc.c.setsuna.patch` — o caso SVC 0x15 CreateTransferMemory em
  `nxnative/dm/host/hle_svc.c`: um handle real para o work buffer do
  audren (sem ele o placeholder deixava o slot de saída sem
  inicializar e o jogo fechava um handle qualquer da pilha — log
  da v3).
- `front_android.c.setsuna.patch` — só o hunk do `hold_sync` em
  `nxnative/dm/android/front_android.c`: `g_held_kind` + as notas
  `dm_audio_note_event` (menu / entrada de texto / fundo / primeiro
  plano). Os outros 9 hunks do diff do fork pertencem a outro projeto
  e foram excluídos de propósito: o overlay já tem fallbacks fracos
  para as funções de mira por toque (ver `gfx_vk_present.c`), então
  o host compila sem eles e a mira por toque fica desligada por
  padrão — o launcher do Setsuna não a usa.

## Limite honesto

O host da v21 (SHA `0578c08f…`, 1.712.184 bytes) foi buildado a partir
de um fork-local do nx2android que carrega outras alterações não
commitadas de outros projetos. A cola do áudio **está** neste
repositório desde o terceiro commit: o despachante `hle_audren_sf.c`
e os 6 patches acima — verificados por aplicação num checkout limpo
do upstream (`git apply` sem rejeições, e os arquivos resultantes
contêm as declarações e os ganchos do audren; os 4 arquivos de patch
integral saem byte-idênticos aos do fork).

Mas o build a partir do repo segue **não provado**, e o host
resultante pode diferir do hash da v21: o fork também carrega
alterações de outros projetos que alimentam o build do host e ficam
fora daqui de propósito (a linha do Astroneer em `main.c`, os gates
de observação em `dm.h`, os arquivos de diagnóstico
`hle_astro_*.c` / `hle_ggc2_diag.c`, o tema e a mira por toque do
outro projeto em `front_android.c`). O caminho acima é o
documentado, não um build provado.

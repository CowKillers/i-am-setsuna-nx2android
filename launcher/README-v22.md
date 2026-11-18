# Launcher v22 — I am Setsuna

**ÚNICA mudança vs v21:** esconder a pílula de navegação por gestos do
Android por cima do gameplay.

## Causa raiz (provada no código)

- O menu do launcher (`LauncherActivity`) já usa modo imersivo
  (`setImmersive()` com `IMMERSIVE_STICKY | FULLSCREEN |
  HIDE_NAVIGATION | LAYOUT_STABLE | LAYOUT_HIDE_NAVIGATION |
  LAYOUT_FULLSCREEN`, reaplicado em `onWindowFocusChanged`).
- Mas o jogo não roda no launcher: roda na stock
  `android.app.NativeActivity` (manifest + intent do Jogar), que nunca
  pedia modo imersivo. O host nativo (`front_android.c:941`) só põe
  `AWINDOW_FLAG_KEEP_SCREEN_ON | AWINDOW_FLAG_FULLSCREEN` — esconde a
  barra de status, não a barra de gestos. Por isso a pílula branca
  aparecia nas fotos do S23 por cima do gameplay, e não no menu.
- Medido nas 3 fotos dele (18/11): faixa idêntica nas três, ~350px de
  largura centrada, 11 linhas de altura, ~15px acima da borda inferior
  — o desenho exato da pílula do sistema, não do jogo. A opção
  "Dicas de gestos" não existe no aparelho dele, então o fix é em
  código.

## O fix (só launcher, sem rebuild do host)

- Novo `android/src/org/nx2a/launcher/GameActivity.java`:
  `GameActivity extends android.app.NativeActivity` com o mesmo
  `setImmersive()` do menu, aplicado em `onCreate` (após o super),
  `onWindowFocusChanged` (quando tem foco) e `onResume`.
- `AndroidManifest.xml`: a segunda activity passa de
  `android.app.NativeActivity` para `org.nx2a.launcher.GameActivity`
  (todo o resto igual: label, ícone, orientação, configChanges,
  exported=false, meta-data `android.app.lib_name` = `dmhost`).
- `LauncherActivity.java`: o intent do Jogar aponta para
  `org.nx2a.launcher.GameActivity`; comentário do cabeçalho atualizado.
- `build.sh`: javac passa a compilar os dois `.java` (a GameActivity só
  era referenciada por string no intent, então o javac não a puxaria
  sozinho); OUT passa a `IAmSetsuna-launcher-v22.apk`.

Nada mais muda: mesmo host da v20/v21 (SHA `0578c08f…`), mesmos 866
shaders, mesma tradução PT-BR, mesmo Donate, mesmo fundo, mesmo Turnip.

## Teste (aceite)

1. Instalar por cima da v21 (mesma assinatura, versionCode maior).
2. Abrir o jogo (Jogar) e tirar um print do gameplay: **a pílula
   branca tem que ter sumido** — a cena tem que chegar limpa até a
   borda inferior.
3. Menu, Configuração, Donate e resto: inalterados.

Sem foto de prévia: isso é comportamento do sistema, não layout — a
prova é o print do aparelho dele depois de instalar.

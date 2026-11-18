# I am Setsuna no Android (nx2android)

Port experimental de **I am Setsuna** (Switch, update 1.1.0) para Android,
feito com o [nx2android](https://github.com/shirai91/nx2android) — o código
arm64 do jogo roda nativamente no celular, com o host substituindo os
serviços do Switch e o NVN reproduzido em Vulkan, sem emulador.

> **Experimental.** Este repositório não contém o jogo, as chaves, o APK,
> os dados, os shaders, a tradução nem a arte oficial. Não há release.

## O que funciona

- O jogo abre e chega ao gameplay, com saves funcionando.
- **60 fps no Handheld**, provado por log (frame p50 16,66 ms, 0 underruns,
  drift de áudio constante).
- Tradução PT-BR embutida no launcher (o jogo abre em português).
- **866 shaders** traduzidos estaticamente (bundle `shaders.dmsv`) — ver
  `docs/shaders.md`.
- Launcher próprio (v21): Jogar, Configuração com submenus aninhados
  (Jogo, Vídeo, Tradução, Controle, Controles, Sobre), editor de layout,
  Turnip 8g2 embutido, "Exportar log" no Sobre e **Donate via Pix**
  (pílula no menu principal; com a sua chave, veja `BUILD.md`).
- Menu interno (pausa) e teclado do jogo no tema do launcher, com fonte
  legível (sem a fonte pixelada original do host).
- Driver de GPU: sistema ou Turnip 8g2 embutido (via adrenotools).

## O que NÃO está aqui (e nunca vai estar)

- Arquivos do jogo: dump (NSP), RomFS, `shaders.dmsv`, data pack, APKs.
- Chaves: `prod.keys`, `title.keys` ou qualquer chave de assinatura.
- Tradução PT-BR: os arquivos traduzidos não estão aqui; traga os seus.
- Arte oficial do jogo: o fundo (`launcher_bg.jpg`), o logo
  (`logo_title.png`) e o ícone (`ic_launcher.png`) são **placeholders
  neutros** — troque pela sua própria arte antes de compilar.
- Chave Pix: o diálogo Donate traz os textos `SUA-CHAVE-PIX-AQUI`,
  `SEU-PAYLOAD-PIX-AQUI` e `SEU NOME AQUI` como placeholders, e nenhum
  QR Code vem no repo. Coloque a sua chave e gere o seu QR (veja
  `BUILD.md`).

Traga seu próprio dump e importe pelo launcher. Assine com sua própria
keystore (veja `BUILD.md`).

## Como compilar

Veja `BUILD.md`. Resumo: copie o perfil (`profile/`), compile o host
(`host/`), depois o launcher (`launcher/`). A próxima build precisa de
`versionCode` maior que **1794915277** (launcher v21): este repo já marca
esse valor. Trocar a chave de assinatura exige desinstalação limpa com
backup de `files/` antes.

## Estado atual (17/11/2026, honesto)

- Port experimental construído com o porter do nx2android
  (`porter port --experimental --debug`): perfil provisório adivinhado pelo
  survey, porque o jogo **não tem perfil** no nx2android para o title
  `0100849000BDA000`.
- O jogo é Unity 5.5.0p1, IL2CPP, NVN, SDK 1_3_1, versão 1.1.0.
- O survey traduziu **866 de 866 shaders** (706 soltos + 160 no
  `StreamingAssets/NX/assets.cpk`).
- APK gerado: pacote `org.nx2a.t0100849000bda000`, versionName 1.1.0,
  launcher v21, host da v20.
- **Pendente de prova no aparelho:** o playthrough completo com zero
  capturas novas de shader (a única prova de "100%"), e o teste da v20/v21
  no aparelho (submenus aninhados, menu interno, teclado e o diálogo Donate).
  Não trate os 866 como provados em runtime até esse playthrough existir.

## Créditos e aviso

- Ferramenta: [nx2android](https://github.com/shirai91/nx2android), port Android
  do [nxnative](https://github.com/seedds/nxnative).
- Uso pessoal, sem distribuição e sem arquivos do jogo neste repositório.

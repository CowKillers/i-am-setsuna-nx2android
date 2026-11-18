# Como compilar

## Pré-requisitos

- Android SDK com NDK `27.2.12479018`, build-tools `36.1.0` e
  plataforma `android-36`.
- JDK 17 (`javac`, `keytool`, `apksigner` do build-tools — o `apksigner`
  aqui só verifica; assinar com ele quebra o alinhamento do dex, veja
  a seção Assinatura).
- Python 3 (com Pillow, para `gen_assets_v13.py`).
- Um checkout do [nx2android](https://github.com/shirai91/nx2android)
  (o código do host em si **não** está neste repositório, só os 3 arquivos
  alterados para este jogo em `host/`).
- Bibliotecas adrenotools (veja abaixo).
- O seu próprio dump do jogo (NSP 1.1.0) e as suas `prod.keys` — fora
  do repositório, sempre.

Variáveis usadas pelo `launcher/build.sh` (todas com padrão sensato,
exceto `ADRENO_DIR`, que você precisa apontar):

| Variável | Padrão | Uso |
| --- | --- | --- |
| `ANDROID_SDK_ROOT` | `$HOME/Android/Sdk` | SDK/NDK/build-tools |
| `JDK` | `/usr/lib/jvm/java-17-openjdk-amd64` | JDK 17 |
| `HOST_SO` | `launcher/../host/out/libdmhost.so` | host buildado |
| `EXPECTED_HOST_SHA` | — | se definido, o host é conferido |
| `ADRENO_DIR` | — | libs adrenotools (obrigatório) |
| `VERCODE` | `date +%s` | versionCode do APK |

## 1. Perfil

Copie o perfil deste repositório para dentro da pasta de build do porter,
antes de rodar o port:

```
<pasta-do-jogo>/0100849000BDA000_build/profile/0100849000BDA000/
```

Com o `game.tsv` já lá, o porter **reaproveita** este perfil em vez de
fazer o survey de novo. O perfil só registra a versão **1.1.0**; se o seu
dump for outra versão, outra região ou outro update, o porter recusa o
build — nesse caso, apague a pasta do perfil copiada e rode o port de
novo: ele faz o survey de novo e gera um perfil novo para aquela versão.

O port extrai o seu dump, reescreve os módulos, traduz os 866 shaders e
gera o data pack na pasta do jogo. Nada disso volta para este repositório.

## 2. Host

Os 3 arquivos em `host/` são as alterações deste jogo sobre o
`nxnative/dm/android/` do nx2android (veja `host/README.md` para o que
cada um faz). Copie-os por cima dos originais no seu checkout do
nx2android (faça backup dos originais antes), e compile:

```sh
DM_GAMES_DIR=<este-repo>/profile \
  python3 <nx2android>/nxnative/dm/android/build.py \
  --game 0100849000BDA000 --ndk "$ANDROID_SDK_ROOT/ndk/27.2.12479018" \
  --lib --release --out <este-repo>/host/out
```

Isso gera `host/out/libdmhost.so`. O `DM_GAMES_DIR` aponta para a pasta
`profile/` (a pasta-mãe do perfil, não o perfil em si).

> Limite honesto: o host da v21 (SHA `0578c08f…`) foi buildado a partir
> de um fork-local do nx2android que carrega outras alterações não
> commitadas de outros projetos — e as correções de áudio do Setsuna
> (`hle_audren.c`, catch-up da v5; `hle_audio.c`, diagnóstico de stall)
> **não** estão neste repositório. O build acima é o caminho documentado,
> não um build provado: o host resultante pode diferir do hash da v21.
> Veja `host/README.md`.

## 3. Adrenotools

O launcher injeta 5 `.so` do adrenotools no APK. Consiga-os compilando
o adrenotools (ou copiando de um checkout funcional) e aponte:

```sh
export ADRENO_DIR=/caminho/do/adrenotools-build
# esperado: $ADRENO_DIR/libadrenotools.so
#           $ADRENO_DIR/src/hook/{libhook_impl,libmain_hook,libfile_redirect_hook,libgsl_alloc_hook}.so
```

## 4. Launcher

```sh
cd launcher
export ADRENO_DIR=/caminho/do/adrenotools-build
bash build.sh
```

O script compila os recursos (`aapt2`), o Java (`javac`/`d8`), injeta
dex, host, adrenotools, Turnip 8g2, tema e fundo, realinha os `.so`
para 16 KB e roda `check.py` (gates: `.so` alinhados, dex com a Activity,
tema e Turnip presentes; com `EXPECTED_HOST_SHA` definido, o SHA do host
também é conferido). O resultado é `build/aligned.apk` — **ainda sem
assinatura** (veja a próxima seção).

O que o script **não** injeta porque não vem no repo (e o launcher
funciona sem eles): `shaders.dmsv` (vem do data pack, importe pelo
launcher), a tradução PT-BR (`android/assets/ptbr/`, se você tiver os
arquivos) e o QR do Pix (`android/assets/donate_pix_qr.png`, se você
gerar o seu).

### Arte

O fundo, o logo e o ícone no repo são placeholders neutros. Para usar a
sua própria arte antes de compilar:

```sh
SETSUNA_BG_SRC=/caminho/da/sua/arte-1920x1080.jpg \
SETSUNA_LOGO_SRC=/caminho/da/sua/foto-com-o-titulo.jpg \
  python3 launcher/gen_assets_v13.py
```

Isso gera `launcher_bg.jpg` (1920x1080) e `logo_title.png` (transparente)
em `launcher/android/`. Sem as variáveis, os placeholders ficam.

### Donate via Pix

O diálogo Donate abre com placeholders (`SUA-CHAVE-PIX-AQUI`,
`SEU-PAYLOAD-PIX-AQUI`, `SEU NOME AQUI`). Para usar a sua chave:

1. Em `launcher/android/src/org/nx2a/launcher/LauncherActivity.java`,
   troque os valores de `PIX_KEY` (sua chave Pix — de preferência uma
   chave aleatória, não CPF, telefone ou e-mail) e de `PIX_PAYLOAD`
   (o código copia-e-cola BR Code do seu banco, com o CRC16 certo).
2. Gere o QR Code a partir do payload (qualquer gerador de QR que aceite
   o texto cru) e salve como `launcher/android/assets/donate_pix_qr.png`
   (PNG quadrado, preto sobre branco).
3. Recompile. Sem o PNG, o diálogo abre só com o texto — não quebra nada.

A chave fica visível dentro do APK: qualquer pessoa com o APK consegue
ver. Por isso a recomendação é a chave aleatória (revogável).

## 5. Assinatura

Crie sua própria keystore uma vez (nunca commite ela nem suas senhas):

```sh
keytool -genkeypair -keystore ~/setsuna-debug.keystore \
  -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000
```

**Não assine com `apksigner`**: ele reescreve os headers locais e
acrescenta o extra field de alinhamento nas entradas STORED, deslocando
os dados e quebrando o alinhamento do `classes.dex` (%4) que o
`realign.py` estabeleceu. Assine com o signer v2 do `mkapk.py` do seu
checkout do nx2android, que só acrescenta o bloco de assinatura. Salve
o driver abaixo como `sign.py` (fora do repo) e rode:

```sh
MKAPK=<nx2android>/nxnative/dm/android/apk/mkapk.py \
KEYSTORE=~/setsuna-debug.keystore \
  python3 sign.py launcher/build/aligned.apk IAmSetsuna-launcher.apk
```

```python
#!/usr/bin/env python3
"""Assina um APK alinhado com APK Signature Scheme v2 (mkapk.py).
Uso: MKAPK=... KEYSTORE=... python3 sign.py <aligned.apk> <signed.apk>
"""
import base64, hashlib, importlib.util, os, re, secrets, shutil, struct, subprocess, sys, tempfile

MKAPK = os.environ["MKAPK"]
KS = os.path.expanduser(os.environ["KEYSTORE"])
ALIAS = os.environ.get("KEY_ALIAS", "androiddebugkey")
PW = os.environ.get("KEYSTORE_PASS", "android")

def der_read(data, off):
    tag, ln = data[off], data[off + 1]; off += 2
    if ln & 0x80:
        n = ln & 0x7F; ln = int.from_bytes(data[off:off + n], "big"); off += n
    return tag, data[off:off + ln], off + ln

def parse_rsa(pem):
    m = re.search(r"-----BEGIN PRIVATE KEY-----\s*([A-Za-z0-9+/=\s]+)-----END PRIVATE KEY-----", pem)
    der = base64.b64decode("".join(m.group(1).split()))
    _, body, _ = der_read(der, 0)
    _, _, o2 = der_read(body, 0); _, _, o3 = der_read(body, o2)
    _, octet, _ = der_read(body, o3); _, kb, _ = der_read(octet, 0)
    ints, o = [], 0
    while o < len(kb):
        t, v, o = der_read(kb, o); ints.append(int.from_bytes(v, "big"))
    return {"n": ints[1], "e": ints[2], "d": ints[3]}

def parse_cert(pem):
    m = re.search(r"-----BEGIN CERTIFICATE-----\s*([A-Za-z0-9+/=\s]+)-----END CERTIFICATE-----", pem)
    return base64.b64decode("".join(m.group(1).split()))

def run(cmd):
    subprocess.run(cmd, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)

src, dst = sys.argv[1], sys.argv[2]
data = open(src, "rb").read()
assert data[-22:-18] == b"PK\x05\x06", "sem EOCD"
_sig, _d1, _d2, _n1, _n, cd_size, cd_off, _c = struct.unpack("<IHHHHIIH", data[-22:])
zip_entries, cd, eocd = data[:cd_off], data[cd_off:cd_off + cd_size], data[-22:]
tmp = tempfile.mkdtemp(prefix="setsuna-sign-"); os.chmod(tmp, 0o700)
try:
    p12 = os.path.join(tmp, "k.p12"); pw = secrets.token_hex(16)
    run(["keytool", "-importkeystore", "-srckeystore", KS, "-srcstorepass", PW,
         "-srcalias", ALIAS, "-destkeystore", p12, "-deststoretype", "PKCS12",
         "-deststorepass", pw, "-destalias", ALIAS])
    run(["openssl", "pkcs12", "-in", p12, "-passin", "pass:" + pw,
         "-nocerts", "-nodes", "-out", os.path.join(tmp, "k.pem")])
    run(["openssl", "pkcs12", "-in", p12, "-passin", "pass:" + pw,
         "-nokeys", "-clcerts", "-out", os.path.join(tmp, "c.pem")])
    key = parse_rsa(open(os.path.join(tmp, "k.pem")).read())
    crt = parse_cert(open(os.path.join(tmp, "c.pem")).read())
    print("signer cert SHA-256: %s" % hashlib.sha256(crt).hexdigest())
finally:
    shutil.rmtree(tmp, ignore_errors=True)
old = sys.argv; sys.argv = ["mkapk.py", "dummy.so", "dummy.apk"]
try:
    spec = importlib.util.spec_from_file_location("mkapk_signer", MKAPK)
    mod = importlib.util.module_from_spec(spec); spec.loader.exec_module(mod)
finally:
    sys.argv = old
open(dst, "wb").write(mod.sign_v2(zip_entries, cd, eocd, key, crt))
print("%s: %d bytes" % (dst, os.path.getsize(dst)))
```

Confira a assinatura só com verificação (nunca re-assine com apksigner):

```sh
"$ANDROID_SDK_ROOT/build-tools/36.1.0/apksigner" verify --print-certs IAmSetsuna-launcher.apk
```

## Regras de versão e assinatura

- `versionCode` só aumenta. Último usado: **1794915277** (launcher v21).
  Este repo já marca esse valor; a próxima build precisa de um maior
  (`VERCODE` padrão é o timestamp Unix do build).
- APK assinado com outra chave NÃO instala por cima do anterior:
  faça backup de `files/` (dados e saves) e desinstale limpo antes.

## Instalar e importar dados

Instale o APK, abra o launcher e use Importar jogo com a pasta (ou zip)
do seu próprio dump contendo `RomFS/`, `shaders.dmsv` e `env.txt`.
Sem esses arquivos o botão Jogar fica bloqueado. O driver padrão é o
Turnip 8g2 embutido.

O log fica em `Android/data/org.nx2a.t0100849000bda000/files/log.txt`
(a cada novo lançamento, o anterior vira `log.prev.txt` — copie antes de
abrir o jogo de novo).

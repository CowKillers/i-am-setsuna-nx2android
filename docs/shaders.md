# Shaders — 866 estáticos, sem compilação em runtime

O jogo **não compila shaders em runtime**: ele instala binários NVN
pré-compilados. Os shaders vivem em dois lugares, e os dois foram
extraídos e traduzidos offline:

| Fonte | Shaders |
| --- | --- |
| Arquivos soltos no RomFS (chaves `706`) | 706 |
| `StreamingAssets/NX/assets.cpk` (2.686 AssetBundles) | 160 |
| **Total no bundle** | **866** (VS 363 + PS 503) |

O log do tradutor fecha a conta: **"866 of 866, failed: {}"**. O mesmo
variante existe nas duas formas compiladas (o controle solto tem 2.104 B,
o do CPK tem 2.001 B, com o código NVN byte-idêntico) — a chave é o hash
de controle + código, então as chaves diferem entre as formas. Todas as
16 chaves capturadas em runtime numa sessão real são da forma CPK
(`nctl = 2001`).

O loader: dispatch NVN em `main+0x10678f8`, construtor de programa GPU
em `main+0x106adcc`. A reconciliação fechou: 1.476 objetos Shader
exatos, zero nomes de mapa sem objeto solto.

## O que falta provar

**866 é o alvo estático provado, não a prova em runtime.** A única
prova de "100%" é um playthrough completo com zero capturas novas de
shader e `skipped (no program) 0` no log. Esse playthrough ainda não
existe. Se uma 17ª chave aparecer em runtime, ela entra no bundle pela
via conhecida (captura → tradução → adição) e vira v12.1.

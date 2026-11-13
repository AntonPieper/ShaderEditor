# Bundled fonts

The editor fonts live in `app/src/main/res/font/`. Each font choice is a
font family (`<name>_family.xml`) of a regular (400) and a bold (700) file,
so asking for bold gives the font's real bold weight.

Licence texts are in `licenses/fonts/`. Every bold file is the unmodified
file from the project's tagged release on GitHub.

## Regular files

These came with commit d48d74fa ("Add popular fonts and ligatures"). Their
source was not recorded; they are unhinted builds of the versions below.
They are kept unchanged so existing text renders exactly as before.

| File | Version (`name` table) | Matches release | SHA-256 |
| --- | --- | --- | --- |
| `fira_code.ttf` | 1.208 | outlines and advances of 1.207 Regular | `952b0d5a1d329fa4c8229aa97143ed9ff3f239f1b4bd84e4c14b6f4f1ccdd705` |
| `ibm_plex_mono.ttf` | 2.000 | outlines of v2.0.0 Regular (subset) | `4e5360e6435ca0fda23ac8bde947e30a690395bedc6a0e0f9b360aceeb490851` |
| `jetbrains_mono.ttf` | 2.211 | outlines and advances of v2.210 Regular | `bcad6628b88dabe2c7128a8d8da7517d30d291d2196c242d6bd064803b6bc81b` |
| `roboto_mono.ttf` | 2.000985 (2015, Apache 2.0) | metrics of v3.001 Regular | `e3284818a33743fb9474240557d229c111ec2b584d14913e5d32af7b74487458` |
| `source_code_pro.ttf` | 1.017 | outlines of 1.017R Regular | `2d1bcd5038af1287746b7b1d1a4db65f091b3e9811428076d43a73121893922d` |

## Bold files

| File | Source | Release tag | Licence | SHA-256 |
| --- | --- | --- | --- | --- |
| `fira_code_bold.ttf` | [tonsky/FiraCode](https://github.com/tonsky/FiraCode/releases/tag/1.207), `FiraCode_1.207.zip` → `ttf/FiraCode-Bold.ttf` | `1.207` | OFL 1.1 | `78347832c01fc526df6c03622989397bc702862245822e20213b1890ede7e5c7` |
| `ibm_plex_mono_bold.ttf` | [IBM/plex](https://github.com/IBM/plex/releases/tag/v2.0.0), `TrueType.zip` → `TrueType/IBM-Plex-Mono/IBMPlexMono-Bold.ttf` | `v2.0.0` | OFL 1.1, Reserved Font Name "Plex" | `0959685fb3dfd306525a6d3ee11489726ddec1a5797750abdc8844e34a20fe01` |
| `jetbrains_mono_bold.ttf` | [JetBrains/JetBrainsMono](https://github.com/JetBrains/JetBrainsMono/releases/tag/v2.210), `JetBrainsMono-2.210.zip` → `fonts/ttf/JetBrainsMono-Bold.ttf` | `v2.210` | OFL 1.1 | `d02e658030761319ede19b3438e5c2bd2deadf7947847978aea1e17b1edc72bd` |
| `roboto_mono_bold.ttf` | [googlefonts/RobotoMono](https://github.com/googlefonts/RobotoMono/tree/v3.001/fonts/ttf), `fonts/ttf/RobotoMono-Bold.ttf` at the tag (the release has no assets) | `v3.001` | OFL 1.1 | `3ecf35e5e87accc7578b605d1f5f0bc30d88b195d6807bec8a0c57f6aa95c4db` |
| `source_code_pro_bold.ttf` | [adobe-fonts/source-code-pro](https://github.com/adobe-fonts/source-code-pro/releases/tag/1.017R), `source-code-pro-1.017R.zip` → `TTF/SourceCodePro-Bold.ttf` | `1.017R` | OFL 1.1, Reserved Font Name "Source" | `80922933add5aaad3870ade70f2a414a57327a7990ef935345ecb7a1d579e3c0` |

Each bold has the same advance width as its regular for every ASCII
character, except Roboto Mono, whose 2015 regular gives `-` and `f`
1230 instead of 1229 units per 2048 (0.05 % of the advance).

## Licence files

| File | Source |
| --- | --- |
| `licenses/fonts/fira_code.txt` | `LICENSE` at tonsky/FiraCode `1.207` |
| `licenses/fonts/ibm_plex_mono.txt` | `LICENSE.txt` at IBM/plex `v2.0.0` |
| `licenses/fonts/jetbrains_mono.txt` | `OFL.txt` at JetBrains/JetBrainsMono `v2.210` |
| `licenses/fonts/roboto_mono.txt` | `OFL.txt` at googlefonts/RobotoMono `v3.001` |
| `licenses/fonts/roboto_mono_regular_apache-2.0.txt` | <https://www.apache.org/licenses/LICENSE-2.0.txt>, for the 2015 regular |
| `licenses/fonts/source_code_pro.txt` | `LICENSE.txt` at adobe-fonts/source-code-pro `1.017R` |

The OFL lets the licence travel in the font's own metadata; every bold file
carries it in `name` IDs 13 and 14.

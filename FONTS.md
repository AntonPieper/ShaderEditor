# Bundled fonts

The editor fonts live in `app/src/main/res/font/`. Each font choice is a
font family (`<name>_family.xml`) of a regular (400) and a bold (700) file,
so asking for bold gives the font's real bold weight.

Every file is the unmodified static TrueType file from the project's
latest tagged release on GitHub; regular and bold come from the same
release. Licence texts are in `licenses/fonts/`.

| File | Source | Release tag | Licence | SHA-256 |
| --- | --- | --- | --- | --- |
| `fira_code.ttf` | [tonsky/FiraCode](https://github.com/tonsky/FiraCode/releases/tag/6.2), `Fira_Code_v6.2.zip` → `ttf/FiraCode-Regular.ttf` | `6.2` | OFL 1.1 | `5992ab9640e2df491b2f609467b1de60e8bc39b2c28db184342a0592d98f6117` |
| `fira_code_bold.ttf` | [tonsky/FiraCode](https://github.com/tonsky/FiraCode/releases/tag/6.2), `Fira_Code_v6.2.zip` → `ttf/FiraCode-Bold.ttf` | `6.2` | OFL 1.1 | `41f6554e845e2f5b70adad3950122334b866aac436793b7742ade600067701be` |
| `ibm_plex_mono.ttf` | [IBM/plex](https://github.com/IBM/plex/releases/tag/%40ibm%2Fplex-mono%402.5.0), `ibm-plex-mono.zip` → `ibm-plex-mono/fonts/complete/ttf/IBMPlexMono-Regular.ttf` | `@ibm/plex-mono@2.5.0` | OFL 1.1, Reserved Font Name "Plex" | `7c6fbddca4b700be918f5f6183d9bd4464fa427fe435f0b480d77fe2bb8c5a43` |
| `ibm_plex_mono_bold.ttf` | [IBM/plex](https://github.com/IBM/plex/releases/tag/%40ibm%2Fplex-mono%402.5.0), `ibm-plex-mono.zip` → `ibm-plex-mono/fonts/complete/ttf/IBMPlexMono-Bold.ttf` | `@ibm/plex-mono@2.5.0` | OFL 1.1, Reserved Font Name "Plex" | `74e5eedcfa4596497d34e19023cabdabd3a8c852b903007a5654a59591a72ffb` |
| `jetbrains_mono.ttf` | [JetBrains/JetBrainsMono](https://github.com/JetBrains/JetBrainsMono/releases/tag/v2.304), `JetBrainsMono-2.304.zip` → `fonts/ttf/JetBrainsMono-Regular.ttf` | `v2.304` | OFL 1.1 | `a0bf60ef0f83c5ed4d7a75d45838548b1f6873372dfac88f71804491898d138f` |
| `jetbrains_mono_bold.ttf` | [JetBrains/JetBrainsMono](https://github.com/JetBrains/JetBrainsMono/releases/tag/v2.304), `JetBrainsMono-2.304.zip` → `fonts/ttf/JetBrainsMono-Bold.ttf` | `v2.304` | OFL 1.1 | `5590990c82e097397517f275f430af4546e1c45cff408bde4255dad142479dcb` |
| `roboto_mono.ttf` | [googlefonts/RobotoMono](https://github.com/googlefonts/RobotoMono/tree/v3.001/fonts/ttf), `fonts/ttf/RobotoMono-Regular.ttf` at the tag (the release has no assets) | `v3.001` | OFL 1.1 | `af0bff7599c3df3831755c16e39b3c496df74b8c8d8a1161b14dc8461be17cb4` |
| `roboto_mono_bold.ttf` | [googlefonts/RobotoMono](https://github.com/googlefonts/RobotoMono/tree/v3.001/fonts/ttf), `fonts/ttf/RobotoMono-Bold.ttf` at the tag (the release has no assets) | `v3.001` | OFL 1.1 | `3ecf35e5e87accc7578b605d1f5f0bc30d88b195d6807bec8a0c57f6aa95c4db` |
| `source_code_pro.ttf` | [adobe-fonts/source-code-pro](https://github.com/adobe-fonts/source-code-pro/releases/tag/2.042R-u%2F1.062R-i%2F1.026R-vf), `TTF-source-code-pro-2.042R-u_1.062R-i.zip` → `TTF/SourceCodePro-Regular.ttf` | `2.042R-u/1.062R-i/1.026R-vf` | OFL 1.1, Reserved Font Name "Source" | `74bd80d3e42a08517cd7e1108ba3d86f2da29ac0f3065be95e0357956ab9db37` |
| `source_code_pro_bold.ttf` | [adobe-fonts/source-code-pro](https://github.com/adobe-fonts/source-code-pro/releases/tag/2.042R-u%2F1.062R-i%2F1.026R-vf), `TTF-source-code-pro-2.042R-u_1.062R-i.zip` → `TTF/SourceCodePro-Bold.ttf` | `2.042R-u/1.062R-i/1.026R-vf` | OFL 1.1, Reserved Font Name "Source" | `b2095e0d657e6d28dc32444a9dacabab0c9241d0bf39d96371756cc9bdbc3a5f` |

In each family the bold has the same advance width as the regular for
every printable ASCII character, and with contextual alternates (`calt`)
off, each of these characters shapes to one glyph at that advance.
Ligatures are `calt` in Fira Code and JetBrains Mono; the other three
have none.

## Licence files

| File | Source |
| --- | --- |
| `licenses/fonts/fira_code.txt` | `LICENSE` at tonsky/FiraCode `6.2` |
| `licenses/fonts/ibm_plex_mono.txt` | `ibm-plex-mono/LICENSE.txt` in `ibm-plex-mono.zip` of IBM/plex `@ibm/plex-mono@2.5.0` |
| `licenses/fonts/jetbrains_mono.txt` | `OFL.txt` at JetBrains/JetBrainsMono `v2.304` |
| `licenses/fonts/roboto_mono.txt` | `OFL.txt` at googlefonts/RobotoMono `v3.001` |
| `licenses/fonts/source_code_pro.txt` | `LICENSE.md` at adobe-fonts/source-code-pro `2.042R-u/1.062R-i/1.026R-vf` |

The OFL lets the licence travel in the font's own metadata; every file
carries it in `name` IDs 13 and 14. "Plex" and "Source" are Reserved Font
Names: a modified copy of those fonts must not use them.

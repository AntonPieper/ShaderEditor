# Bundled fonts

The editor fonts live in `app/src/main/res/font/`. Each font choice is one
regular (400) file; the app draws code in that face only.

Every file is the unmodified static TrueType file from the project's
latest tagged release on GitHub. Licence texts are in `licenses/fonts/`
and ship in the APK (see "Licence files").

| File | Source | Release tag | Licence | Bytes | SHA-256 |
| --- | --- | --- | --- | --- | --- |
| `fira_code.ttf` | [tonsky/FiraCode](https://github.com/tonsky/FiraCode/releases/tag/6.2), `Fira_Code_v6.2.zip` → `ttf/FiraCode-Regular.ttf` | `6.2` | OFL 1.1 | 289,624 | `5992ab9640e2df491b2f609467b1de60e8bc39b2c28db184342a0592d98f6117` |
| `ibm_plex_mono.ttf` | [IBM/plex](https://github.com/IBM/plex/releases/tag/%40ibm%2Fplex-mono%402.5.0), `ibm-plex-mono.zip` → `ibm-plex-mono/fonts/complete/ttf/IBMPlexMono-Regular.ttf` | `@ibm/plex-mono@2.5.0` | OFL 1.1, Reserved Font Name "Plex" | 173,052 | `7c6fbddca4b700be918f5f6183d9bd4464fa427fe435f0b480d77fe2bb8c5a43` |
| `jetbrains_mono.ttf` | [JetBrains/JetBrainsMono](https://github.com/JetBrains/JetBrainsMono/releases/tag/v2.304), `JetBrainsMono-2.304.zip` → `fonts/ttf/JetBrainsMono-Regular.ttf` | `v2.304` | OFL 1.1 | 273,900 | `a0bf60ef0f83c5ed4d7a75d45838548b1f6873372dfac88f71804491898d138f` |
| `roboto_mono.ttf` | [googlefonts/RobotoMono](https://github.com/googlefonts/RobotoMono/tree/v3.001/fonts/ttf), `fonts/ttf/RobotoMono-Regular.ttf` at the tag (the release has no assets) | `v3.001` | OFL 1.1 | 125,748 | `af0bff7599c3df3831755c16e39b3c496df74b8c8d8a1161b14dc8461be17cb4` |
| `source_code_pro.ttf` | [adobe-fonts/source-code-pro](https://github.com/adobe-fonts/source-code-pro/releases/tag/2.042R-u%2F1.062R-i%2F1.026R-vf), `TTF-source-code-pro-2.042R-u_1.062R-i.zip` → `TTF/SourceCodePro-Regular.ttf` | `2.042R-u/1.062R-i/1.026R-vf` | OFL 1.1, Reserved Font Name "Source" | 210,312 | `74bd80d3e42a08517cd7e1108ba3d86f2da29ac0f3065be95e0357956ab9db37` |

With contextual alternates (`calt`) off, each printable ASCII character
shapes to one glyph at the font's single advance width.
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

The OFL asks that each copy of a font carries its copyright notice and the
licence. `licenses/fonts/` is the single source: the build packages that
folder as `assets/fonts/<font>.txt` in the APK (`sourceSets` in
`app/build.gradle.kts`), and Settings > Font licences shows each text under
the font's name. Each file starts with the font's copyright line. "Plex" and
"Source" are Reserved Font Names: a modified copy of those fonts must not
use them.

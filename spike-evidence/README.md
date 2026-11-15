# THROWAWAY: evidence of the code-knowledge study

This branch is a spike. It is not a pull request and must not be merged.
It backs the numbers of `.foreman/proposals/code-knowledge.md` (2026-11-15):
what the editor can know about GLSL at a position, how fast, and through
which seam.

The prototype is `app/src/test/java/de/markusfisch/android/shadereditor/spike/`
(pure Java over the production lexer) and the test file
`app/src/test/resources/spike/hostile.frag`. Nothing outside those folders
and this one is changed.

| File here | Made by | Holds |
| --- | --- | --- |
| `baseline.txt` | `spike.Baseline` | What today's analysis and a keypress cost, with the production classes |
| `cost.txt` | `spike.Report` | Options A (stretched scan), B (parser, names, types), C (one function) at 1,000, 5,000 and 50,000 lines: time, allocation, memory kept |
| `quality.txt` | `spike.Quality` | What A and B resolve on real code, broken code, typing character by character, random damage, the smallest examples |
| `seam.txt` | `spike.Demo` | The proposed seam answering on a bundled example, on a manifest, across 50 files; what a question costs; the table of built-ins |
| `check.txt` | `spike.Check` | Parse problems and typed expressions for every bundled file and the hostile file |

To run them again (desktop JVM, from the repository root):

```sh
mkdir -p /tmp/spike-out
javac -nowarn -implicit:class -d /tmp/spike-out -sourcepath app/src/main/java \
	$(ls app/src/test/java/de/markusfisch/android/shadereditor/spike/*.java | grep -v SpikeTest)
java -Xmx2g -cp /tmp/spike-out de.markusfisch.android.shadereditor.spike.Report
```

and the same for `Baseline`, `Quality`, `Demo` and `Check`. The four JUnit
tests run with `./gradlew :app:testDebugUnitTest --tests "*spike*"`.

Machine: Apple M1 Pro, 10 cores, 16 GB, OpenJDK 17.0.21, load average 1.6
to 3.3. Nothing ran on a phone or an emulator.

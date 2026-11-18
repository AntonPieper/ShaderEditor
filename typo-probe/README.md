# typo-probe: the drivers' own messages on realistic typos

Measuring tool of the typo experiment (report: `.foreman/proposals/typo-experiment.md` in the owner's checkout). It is not part of the app. It has its own applicationId, `de.markusfisch.android.shadereditor.typoprobe`, and asks for no permissions.

## Run it on a device (the one command)

```
./gradlew :typo-probe:probe -Pserial=<adb serial>
```

On Windows: `gradlew.bat :typo-probe:probe -Pserial=<adb serial>`. `adb devices` lists the serials.

The command builds the probe APK, installs it on that device only (`adb -s <serial>`), runs it with `am instrument`, pulls the result to `typo-probe/results/<serial>.jsonl` and **uninstalls the probe again**, also when the run fails. It does not read or touch the app or its data. It took about 3 s on an emulator.

What the probe does: it creates an EGL pbuffer context (ES 3 where the device has it, else ES 2). Then, for every `corpus/texts/<id>.frag` with its `<id>.vert`, it does what the app's `GlDevice` does: it compiles the vertex shader and then the fragment shader, and it links them with `position` bound to 0. It writes one header line (manufacturer, model, Android version, GL_VENDOR / GL_RENDERER / GL_VERSION / GLSL version, EGL) and one JSON line per text: `id`, `vertexOk`, `vertexLog`, `fragmentOk`, `fragmentLog`, `linkOk`, `linkLog`, and `micros` (vertex, fragment and link times). Lines come in sorted id order. Apart from the times, the output is deterministic.

## The corpus

`corpus/` is generated and committed, so another machine needs only the command above.

- `corpus/index.jsonl` has one line per program. Each line gives the project, entry and program file, the GLSL version, the app's own errors before the driver (`appErrors`; `compiled` is false when the app stops before the driver), and `map` (file:line for every line of the assembled text, `""` for a line the engine generates). For a mutant it also gives the typo: `kind`, `sub`, `file`, `line`, `col`, `before` → `after`, and `targets` (the lines that count as right).
- `corpus/texts/<id>.frag|.vert` hold exactly what the engine would hand to `glShaderSource`. `<id>.src` is the mutated source file.
- `o-NN` are the 47 unchanged programs, a baseline that every driver must accept. `Kkk-NN` are the 264 mutants: 12 kinds, 22 of each.
- `probeOnly: true` (45 texts: every unterminated comment, every missing `#endif`, and one `uniform` typo after `#pragma se input`) marks a text where the app's own check stops the build before the driver. The driver still gets the original assembled program with the same edit made line for line (a deleted line becomes an empty one), so that its message can be measured too.

## Regenerating the corpus (Mac, JVM)

The generator calls the app's own pipeline: `Preparation.prepare` and then `PlanSource.of`.

```
./gradlew :app:compileDebugUnitTestJavaWithJavac
B=app/build/intermediates
CP=$B/javac/debug/compileDebugJavaWithJavac/classes:$B/compile_and_runtime_r_class_jar/debug/processDebugResources/R.jar:<androidx annotation-jvm jar>:<sdk>/platforms/android-37.0/android.jar
javac -d /tmp/gen -cp $CP typo-probe/jvm/src/de/markusfisch/android/shadereditor/opengl/*.java
java -cp /tmp/gen:$CP de.markusfisch.android.shadereditor.opengl.TypoGenerate \
	app/src/debug/assets/examples app/src/test/resources/shadertoy typo-probe/corpus
```

The seed is fixed (2026), so the same tree gives the same corpus.

## Results so far

- `emulator-5554` and `emulator-5556` (Mac): the emulator's GLES translator on Apple M1 Pro, GL 4.1 over Metal.
- `homepc-api37` (ES 3) and `homepc-api23` (ES 2) (Windows): the same translator on an NVIDIA RTX 3080, driver 621.24.
- `phone-s938b`: the owner's Samsung SM-S938B, Qualcomm Adreno 830, driver V@0800.64.8, Android 17. Its driver compiles the shader itself. The tool named the file after the WiFi serial (`10.20.121.115_5555.jsonl`) and it was renamed.

The first error line is the same on all four for every typo; the logs differ only in later lines and in warnings. The four emulator files' messages come from the emulator's translator, not from a phone's driver; the phone's come from Qualcomm's.

## Analysis (Mac)

- `typo-probe/jvm/analyse.sh` runs over every file in `results/`. For each text it records three more voices: the app's own `DriverLog` on the driver's log, the strict reader of the one-front-end spike (`jvm/src/exact/`), and Mesa's `glsl_compiler`. It maps every voice's line back to file and line and scores it, writing `analysis/voices.jsonl`.
- Then `python3 -I typo-probe/jvm/py/keys.py typo-probe`, `tabulate.py` and `decision.py` (same argument) apply the blind message scores in `analysis/scores.tsv` and write `analysis/scored.jsonl` and `analysis/tables.md`.
- A message that has no score yet stops `tabulate.py`. Score it blind first.
- The report as of this commit is in `analysis/typo-experiment-report.md`.

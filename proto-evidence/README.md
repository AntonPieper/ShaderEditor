# THROWAWAY PROTOTYPE: editor core

Branch `proto/editor-core`. Not for merging, never a pull request. It exists
to back the numbers in the editor proposal (`.foreman/proposals/editor.md`).

- `app/src/main/java/.../editor/` `Text`, `Document`, `LineStates`: the
  document model, undo and incremental line states. Pure Java.
- `syntax/GlslLexer.lexLine`, `syntax/LineTokens`: the per-line restart.
- `editor/proto/`: `ProtoEditorView` (draws visible lines, own
  InputConnection), `TimedEditText` (the platform EditText with the same
  clocks), `ProtoActivity` and launcher aliases, one per configuration.
- `app/src/test/java/.../editor/EditorBench.java`: checks and JVM timings.

JVM: `javac -d out app/src/main/java/.../syntax/*.java
app/src/main/java/.../editor/{Text,Document,LineStates}.java
app/src/test/java/.../editor/EditorBench.java` then
`java -cp out de.markusfisch.android.shadereditor.editor.EditorBench
<a 5,000-line glsl file>`.

Device: two local edits in `app/build.gradle.kts`, not committed: the debug
build gets `applicationIdSuffix = ".edproto"` and `isDebuggable = false`,
and LeakCanary is left out (it refuses to start in a non-debuggable build).
After installing: `cmd package compile -m speed -f <package>`. Each alias
in the manifest is one run; results go to logcat under the tag `EdProto`.

`proto-evidence/` holds the output the proposal quotes.

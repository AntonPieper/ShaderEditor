## 6. Device checks

There are 19 checks in 3 batches, and each batch fits one emulator session. Each check says which builds to compare, the steps, the Android version and what "fixed" looks like.

Builds, the same for every check:
- **old X.Y.Z**: `gh release download X.Y.Z --repo markusfisch/ShaderEditor -p app-release.apk -D old-X.Y.Z`, package `de.markusfisch.android.shadereditor` (called OLD below).
- **master**: a debug build of upstream master. In a worktree at `upstream/master`, run `./gradlew assembleDebug`. Package `de.markusfisch.android.shadereditor.debug` (called DBG below). It installs beside OLD.
- **fork**: a debug build of `origin/integration/project-mode`, built locally or taken from the CI pre-release `latest-integration-project-mode`. It has the same package as DBG, so uninstall the other one first. Its debug build runs project mode: the shaders live in a library.
- For checks that type a shader: make a new shader in the drawer (+), select all, delete, then type or paste the text. The emulator clipboard works with `adb shell cmd clipboard set-primary "…"` on Android 13+ when the agent-device helper is in use; otherwise use the app's Paste.
- Android versions: Android 16 everywhere. Android 6 too where the check says so (memory: `android-emulator-launch.md`, `agent-device-text-editing.md` for driving the editor).

Every check also fills in this result line: `Kn: OLD <what happened> / NEW <what happened> / verdict fixed | not fixed | not reproducible`, with a screenshot of each.


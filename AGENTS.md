# ShaderEditor – Agent Guidelines

Core philosophy and non-obvious constraints. Everything else is discoverable from the code.

---

## Architecture

- **No new global singletons.** `MainActivity` decomposes into scoped manager classes (`activity/managers/`). Follow the same pattern.
- **GL thread isolation.** Never touch UI from inside `ShaderRenderer`. Hand results back via `queueEvent()` or `OnRendererListener`.
- **Lazy sensor registration.** Hardware listeners (`hardware/`) are registered/unregistered by `ShaderRenderer` based on whether the shader source references the corresponding uniform constant. New sensors must follow this pattern — never register unconditionally.
- **Shared editor state.** `UndoRedo.EditHistory` is process-wide (held in `ShaderEditorApp`) so undo stacks survive fragment recreations. Preferences are always accessed via `ShaderEditorApp.preferences`.
- **Shader metadata triad.** Any new shader metadata touches `DatabaseContract` (bump schema version) + `ShaderDao` + `ShaderManager` + the relevant UI surface — all four, always.
- **No Compose/Navigation.** Fragments are added manually via `AbstractSubsequentActivity` helpers. Do not introduce Jetpack Navigation or Compose.
- **`BatteryLevelReceiver` lifecycle.** `ShaderWallpaperService` enables/disables the receiver in the manifest at runtime to avoid wakeups when no wallpaper is active.

## Constraints

- **Indentation: tabs.** EditorConfig is authoritative (`CONTRIBUTING.md`).
- **GLSL must be ASCII-only.** `ShaderView.removeNonAscii()` enforces this before passing source to OpenGL — don't bypass it.
- **DAO scope only.** All DB interactions stay inside DAOs using try-with-resources. Never cache a raw `SQLiteDatabase`.
- **Off-main-thread reads.** DAO reads use a single-thread executor + main-thread `Handler` (see `ShaderListManager`). Don't do DB or file I/O on the main thread — StrictMode is on in debug builds.
- **File import/export is API < 29 only.** Scoped storage makes legacy file paths unavailable on Android 10+; the preference entries are hidden at runtime for newer versions.
- **Translations.** Every new string resource must be added for all supported languages under `res/values-*/`.

## Adding a new uniform/sensor (checklist)

1. Constant + uniform location + value assignment in `ShaderRenderer`.
2. Listener in `hardware/`, registered/unregistered by `ShaderRenderer` on source parse.
3. Entry in `PresetUniformAdapter`.
4. Documentation in `FAQ.md` and relevant string resources (all languages).

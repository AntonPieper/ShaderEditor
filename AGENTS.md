# ShaderEditor

## Scope
- Single Android app module under `app/`, written in Java 17 with a classic View/Fragment/manager structure.
- Prefer fitting changes into the existing custom view + fragment + manager approach instead of introducing a second UI architecture.

## Architecture
- The embedded preview, fullscreen preview, and live wallpaper are different entry points over the same rendering stack. Keep rendering behavior shared unless a divergence is clearly intentional.
- Process-wide state is intentionally centralized in `ShaderEditorApp` and the database singleton. Avoid adding new global singletons and use application context for long-lived objects.
- Long-lived user configuration should go through `ShaderEditorApp.preferences`, not scattered `SharedPreferences` reads.
- Prefer responsibility-specific classes over generic `*Utils` helpers.

## Threading And Boundaries
- Renderer code must stay off the UI layer. Do not touch views from GL/rendering code; hand results back via callbacks or queued work.
- Database, bitmap, and file operations do not belong on the main thread. Follow the existing single-thread executor + main-thread handoff pattern.
- Keep SQLite access inside DAOs via `DataSource`; do not spread raw database access through activities/fragments.
- Any executor, listener, or other long-lived worker needs explicit lifecycle ownership and cleanup. Thread leaks and forgotten unregister/shutdown paths are a recurring failure mode here.

## Extension Rules
- Rendering-facing features should be wired end-to-end through shared infrastructure, not patched into only one surface.
- Uniform/sensor/media features are demand-driven: keep listeners, permissions, and expensive resources gated by actual shader usage and platform availability.
- New persisted fields are cross-cutting changes: update schema migration, DAO CRUD, and every UI path that loads, saves, or displays that data.
- New preferences should be declared centrally in `Preferences` and consumed through the cached preference wrapper.
- Be defensive with user-provided shaders, imported assets, and stale persisted state. Prefer safe fallback or feature disablement over crash-prone assumptions.

## Platform Constraints
- Do not bypass the shader-source sanitizing path; GL compilation must stay resilient to pasted non-ASCII characters.
- Storage behavior is Android-version dependent. Do not assume direct filesystem access works everywhere; preserve the SAF/legacy split.
- Wallpaper behavior must preserve low-battery render-mode handling.
- Bitmap and texture code is memory-sensitive; release/recycle aggressively.

## Project Conventions
- `EditorConfig` is authoritative: tabs for indentation, no manual alignment, otherwise standard Android Studio formatting.
- Always use braces, even for single-line conditionals.
- Keep changes scoped and local. Prefer small manager/helper classes over broad rewrites.
- When changing strings, keep all shipped locales in sync: `values`, `values-ru`, and `values-uk`.

## Validation
- Automated test coverage is minimal. After behavior changes, manually verify the affected editor flow and any impacted preview, wallpaper, import/export, permission, or sensor path.

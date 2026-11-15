## Problem

Rotating the screen while the list of errors (the sheet behind the error button) is open crashes:

`Fragment$InstantiationException: ... ErrorListModal: could not find Fragment constructor`

The same happens on any other recreate: a split-screen resize, "Use device colours", a process death and return. `ErrorListModal` had only constructors with arguments, so the system could not make it again. Upstream has the same defect.

## Change

- **No-argument constructor:** `ErrorListModal` now has one and holds no errors of its own. It asks a small `Host` (`ErrorListModal.Host`) for the errors to list, for whether the picture is the last that worked, and for what a chosen error does.
- **Host:** `EditorFragment` is the host and shows the sheet in its child fragment manager, so the system makes the sheet again with the new `EditorFragment`. The debug-only `ProjectPreviewActivity` is a host too.
- **Never stale:** a sheet made again after a recreate waits hidden for the host's next errors. It shows them, with the "Showing the last picture that worked" line when that applies, or closes when the new build has none. It never lists errors from before the recreate.
- **Live:** the host refreshes an open sheet whenever its errors are set or cleared, and the sheet closes when they become empty.

## Other fragments checked

Every other class that extends `Fragment`, `BottomSheetDialogFragment` or a preference dialog fragment has only the implicit no-argument constructor:

`AbstractSamplerPropertiesFragment`, `AddUniformPageFragment`, `CropImageFragment`, `CubeMapFragment`, `EditorFragment`, `FontLicencesSheet`, `ListPreferenceDialogFragment`, `LoadSampleFragment`, `MaterialPreferenceDialogFragmentCompat`, `PreferencesFragment`, `Sampler2dPropertiesFragment`, `SamplerCubePropertiesFragment`, `ShaderListPreferenceDialogFragment`, `TextureParametersFragment`, `TextureViewFragment`, `UniformPagesFragment`, `UniformPresetPageFragment`, `UniformSampler2dPageFragment`.

The image sheet (`ImageSheet`), the New sheet (`NewSheet`) and `RenameDialog` are plain dialogs, not fragments, so the system never re-creates them.

## Checks

- `./gradlew :app:assembleDebug :app:lintDebug :app:testDebugUnitTest` passes: 1570 tests, lint 0 errors and 15 warnings, none in these files.
- No JVM test: the sheet is a fragment, and the project has no Robolectric.
- Not yet checked on a device. Steps for another machine:
  1. Build this branch with `./gradlew :app:assembleDebug` and install it. Open a folder file that runs, for example a library shader.
  2. Type `x` on an empty line after the last `}`. The error button appears; tap it. The list shows `…:N: 'x' : syntax error`.
  3. Rotate to landscape with the list open.
     - Before: the app closes with the `InstantiationException` above.
     - After: no crash. Within a moment the list is open again in landscape, with the same error, now from the build after the rotation.
  4. Tap the error: the editor goes to its line and the list closes.
  5. Open the list again and rotate back to portrait: it comes back in portrait. Close it and undo the `x` (⋮ → Undo): the error button disappears, so there is no list to open.
  6. A failing edit of a file that ran before shows "Showing the last picture that worked" as the list's first line. Rotate with that list open: it comes back with that line.
  7. Developer options → "Don't keep activities" on. Open the list, press Home, return from Recents: no crash, and the list comes back with the current errors. Turn the option off afterwards.
  8. The same on Android 6 (API 23).

🤖 Generated with [Claude Code](https://claude.com/claude-code)


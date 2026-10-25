# Folder-first authoring UX

This revision incorporates the user's native-picker/Termux feedback. A shader is an editable file in an ordinary folder. ShaderEditor exposes its own user files as one location in Android's native file picker. The same open, save, create, and copy actions work in that location and other document providers.

Keep the existing Android editor and preview styling. This is a workflow simplification, not a visual redesign or a replacement file manager.

## 1. One filesystem workflow

The main actions are **New**, **Open folder**, **Save**, and **Run**. The existing recent/library surface gives quick access to previously opened shaders/folders and existing DB shaders. A path/location subtitle is enough where needed; do not introduce managed, linked, imported, virtual, or isolated project modes in the UI.

ShaderEditor publishes a `DocumentsProvider` root named **ShaderEditor** containing user-created folders, GLSL, assets, and other user documents. Other editors and Android's Files UI can open and edit those same files. Provider access does not expose the DB, preferences, draft journals, caches, or installed wallpaper snapshots.

Use the native picker to choose folders, standalone documents, new locations, and external resources. The in-app file list only navigates the currently open folder. Do not build a second device-wide storage browser. Android/provider restrictions still apply: accept any directory the picker grants, including empty folders and folders with unrelated files. A manifest or `main.glsl` is never required just to open/browse a folder. Handle read-only folders without rejecting the open action.

Opening a folder edits its files in place. There is no `import versus link` question and no automatic copy into private storage. Expose copying as the familiar explicit **Save a copy** action. Archive opening extracts to a user-chosen folder; it is an operation, not a persistent project type.

## 2. Create in the default location or anywhere else

Global **New** uses one small creation sheet:

- Name, prefilled with an unused `Untitled` name.
- Location, defaulting to the ShaderEditor provider's user-files root.
- A **Change** location action that opens Android's native directory picker.
- **Create**.

Show the resulting location succinctly, for example `ShaderEditor / Untitled / main.glsl`. Create that named folder and a valid template `main.glsl`, then focus the editor. No project-type, renderer, manifest, or storage-mode choices. Location selection can choose a different subfolder of ShaderEditor or any other writable provider folder with the identical flow. Remember the last explicitly selected creation parent as a convenience; first use defaults to ShaderEditor, and an unavailable remembered parent returns to that default with a visible location row.

Inside an open folder, **New file** creates a named source/include file there without adding a nested project folder. Keep optional templates in that action rather than a required wizard. Existing content is never overwritten silently. Suggest an unused name automatically.

The provider location details should state that ShaderEditor's own app files are removed when the app is uninstalled. Do not add a repeated warning/confirmation to every create or save action. Files selected in other providers retain those providers' lifecycle behavior.

## 3. Open and decide what runs

Open folder uses `ACTION_OPEN_DOCUMENT_TREE`. It loads the file list lazily and uses a valid manifest entry when one exists. Without a manifest, restore the last explicitly run file for that folder if still present; otherwise use `main.glsl` if present. Otherwise open the folder without a render target and show **Open a shader, then Run**. Do not recursively scan/compile every GLSL file to guess an entry.

**Open file** remains available through the system picker and incoming Android open/share intents. It opens the document directly. Reuse a known containing folder grant when available. A lone document grant is enough for a standalone shader; ask for its folder only if a sibling dependency is actually needed. The message is **Choose this shader's folder to load its other files**, with one **Choose folder** action. There is no conversion/import wizard for this permission boundary.

Keep the running shader visible in the preview title, for example `Preview: main.glsl`. Editing an include or another pass does not switch it. The usual Run button rebuilds the selected running shader. **Run this file** is a file/editor action that selects a different GLSL file as the running entry, remembered locally for that folder. It does not silently rewrite `shader-project.json`.

If a manifest has an unsupported format or invalid content, opening/browsing still succeeds; rendering gives a located config error rather than ignoring that file. A changed explicit manifest entry is honored on reopening/refresh unless the user explicitly selects a different file for the current session. **Set default shader** is an optional advanced action that writes the minimal manifest when the user wants a portable default. Most users never need it.

A single-file open has its selected file as the running entry. For a folder with no target, opening a GLSL file can focus the file; Run then selects it. Failed compilation of an include-only file explains that it has no entry function. No hidden heuristics that classify arbitrary source text as a complete shader.

## 4. Keep common authoring actions short

| Action | Normal flow |
| --- | --- |
| Add texture | Pick image/library item; insert generated annotation and uniform; return to code |
| Add shader input | Select a shader from the open folder or create one; insert input; return to code |
| Add include | Select/create a source file; insert its relative include |
| Change sampling | Edit optional settings beside the input or in its contextual properties |
| View pass output | Choose a reachable output in the preview selector; keep the same running graph |

Texture insertion uses an existing project-local file in place when it is within the selected folder. An outside/library resource is copied once into `assets/`; imported unsupported image formats are converted to PNG. Suggest a collision-free name and valid unused variable, then insert:

```glsl
#pragma se input "assets/paper.png"
uniform sampler2D paper;
```

Calculate the path relative to the edited file. Do not ask users to type the same name into several fields. Default filter/wrap values are omitted; advanced controls edit the same annotation, never hidden settings. Explicit replacement is the only operation that overwrites a colliding resource. A local same-file selection must not produce unnecessary copies.

Shader inputs default to current frame. Previous frame is a contextual option, not a compulsory decision dialog. A newly created file executes only after it is referenced. Shared include files hold code, not input/target metadata; explain the restriction at the relevant annotation error rather than on every include action.

Output inspection shows a texture from the existing frame/graph and never runs a separate simulation. Hide output selection when only the final image is relevant. Restart is a secondary preview action. Keep per-file text/undo/selection independent.

## 5. Saving and outside edits without storage modes

**Save** means save changed files to their current locations, consistently across ShaderEditor's provider and other providers. Keep existing auto-save/save-on-run preferences; give new native folders the same configured behavior instead of another location-specific policy. Do not introduce a separate save mode just because the provider authority changes.

Use a small unsaved dot and concise failure feedback. Do not expose snapshot IDs, store types, or revision machinery. Retain recoverable drafts internally. Switching files must preserve drafts; opening a folder must not copy its entire contents into a hidden workspace.

Refresh visible files/dependencies on return from an external editor or explicit Refresh. Reload clean files automatically. Ask only when both the user and external storage changed the same edited file. Explain the conflict with **Reload from file** and **Keep my edits**, with **Save a copy** available for preserving both. Keeping edits is not an automatic overwrite until the user's next explicit save/confirmed save action. Preserve recoverable drafts through any choice; no automatic merge engine.

For read-only/unavailable storage, keep editing locally and offer **Save a copy** or **Choose folder again** as appropriate. Save a copy uses the same creation/destination picker flow. It copies the selected running shader and its dependencies to a new folder; it does not silently duplicate the entire opened directory. After successful copying, switch to the copy and show its location. If there is no running shader, save the selected document; extra files require explicit selection.

Handle failed writes honestly: retain unsaved files and report them, with Retry. Do not claim multi-file atomic save for all providers. The implementation may keep a draft journal and in-memory build snapshot, but those mechanisms are not a user-visible project lifecycle.

## 6. Errors, files, and preview

A syntax error leaves the last working image visible. Show **Preview needs fixing** or **Showing last successful build** and an error count; selecting an error opens its actual source file. Save remains available for invalid source. No successful build means a clear error/empty preview, not an unexplained black rectangle. Ignore stale diagnostic completions.

Rename a file in the open-folder list using one name field. Update recognized literal references where the bounded scan succeeds, including unused source files; preserve unresolved edits as drafts if saving fails. Normal safe renames do not need a second review wizard. Explain incomplete/unsupported updates rather than silently breaking known references. Do not build a generic cross-provider move/refactoring tool in v1; users can use the native file explorer for general file management and refresh afterward.

Delete uses the provider capability and warns about known dependent files. Removing an item from Recents only removes the shortcut. Do not equate it with deleting a folder. Native Files operations are ordinary outside edits; they go through the same refresh/conflict handling.

## 7. Share the shader, not unrelated folder contents

Default **Share** packages the selected running shader and the transitive closure of its shader inputs, literal includes, and local images. A dependency-free standalone shader can be shared as a `.glsl` file. A multi-file shader is shared as a ZIP retaining relative paths. If its selected entry differs from the folder's portable default, generate/adjust the entry manifest IN THE COPY. Do not modify the source folder to share it.

Invalid/unsupported manifests can be preserved in an explicitly incomplete source export; do not silently replace them with a supported manifest.

Never default to exporting the whole opened folder: it may be Documents, a repository, or another broad user directory. An optional **Include more files** action lets users explicitly add unused experiments or documentation. General whole-folder copying belongs to the native file explorer; no second project-export mode is necessary.

Sharing does not require successful GLSL compilation. Static dependency collection usually works despite shader errors. If malformed/missing references prevent complete collection, show which files are unresolved and allow explicit extras or sharing the incomplete sources as-is. Do not claim such a package is self-contained. Exclude app internals and VCS administration directories.

Opening a ZIP offers a destination using the same location picker, extracts into a new folder without overwriting unrelated files, then opens that folder normally. A portable-default override exists only in the extracted manifest; there is no imported-project mode.

## 8. Wallpaper and existing shaders

**Set wallpaper** applies the current valid shader. Internally store a durable dependency snapshot, so an unavailable source provider cannot break the wallpaper after the editor closes. Keep this mechanism invisible. Current invalid edits cannot silently apply an older preview. Later edits use **Update wallpaper**; no live-sync preference or provider-dependent behavior in v1. Keep separately persisted legacy/runtime behavior where supported.

Existing DB shaders remain available through the current library and fixed adapter. A normal open/run must not force a migration dialog. When a user requests files or a project-only feature, use **Save as files** with the same Name/Location flow. Preserve the original. Report unsupported conversion settings only when they occur. Do not display internal terms such as compatibility mode or virtual one-file project in normal navigation.

## 9. Acceptance and implementation restraint

Use the existing Android components and localization/accessibility conventions. Long filenames retain a way to see the full path, focus returns to the editor after insertion, and save/error state is not color-only. Do not add an onboarding tour for concepts that the actions already explain.

Native authoring may remain behind one temporary development gate until S9 validation. No public v1 claim before rendering, provider access, file recovery, and wallpaper cases work. Provider failures and conflicts still need correct handling; simplify their frequency and presentation instead of hiding data loss.

Primary references: [Android document-provider guide](https://developer.android.com/guide/topics/providers/create-document-provider), [Android document access](https://developer.android.com/training/data-storage/shared/documents-files), and [Termux's DocumentsProvider](https://github.com/termux/termux-app/blob/master/app/src/main/java/com/termux/filepicker/TermuxDocumentsProvider.java). Use Termux as a UX/API reference, not code to copy verbatim.

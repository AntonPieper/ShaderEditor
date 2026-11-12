# Folder-first authoring UX

This revision incorporates the user's native-picker/Termux feedback. A shader is an editable file in an ordinary folder. ShaderEditor exposes its own user files as one location in Android's native file picker. The same open, save, create, and copy actions work in that location and other document providers.

Keep the existing Android editor and preview styling. This is a workflow simplification, not a replacement file manager. The one part that is redesigned is the drawer: it becomes the place where folders and files are browsed. NAVIGATION.md is that design and decides wherever this document speaks of the drawer, the file list or the toolbar.

## 1. One filesystem workflow

The main actions are **New**, **Open folder**, **Save**, and **Run**. The drawer is one column: **Folders** (the library **Shaders**, **Examples**, and what was opened before), then a folder, then its files. A tap goes in, the arrow goes out; there is no tree. A path/location subtitle is enough where needed; do not introduce managed, linked, imported, virtual, or isolated project modes in the UI.

ShaderEditor publishes a `DocumentsProvider` root named **ShaderEditor** containing user-created folders, GLSL, assets, and other user documents. Other editors and Android's Files UI can open and edit those same files. Provider access does not expose the DB, preferences, draft journals, caches, or installed wallpaper snapshots.

**Examples** is a folder at the top of the drawer, and the menu entry is a shortcut to it. It holds the bundled examples, one read-only project inside the app. Tapping an example opens and runs it in place; edits stay local until the user chooses Save a copy, exactly as for any read-only folder (section 5). An app update replaces the bundled examples and never touches a copy. EXAMPLES.md describes the set and its sections.

Use the native picker to choose folders, standalone documents, new locations, and external resources. The drawer shows the folders the user has opened and, inside each, its files. It never shows storage the user has not granted; choosing a location stays with Android's picker. Do not build a second device-wide storage browser. Android/provider restrictions still apply: accept any directory the picker grants, including empty folders and folders with unrelated files. A manifest or `main.glsl` is never required just to open/browse a folder. Handle read-only folders without rejecting the open action.

Opening a folder edits its files in place. There is no `import versus link` question and no automatic copy into private storage. Expose copying as the familiar explicit **Save a copy** action. Archive opening extracts to a user-chosen folder; it is an operation, not a persistent project type.

## 2. Create in the default location or anywhere else

Global **New** uses one small creation sheet:

- Name, prefilled with an unused `Untitled` name.
- Location, defaulting to the folder the drawer shows.
- A **Change** location action that opens Android's native directory picker.
- **Create**.

Location defaults to the folder the drawer shows, and on first use to the library folder `ShaderEditor / Shaders` (section 8). Show the result succinctly, for example `ShaderEditor / Shaders / Untitled.glsl`. Create that file from a valid template, make it the running shader, and focus the editor. A new shader is a file, not a folder, so shaders in one folder can share textures and included code. No project-type, renderer, manifest, or storage-mode choices. A separate folder is one step away: Change opens the picker, which can create one, and any writable provider folder works with the identical flow. Remember the last explicitly selected creation parent as a convenience; first use defaults to ShaderEditor, and an unavailable remembered parent returns to that default with a visible location row.

**New** in the drawer offers **Shader** or **Empty file** in the same sheet. A shader has `main` and runs; an empty file opens beside what runs, for include files and passes. Keep optional templates in that action rather than a required wizard. Existing content is never overwritten silently. Suggest an unused name automatically.

The provider location details should state that ShaderEditor's own app files are removed when the app is uninstalled. Do not add a repeated warning/confirmation to every create or save action. Files selected in other providers retain those providers' lifecycle behavior.

## 3. Open and decide what runs

Open folder uses `ACTION_OPEN_DOCUMENT_TREE`. It shows the folder in the drawer, listed lazily. Entering a folder, from the picker or from the drawer, starts nothing: looking is free. The shader that the one rule in SPEC 1 picks (the file last run in that folder, else a valid manifest entry, else `main.glsl`) is the first row, and a tap opens and runs it. If none applies, the folder says **Nothing here runs yet. Tap a shader to open and run it.** Do not recursively scan/compile every GLSL file to guess an entry.

**Open file** remains available through the system picker and incoming Android open/share intents. It opens the document directly as a one-file project, which is enough for a standalone shader. When the shader references another file, the diagnostic says **Open this shader's folder to load its other files**, with one **Open folder** action. That folder then opens like any other; the same document is selected in it when the provider can identify it, and otherwise the user picks it from the file list. Unsaved edits to the document carry over. There is no conversion/import wizard for this permission boundary, and no attempt to prove which granted folder a lone document belongs to.

The toolbar's title is the file in the editor. Its second line names the running shader when that is another file, then the folder, for example `▶ main.glsl · aurora`; the frame rate sits in the quality control. A tap on a file that can run and that the running shader does not use opens it and selects it as the running entry, remembered locally for that folder. A tap on a file the running shader uses, an include or another pass, only opens it; the shader keeps running. The usual Run button rebuilds the running shader. **Run this file** does the same selection on request for any file, in the row's menu. It does not silently rewrite `shader-project.json`.

If a manifest has an unsupported format or invalid content, opening/browsing still succeeds; rendering gives a located config error rather than ignoring that file. The remembered Run this file choice wins over the manifest entry, on every open and refresh, until the user picks another file or uses Set default shader. **Set default shader** is an optional advanced action that writes the minimal manifest when the user wants a portable default, and clears the remembered choice. Most users never need it.

A single-file open has its selected file as the running shader. A file can run when its own text defines `main`. That is read from the one file being opened, never from a scan; a file without it opens for editing only. Failed compilation of an include-only file explains that it has no entry function. There is no other heuristic that classifies source text as a complete shader.

## 4. Keep common authoring actions short

| Action | Normal flow |
| --- | --- |
| Add texture | Pick image/library item; insert generated annotation and uniform; return to code |
| Add shader input | Select a shader from the open folder or create one; insert input; return to code |
| Add include | Select/create a source file, or pick a bundled library file, which is copied to `lib/` at the project root (an existing file is reused, never overwritten); insert its relative include |
| Change sampling | Edit optional settings beside the input or in its contextual properties |
| View pass output | Choose a reachable output in the preview selector; keep the same running graph |

Texture insertion uses an existing project-local file in place when it is within the selected folder. An outside/library resource is copied once into `textures/` at the project root; imported unsupported image formats are converted to PNG. Suggest a collision-free name and valid unused variable, then insert:

```glsl
#pragma se input "textures/paper.png"
uniform sampler2D paper;
```

Calculate the path relative to the edited file. Do not ask users to type the same name into several fields. Default option values are omitted; advanced controls edit the same directive, never hidden settings. The cubemap picker assembles six faces into the strip of SPEC 4. Explicit replacement is the only operation that overwrites a colliding resource. A local same-file selection must not produce unnecessary copies.

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

Rename a file from its row's menu in the drawer using one name field. Rename does not rewrite references in v1. When the running shader depends on the file, say so before renaming; afterwards the ordinary missing-file diagnostics point at each reference that needs the new name. Finding every reference would mean reading every file in a folder that may be a whole repository, which the lazy-open rule forbids. Users can use the native file explorer for moves and refresh afterward.

A uniform that nothing provides is a warning, and the shader still runs (SPEC 3.2). The warning carries its fix: **Add texture** for a sampler, the nearest builtin name for a likely typo. Shader text written for the old conventions offers **Convert**, which rewrites that file's sampler comments and library texture names into directives (SPEC 12.3).

Delete uses the provider capability and warns about known dependent files. Removing an item from Recents only removes the shortcut. Do not equate it with deleting a folder. Native Files operations are ordinary outside edits; they go through the same refresh/conflict handling.

## 7. Share the shader, not unrelated folder contents

Default **Share** packages the selected running shader and the transitive closure of its shader inputs, literal includes, and local images. A dependency-free standalone shader can be shared as a `.glsl` file. A multi-file shader is shared as a ZIP retaining relative paths. If its selected entry differs from the folder's portable default, generate/adjust the entry manifest IN THE COPY. Do not modify the source folder to share it.

Invalid/unsupported manifests can be preserved in an explicitly incomplete source export; do not silently replace them with a supported manifest.

Never default to exporting the whole opened folder: it may be Documents, a repository, or another broad user directory. An optional **Include more files** action lets users explicitly add unused experiments or documentation. General whole-folder copying belongs to the native file explorer; no second project-export mode is necessary.

Sharing does not require successful GLSL compilation. Static dependency collection usually works despite shader errors. If malformed/missing references prevent complete collection, show which files are unresolved and allow explicit extras or sharing the incomplete sources as-is. Do not claim such a package is self-contained. Exclude app internals and VCS administration directories.

A ZIP reaches the app through Open file or as a share target. Do not register ShaderEditor as a default viewer for every ZIP on the device. Opening a ZIP offers a destination using the same location picker, extracts into a new folder without overwriting unrelated files, then opens that folder normally. A portable-default override exists only in the extracted manifest; there is no imported-project mode.

## 8. Wallpaper and existing shaders

**Set wallpaper** applies the current valid shader. Internally store a durable dependency snapshot, so an unavailable source provider cannot break the wallpaper after the editor closes. Keep this mechanism invisible. Current invalid edits cannot silently apply an older preview. Later edits use **Update wallpaper**; no live-sync preference or provider-dependent behavior in v1. Keep separately persisted legacy/runtime behavior where supported.

Existing shaders go through three steps (SPEC 12.2), and users notice only the second.

1. At first nothing changes. DB shaders are listed, edited, and saved as today. Underneath they are converted on the fly, so they already run on the same engine as files.
2. Later, one update moves them. All shaders become files in the library folder, and all library textures go into its `textures/` folder once:

```text
ShaderEditor/
  Shaders/
    Plasma.glsl
    Clouds.glsl
    textures/
      noise.png
```

The shader list looks the same afterwards, with the same thumbnails, and the wallpaper keeps showing the same shader. Say once, plainly, what happened and where the files are. Titles that are empty or repeated get a number. If anything fails, the affected shader stays usable from the database and the move resumes on the next start. Do not ask the user to choose anything.
3. A release later the database copy is removed. This step is invisible.

Before step 2, **Save as files** converts one shader on request with the usual Name/Location flow and keeps the original. Do not display internal terms such as compatibility mode or converter in normal navigation.

## 9. Acceptance and implementation restraint

Use the existing Android components and localization/accessibility conventions. Long filenames retain a way to see the full path (the drawer's header shows it, and rows under Running show each file's folder), focus returns to the editor after insertion, and save/error state is not color-only. Do not add an onboarding tour for concepts that the actions already explain.

Native authoring stays behind one temporary development gate. The gate lifts for single-pass projects once M14 is validated (Gate A); shader inputs then report that they need a newer version until Gate B. No public claim before rendering, provider access, file recovery, and wallpaper cases work. Provider failures and conflicts still need correct handling; simplify their frequency and presentation instead of hiding data loss.

Primary references: [Android document-provider guide](https://developer.android.com/guide/topics/providers/create-document-provider), [Android document access](https://developer.android.com/training/data-storage/shared/documents-files), and [Termux's DocumentsProvider](https://github.com/termux/termux-app/blob/master/app/src/main/java/com/termux/filepicker/TermuxDocumentsProvider.java). Use Termux as a UX/API reference, not code to copy verbatim.

# Importing from ShaderToy, offline

Status: design for the offline import decided on 2026-11-15. Nothing here uses the network, a key or a new permission. Opening a shader by its link and browsing wait for the answer of ShaderToy's operators and are not part of this.

## The short version

- **What you can do.** Get a shader out of ShaderToy yourself, as JSON, and hand it to the app: share it, open the file with the app, or paste it. The app makes a folder in the library with one file per tab, wired as passes, and opens it.
- **What it becomes.** Image is `main.frag`. Buffer A to D are `bufferA.frag` to `bufferD.frag`. Common is `common.glsl`, included by every pass. The author's code is not edited: the import puts its lines above it, as ShaderToy does.
- **What it is honest about.** Whatever cannot be carried over is named three times: in a dialog right after the import, in the folder's `README.md`, and in a comment at the line. Nothing is replaced by a guess. A missing texture reads black and says so.
- **What is not supported.** ShaderToy's own pictures, cubemaps, volumes, music and videos (the app does not ship or fetch them), the keyboard, the webcam, the microphone, sound output, cubemap passes, mipmaps of buffers.
- **Pasting a single tab** works exactly as it does today. Nothing about it changes.
- **Several tabs without JSON** (copying each tab's code on the phone) need a small form in the New sheet. The converter for it is built and tested; the form waits until the New sheet is free to change.
- **GLSL.** An import is always `#version 300 es`, as ShaderToy is. A phone with OpenGL ES 2.0 only cannot run it and the app says so.

## 1. What a person can get out of ShaderToy

Three forms exist. Each line says how sure it is.

### 1.1 One tab's code

Select all in the editor on the site, copy, paste into a shader of the library. This works today for a shader that has only the Image tab and stays as it is (UX 8).

It cannot carry a shader with buffers: the code of a tab does not say what `iChannel0` reads. That is set in a panel under the editor and exists only in the JSON.

### 1.2 JSON

Two shapes were found as real files, and both are accepted. They hold the same things under slightly different names.

| | The API's answer | The site's own shape |
| --- | --- | --- |
| Outside | `{"Shader": {…}}` | `[{…}]`, a list of one |
| Inside | `ver`, `info`, `renderpass` | the same |
| An input | `ctype`, `src`, `id` a number | `type`, `filepath`, `id` a string |
| Buffer A to D | ids 257 to 260 | ids `4dXGR8`, `XsXGR8`, `4sXGR8`, `XdfGR8` |
| Checked on | four real files | one real file |

In both, `info` has `name`, `username`, `id` and `description`. A pass has `type` (`image`, `buffer`, `common`, `cubemap`, `sound`), `name`, `code`, `inputs` and `outputs`. An input has `channel` (0 to 3) and `sampler` with `filter` (`nearest`, `linear`, `mipmap`), `wrap` (`clamp`, `repeat`, `mirror`), `vflip` and `srgb`, the last two as the strings `"true"` and `"false"`. A buffer's `outputs[0].id` is the id that inputs name to read it.

Also accepted, because it follows from the two: the object alone, without `Shader` around it and without the list; and either spelling of a key in either shape.

Where this was checked:

- The API's shape: four files of `GabeRundlett/shadertoy-api-shaders` on GitHub, which are saved API answers, read 2026-11-15. It matches the type definitions of two open-source clients (`repi/shadertoy-browser`, `ivoleitao/shadertoy_api`).
- The site's shape: one file of `CreativeCodeBerlin/creativecode.berlin` (`public/shadertoys/wsB3Rt.json`), read 2026-11-15, and a copy of the site's renderer script (`effect.js`) in the same repository, which reads exactly these keys and holds the four buffer ids. That copy is a third party's, committed 2026-02-21. It is not the site, which still refuses automated reading.

How a user gets JSON, honestly:

- **With an own API key:** open `https://www.shadertoy.com/api/v1/shaders/<id>?key=<key>` in the browser and share or save the page. This works on a phone. A key needs an account of a certain standing (reported, not read), and the shader must be "Public + API".
- **From a desktop:** a browser's developer tools show the page's own data in the site's shape; a well-known browser extension is said to export it. Neither was tried here. The file then has to reach the phone.
- **A download button on the site:** none was found by the research. Not established.

So on a phone alone, JSON is realistic only with an API key. That is why the tab form below matters, and why browsing waits for the operators.

### 1.3 Several tabs, as code

The user copies each tab in turn. Three ways to hand them over were compared for a shader with Common, Buffer A and Image and two channels to wire:

| Way | What the user does | Where it goes wrong |
| --- | --- | --- |
| One text, tabs separated by a line the app knows | 3 copies, 3 pastes into one text, 3 separator lines typed exactly, channel lines typed by hand | A mistyped separator; tabs in the wrong order; the app can only refuse the whole text |
| One paste per tab into files | Per tab: New file, type its name, paste. Then type the `#pragma se input` lines by hand | Wrong names; a forgotten `frame=previous` on a buffer that reads itself, which is a cycle error |
| A form: **From ShaderToy** in the New sheet | Per tab: copy on the site, tap **Paste** beside the tab's name. Per channel the code uses: pick what it reads from a list | A tab pasted into the wrong row; the row shows its first line to check |

**Chosen: the form.** It needs no typing, and what the code cannot say (the channels) is asked for, one tap each, only for the channels the code names. It lists Image, Common, Buffer A to D; a tab left empty does not exist. Until a channel is picked it reads black and the result says so.

The form lives in the New sheet, which is being rebuilt elsewhere right now. The converter takes "a name and the text of each tab" today and is tested with it; the form is the remaining step.

## 2. The mapping

Semantics marked **script** were read in the copy of the site's renderer script named in 1.2. That is the best source reachable; it is a copy, not the site. Semantics marked **engine** are this app's and were read in its code.

### 2.1 Tabs become files

| ShaderToy | File | Role |
| --- | --- | --- |
| Image | `main.frag` | the running shader |
| Buffer A to D | `bufferA.frag` to `bufferD.frag` | passes |
| Common | `common.glsl` | included by every pass, before its code |
| Cube A | `cubeA.glsl` | kept, not run; a warning |
| Sound | `sound.glsl` | kept, not run; a warning |
| (none) | `empty.frag` | what a buffer holds before the first frame |
| (none) | `README.md` | title, author, link, licence note, the list of files, the warnings |

A buffer's letter comes from its output id (table in 1.2), then from its name ("Buffer A", "Buf A"), then from its place among the buffers.

### 2.2 Which frame a buffer read sees

**Script:** in each frame the site draws the buffers in the order they stand in the JSON, then Image. Every buffer keeps two pictures; a read gets the last one finished. So a read of a buffer drawn earlier in the frame sees this frame, and a read of the buffer itself or of a later one sees the frame before.

The import writes exactly that:

| The pass reads | Line the import writes |
| --- | --- |
| a buffer drawn before it | `#pragma se input "bufferA.frag"` |
| itself | `#pragma se input "bufferA.frag" frame=previous` |
| a buffer drawn after it | `#pragma se input "bufferB.frag" frame=previous` |
| any buffer, from Image | `#pragma se input "bufferA.frag"` |

The app orders the passes of a frame from these lines (SPEC 6), so it draws them as the site does without being told an order.

### 2.3 How a buffer is read

| ShaderToy | The input line | Note |
| --- | --- | --- |
| `filter` nearest | nothing (the app's default for a pass) | |
| `filter` linear | `filter=linear` | |
| `filter` mipmap | `filter=linear`, and a warning | passes have no mipmaps (SPEC 4) |
| `wrap` clamp | nothing (the default) | |
| `wrap` repeat, mirror | `wrap=repeat`, `wrap=mirror` | |
| `vflip`, `srgb` | nothing | **script:** they do not apply to buffers |

One pass may read the same buffer once with nearest and once with linear. The import writes both lines. A device with OpenGL ES 2.0 cannot do that, and the engine refuses it when it builds, with its own message. The import never refuses it.

### 2.4 What a buffer holds

**Script:** buffers are 32-bit float, the size of the picture, and start as (0, 0, 0, 0).

- **Format: `#pragma se target format=rgba16f` on every buffer.** 16-bit float is the widest the app has. The alternative, 8 bits, would silently break every shader that keeps positions, speeds or negative numbers in a buffer, which is most of them. On a device that cannot draw into half-float pictures the engine refuses the build and names the fix: "format=rgba16f needs … which this device does not have; use format=rgba8". The user can then decide; the import does not decide for them.
- **16 against 32 bits** is not detected. A shader that packs data into the bits of a float, or counts above 2048 in one, can differ. The README of every import with buffers says this in one line.
- **Size:** the default, the size of the picture. Equal.
- **Start: `initial="empty.frag"`**, a file that draws (0, 0, 0, 0), on every buffer whose earlier frame someone reads. The app's own start is (0, 0, 0, 1), and shaders test the alpha of a buffer to see whether they have run before. A buffer whose earlier frame nobody reads gets no `initial`, as that would be a warning of the engine.

### 2.5 The inputs every shader has

The author's code is left as it is. Above it the import declares the app's uniforms it needs and ShaderToy's names as ordinary variables, and a function that fills them; `main` calls it first. Only what the code names is declared.

```glsl
uniform vec2 resolution;
uniform float time;

vec3 iResolution;
float iTime;

void shaderToyInputs() {
	iResolution = vec3(resolution, 1.0);
	iTime = time;
}
```

Why variables and not a renaming of the text, as the single-tab paste does: a shader may have a local `time` of its own, and after a renaming `iTime` would silently mean that one.

| ShaderToy | Becomes | How exact |
| --- | --- | --- |
| `iResolution` (vec3) | `vec3(resolution, 1.0)` | equal (**script:** z is 1) |
| `iTime`, `iGlobalTime` | `time` | equal |
| `iFrame` | `frame` | equal |
| `iDate` | `date` | equal: year, month from 0, day, seconds of the day |
| `iMouse` | `mouse`, as a vec4 | close, see below |
| `iChannelResolution[n]` | the size of what channel n reads, from `textureSize` | equal for a buffer; a missing texture is 1 × 1 |
| `iChannelTime[n]` | 0 | equal for everything the import supports |
| `iSampleRate` | 44100 | only sound uses it |
| `iTimeDelta` | 1/60, and a warning | not equal: the app tells a shader no frame time |
| `iFrameRate` | 60, and a warning | not equal, for the same reason |
| `fragCoord` | `gl_FragCoord.xy` | equal |
| `HW_PERFORMANCE` | 0 | **script:** what the site sets on a slow device |
| `mainImage` | called by the `main` the import adds | equal |
| `mainVR` | left alone | the picture is the one without a headset |
| `mainSound`, `mainCubemap` | not run | a warning |

`iMouse`: x and y are where the finger is or was last, z and w where it went down, both negative once it is lifted. That is what the copy of the site's page does. The site's editor is reported to keep w positive only in the frame of the click; a shader that tests `iMouse.w` for a fresh click sees a held finger as many clicks. Not detected, not warned.

The `main` the import adds, **script:**

```glsl
out vec4 shaderToyColor;

void mainImage(out vec4 c, in vec2 f);

void main() {
	shaderToyInputs();
	vec4 color = vec4(1e20);
	mainImage(color, gl_FragCoord.xy);
	shaderToyColor = vec4(color.rgb, 1.0); // a buffer: = color
}
```

It stands above the code, as on the site, so that a macro of the shader (`#define color …`) cannot change it. The order in a file is: version and precision, the target line of a buffer, the channels, the inputs, `main`, the include of Common, the code.

**A shader that already defines `main`** is not ShaderToy's form. That file is written as it came, with a warning, as today's paste leaves such text alone.

**A name of its own that is also one of the app's uniforms.** A shader that declares its own global `time` (or `resolution`, `frame`, `mouse`, `date`) and uses `iTime` would declare `time` twice. The import sees a declaration of that name outside every function and adds one line, `#define time shaderToyOwn_time`, after its own lines, so the shader's name is another one throughout. Its meaning does not change. A shader that says `#define time iTime` itself needs nothing and gets nothing.

### 2.6 Channels that are not buffers

The app neither ships nor fetches ShaderToy's media: their licence is not known (the research could not read it), and fetching is network use.

Such a channel is declared without an input. The engine then gives it an empty texture that reads black, and warns by itself: "Nothing provides iChannel1, so it reads black; attach an image to it with #pragma se input "path"". The project builds and runs.

Above the declaration the import leaves what it knows and a line ready to be switched on:

```glsl
// iChannel1 read a texture of ShaderToy (/media/a/52d2….jpg),
// which is not part of the shader. It reads black until you attach a picture; the file says how.
// Put a picture into this folder and take the "//" off the next line:
// #pragma se input "channel1.png" wrap=repeat
uniform sampler2D iChannel1;
```

The bundled noise is not used as a stand-in: it would be a different picture presented as the shader's, which is a silently wrong result.

Every warning starts with the file and the channel: "bufferA.frag: iChannel1 read …".

| Channel reads | Declared as | The warning goes on |
| --- | --- | --- |
| a texture | `sampler2D` | "a texture of ShaderToy (/media/a/….jpg), which is not part of the shader. It reads black until you attach a picture; the file says how." |
| a cubemap | `samplerCube` | "a cubemap of ShaderToy (…), which is not part of the shader. It reads black until you attach a picture; the file says how." |
| a volume | `sampler3D` | "a volume texture of ShaderToy (…), which the app cannot load. It reads black." |
| the keyboard | `sampler2D` | "the keyboard, which the app does not have. It reads black: no key is down." |
| the webcam | `sampler2D` | "the webcam, which the import does not attach the app's camera to. It reads black." |
| the microphone | `sampler2D` | "the microphone, which the app does not turn into a picture. It reads black." |
| music, a music stream | `sampler2D` | "music (…), which the app does not turn into a picture. It reads black." |
| a video | `sampler2D` | "a video (…), which the app does not play. It reads black." |
| Cube A | `samplerCube` | "Cube A, a pass that draws a cubemap, which the app cannot do. It reads black." |
| a buffer the shader lacks | `sampler2D` | "a buffer, which this shader does not have. It reads black." |
| a kind the import does not know | `sampler2D` | "something of the kind "…", which the import does not know. It reads black." |
| nothing, but the code names it | `sampler2D` | no warning: it read black on the site too |

A texture's settings go into the prepared line: `nearest` becomes `filter=nearest`, `mipmap` becomes `mipmap=linear`, `repeat` and `mirror` become `wrap=`. `vflip` false is noted in the comment, because the app always reads a picture upright (**script:** `vflip` true, the site's default, is upright). A cubemap's comment says which layout of the six faces the app reads.

The other warnings, word for word:

- "main.frag: iChannel3 read Buffer B with mipmaps, which passes do not have here. It is read with filter=linear."
- "main.frag: iTimeDelta is always 1/60 s here, as the app does not tell a shader how long a frame took. On a screen that is not 60 Hz, what is computed from it runs at another speed."
- "main.frag: iFrameRate is always 60 here, as the app does not tell a shader its frame rate."
- "The Sound tab makes sound, which the app does not do. Its code is kept in sound.glsl and is not run."
- "The Cube A tab draws a cubemap, which the app cannot do. Its code is kept in cubeA.glsl and is not run."
- "main.frag defines main itself, so it was kept as it is, without ShaderToy's inputs."
- "The text holds 3 shaders; the first was imported."
- For tabs given as code: "main.frag: iChannel0 is not attached, because the code does not say what a channel reads. It reads black; the file lists the lines to choose from."

A channel the JSON attaches and the code never names is left out. The site draws such a buffer anyway and nobody sees it.

### 2.7 GLSL version

**Script:** the site compiles `#version 300 es` with `precision highp float; precision highp int;`.

The import always writes those three lines. Emitting the older language "when the code allows" would need a judgment about the code that a text scan cannot make safely: `texture()`, `texelFetch`, integer operations and loops that the older language lacks are everywhere in ShaderToy code.

On a device with OpenGL ES 2.0 only, the engine says: "#version 300 es needs OpenGL ES 3.0, but this device only has OpenGL ES 2.0". The import itself works there; the project is ordinary files.

## 3. Where it lands

```text
Shaders/
  Ink Drop/
    README.md
    main.frag
    bufferA.frag
    bufferB.frag
    common.glsl
    empty.frag
```

- A folder in the library, named after the shader, like a saved copy of an example with several files. A name that is taken gets a number (`Ink Drop 2`), as New shader does it; characters a name cannot hold become `_`. A shader without a name is `ShaderToy`.
- Nothing existing is written over, ever.
- `main.frag` is written last, so a folder that was cut short has nothing that runs and does not look finished.
- **Undo:** an import makes files. It is undone by deleting the folder. There is no other undo, and the dialog after the import does not offer one.

The README:

```markdown
# Ink Drop

By wyatt on ShaderToy: https://www.shadertoy.com/view/4cVSRw

This shader is its author's work, under the licence they chose. Look
for one in the comments of the code. Where a shader states none,
ShaderToy's default is reported to be CC BY-NC-SA 3.0: name the
author, no commercial use, share changes under the same terms.

## Files
- [main.frag](main.frag) Image: what you see
- [bufferA.frag](bufferA.frag) Buffer A
- [common.glsl](common.glsl) Common: code every pass includes
- [empty.frag](empty.frag) What a buffer holds before the first frame

Buffers keep 16-bit floats here; ShaderToy keeps 32-bit ones. A shader
that packs data into the bits of a float may differ.

## Not carried over
- bufferA.frag: iChannel1 read a texture of ShaderToy …
```

"Reported", because the terms of the site could not be read. Naming the author is right whatever they say, and it costs nothing. The link is only written when the JSON has an id. The drawer orders the folder by this README (NAVIGATION 6.4).

## 4. What the user sees

**Starting an import**

| From | How |
| --- | --- |
| Another app | Share text or a file to ShaderEditor, or open a `.json` file with it |
| The clipboard | Paste the JSON into a shader of the library's root. The app asks, once: **Import from ShaderToy?** "This text is a shader from ShaderToy with its passes. Import it as a folder of its own? The text stays in this file; Undo takes it out again." **Import** makes the folder. JSON that is already in a file when it is opened is not asked about; so a question that is open when the device is turned is gone, and Undo and Redo, or pasting again, bring it back |
| Several tabs | **From ShaderToy** in the New sheet (1.3), later |

What a text is, is told from its content (SPEC 2.1), never from a name: it is ShaderToy's JSON when it starts with `{` or `[` and has a list `renderpass`. Any other text is handled as before.

**After it**

- The folder's `main.frag` opens and runs. The drawer shows the folder with its picture once it has run; **Running** lists its passes with their roles.
- If anything was not carried over, a dialog **Imported from ShaderToy** lists it, once: "The shader is a folder in your library now. Not carried over:", the items, and "This list is also in the folder's README.md." Each item is also a comment at its line, and a channel that reads black is in the engine's warnings while the shader runs.
- With nothing to say, the project just opens.

**When it cannot be imported**, one message says why and nothing is made: "Not imported from ShaderToy: it has no Image pass". The reasons, word for word: "the JSON is cut off or broken (line 12: …)", "ShaderToy answered with an error: Shader not found", "the list holds no shader", "it is not a shader of ShaderToy", "it has no list of passes ("renderpass")", "it has no passes", "pass 2 is not an object", "Buffer A (pass 2) has no code", "the inputs of Image are not a list", "an input of Image is not an object", "it has no Image pass", "it has two Image passes", "it has two Common passes", "two passes write Buffer A". A folder that cannot be written whole is removed again and the reason is told.

**While it is made**, the screen behaves as for a shared text: nothing blocks, and a screen that is made again (turned, or brought back) shows the result or the failure. Parsing the JSON, converting and writing happen on the projects' queue, off the main thread. Taking the text out of the other app's file is today's read of a shared text, which is on the main thread; that is the same for every shared file and is not changed here.

The app is offered for `.json` files (types `application/json`, `text/json`) only in builds that have project mode on.

**Today's paste of one tab** is unchanged: text that defines `mainImage` and no `main`, put into a shader of the library's root, is wrapped in one step that Undo takes back. Release builds have only this, as before.

## 5. Not done, and why

| Not done | Why |
| --- | --- |
| Anything over the network: open by link, search, browse | Decided: wait for the operators (key, limits, media) |
| ShaderToy's textures, cubemaps, volumes, music, videos | Licence unknown; not shipped, not fetched |
| The tab form | Needs the New sheet |
| Sound output, cubemap passes | The engine has neither |
| Keyboard, webcam, microphone, music, video as inputs | Not built: the keyboard does not exist on a phone, the camera is another kind of sampler here, the app has no sound picture |
| Mipmaps of buffers | Not in the format (SPEC 4) |
| 32-bit float buffers | The app has 16-bit |
| Exact `iTimeDelta`, `iFrameRate` | Needs a new uniform of the engine. Follow-up |
| Exact `iMouse.w` | Needs the engine to know the frame a touch began. Follow-up |
| `st_assert`, the `iCh0` structs | Rare; they fail to compile with the compiler's message |
| Converting a tab pasted into a file of a folder | Unchanged rule (UX 8): files of a port are not rewritten one by one |

## 6. Open questions, with the default taken

1. **File names.** `bufferA.frag`, not `Buffer A.frag`: letters only, easy to type in an input line. Default taken.
2. **Buffers as `rgba16f` always.** A device without it gets the engine's refusal instead of a wrong picture. Default taken.
3. **The dialog after an import with warnings.** A dialog, not a bottom message, because the list can be long and must not vanish. Default taken.
4. **The pasted JSON stays in the file it was pasted into.** The app does not delete the user's text; Undo removes it. Default taken; the alternative is to take the paste back automatically after the import.
5. **A frame-time uniform in the engine** would make `iTimeDelta` and `iFrameRate` exact. It is a new builtin for every shader and shows in Add uniform, so it is the owner's call. Default: not added.
6. **The JSON's `description`** is not copied into the README. It is the author's text and often long; the link leads to it. Default taken.

## 7. How this was checked

- **The converter** is a unit without Android in the project layer, with JVM tests on four shaders written for them in ShaderToy's JSON, in both shapes; none is from the site.
- **Every converted fixture prepares** with the engine's own preparation, the code that plans a build, without a diagnostic, as the bundled examples do. A buffer that reads itself has the same plan shape as the bundled Ink example.
- **Every converted pass compiles** as GLSL ES 3.00 with Mesa's standalone compiler on the desktop, after the engine's assembly (includes put in, host lines blanked): thirteen programs, no message.
- **On a device** (emulator, Android 16, OpenGL ES 3): JSON shared as text, a `.json` file opened with the app, a file shared, and JSON put into a shader each made the folder; the drawer listed it with its picture, and Running listed the passes with their roles. A buffer that reads itself drew the same trail as the same logic written by hand as a project, and its first frame showed that it started as (0, 0, 0, 0). Two buffers that read each other, with negative numbers, drew spreading waves. The shader with every unsupported input ran, listed its eighteen items in the dialog, and the engine warned at each channel that reads black. A share during a turn of the device arrived on the screen made again. A process killed during an import left no folder and nothing half-written; the share has to be repeated.
- **Not checked from a primary source:** everything marked **script** rests on a third party's copy of the site's renderer script; `iMouse.w` on the site's editor page; how a phone user gets the site's JSON shape.

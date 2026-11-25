# crashguard-2: the fix for a never-building shader taken for a crash

Builds (heads checked after `git fetch`, both exact):
- **Upstream** `origin/upstream-crash-guard-failed-build` at **900d24df4a4fc9b6b82baef59257d6123316cf88**: one commit on 190471515. `MainActivity.onInfoLog` clears `pending_crash_shader` when the info log is not empty.
- **Fork** draft PR #832 (`fix/crash-guard-failed-build`) at **9a92d959538eaedb84136123004a61ae1e675c9b**. On an ERROR diagnostic it clears `pending_crash_shader` and calls `screen.onFailed()`; the library's `RunGuard.failed()` clears `pending_crash_file`. Built as the debug app and as the `.dbscreen` old-editor variant.

Test files:
- `Bad.frag`: `gl_FragColor = vec4(oops, 1.0);`
- `Good.frag`, `Good2.frag`: compile.
- `Endless.frag`: the 100,000,000-iteration `sin` loop from the task.

Markers were read with `run-as … cat shared_prefs/…_preferences.xml | grep pending_crash` before each kill.

## The headline: the emulator does not survive the hanging shader
On **both** emulators (host GPU), `Endless.frag` does not just hang the app. Within about 2–4 s of the program reaching the GPU, the **whole emulator process dies**: adb says "device offline", and qemu is gone. So `am force-stop` and `am crash` during the hang could not be sent. Each time, the emulator had to be booted again through the device panel; no cold-boot command was needed. Case 4 was therefore judged on what the next start of the app does after that death, which is a harsher kind of process death than a force-stop.

## Fork PR #832

| Case | Pixel_API_37 (Android 17) | Pixel_API_23 (Android 6) |
|---|---|---|
| 1 Folder file `Bad.frag`, 10 s, force-stop, start | Before kill: `pending_crash_file` **empty**. After start: no message, not held (`held_file` unchanged), Bad.frag shown with its errors (f1-start) | Same: empty, no message, not held |
| 1 `.dbscreen`, saved `oops` shader selected, 10 s | `pending_crash_shader` **0** at 1 s and 10 s. After start: shader **unchanged**, no "commented out" toast (d1-start) | Not done (see below) |
| 2 `Good2.frag` running, `x` typed, two force-stops | Marker empty before both kills; **no message either time**; the draft with `x` comes back with its error (f2-start2) | Same (a2-start2) |
| 3 `Good.frag` / Default (control) | No message, marker empty (fork), 0 (`.dbscreen`) | No message |
| 4 `Endless.frag` | `pending_crash_file` = `…\nEndless.frag` 3 s after opening; the emulator died. After the reboot the marker was still set. The first start died again before the guard's write reached disk. The next start: **"Shader Editor closed while Endless.frag was starting. It does not run again by itself."** + Run code, file shown, not run, `held_file` = Endless.frag, emulator alive (f4-s6) | Happened unplanned: on the first start after install the library opened Endless.frag by itself and the emulator died. After the reboot, `pending_crash_file` = Endless.frag. Next start: **the same message, held, not run**, emulator alive (a4-s5) |
| 4 `.dbscreen` | **Not testable**: sharing Endless.frag in runs it at once, before it can be saved, so there is no shader id and the marker stays 0; the emulator died both times (once in auto, once after switching to Run manually) | Not done |
| 4 `am crash` during the hang | Not possible: the emulator dies first | — |
| 5 `Bad.frag` edited into the hang, force-stop during the hang | `pending_crash_file` = `…\nBad.frag` **1.9 s after the edit** (so the guard caught the build); the emulator died before my force-stop. After the reboot the marker was still `Bad.frag`. **But the next start did not hold it**: no message; Bad.frag was shown and built from its restored draft, which lacked my last edit (`vec3(v)` was lost with the death, so the draft still had `oops`, failed to compile and did not hang); `held_file` stayed `…Endless.frag` from case 4 (f5-start). **Not confirmed.** Reading `RunGuard`'s constructor, a non-empty `pending` should become `held`. I could not repeat it without another emulator death | Not done |

## Upstream 900d24df4

| Case | Pixel_API_37 | Pixel_API_23 |
|---|---|---|
| 1 Saved `oops` shader selected, 10 s, force-stop, start | `pending_crash_shader` **0** at 1 s and 10 s. Shader unchanged, no toast (u1-start) | Same: 0, unchanged, no toast (au1-start) |
| 3 Default (control) | 0, no toast | 0, no toast |
| 4 Hanging shader | Not attempted on Android 17 (each try kills the emulator; done on Android 6 instead) | Manual run mode. `oops` shader edited into the loop, **saved** (`pending_crash_shader` = 2, not run), then **Run code**: **the marker was 0 one second later**, before any frame of it, and the emulator died at about 2 s. After the reboot the marker was still 0. The next start **ran the shader again and killed the emulator again**: **no rewrite, no toast, the guard did not catch it.** An earlier try in auto mode (edit without saving) also left the marker at 0, because an unsaved edit does not set it |

**Likely reason for upstream case 4 (not verified):** upstream clears the marker in `onFramesPerSecond` whenever `fps > 0`, and the renderer keeps drawing the previous program while a new one is pending. Its frame rate (57 fps shown) can clear the marker before the new program ever draws. Before this commit it was the same; the new `onInfoLog` clear only adds to it. The fork avoids this for database shaders with `drewShaderId`, and for files with `RunGuard`'s per-file "drew" set.

## Not done, and why
- `.dbscreen` cases on Android 6, and fork case 5 on Android 6: each hanging run costs an emulator death and reboot, and Android 17 already showed the `.dbscreen` hang case cannot be set up (share runs before save).
- Upstream case 4 on Android 17, and `am crash`: the emulator dies before any command can reach it.

## Cleanup
- **Pixel_API_37:** earlier fork build back (sha1 f706f08a), app data byte-identical (141/141). `.dbscreen` uninstalled. Font 1.0, density 420, rotation 1/0, Gboard, no accessibility service. Shut down.
- **Pixel_API_23:** earlier fork build back (md5 f0655b81). The backup taken this session (`data-23k`) was already the fork app's state after an automatic start, so I restored the tuning-6 backup (`data-23j`, the agreed state) and verified it byte-identical (28/28). This also undid one start of the earlier build that I made to check that it runs. Run mode is in that data (Auto). LatinIME, font 1.0, density 440, rotation 0/0. Shut down.
- The emulators died 4 times each during the hang tests; each was brought back by the device panel's boot, and no settings were changed.
- agent-device session list empty. Worktrees `ShaderEditor-cg2` and `ShaderEditor-up2` removed after this push; no Gradle daemon. No fix, no commit to code. The APKs are not pushed.

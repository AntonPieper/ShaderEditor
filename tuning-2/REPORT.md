# tuning-2: re-run of the tuning-1 faults (final reports as sent)

## Part 1: head c90d141d08dabe21ddc46268af4aa8bf4809ce54 (commit 1016aeed)

All ten tuning-1 findings are fixed on both emulators, including the Android 6 clock restart. New problem: turning TalkBack on, or a system permission dialog appearing, closes the tuning bar.

### Pixel_API_23
| Row | Seen | Result |
|---|---|---|
| 1 | After typing 0.060 + Enter, every stop showed its own value; + on 0.60 changed only it | Pass |
| 2 | Focus to the ruler after Enter; End/Delete/Backspace/a/b changed nothing; →→← 0.70→0.71→0.72→0.71 | Pass |
| 3 | Touch holds 0.6/1.5/3 s show start value, OFF after release; tap toggles; real `\` hold same | Pass |
| 4 | 1e99 → "Not a number of this type"; 2.5e-1 applied; empty → error; empty then › → 0.25; nothing applied before Enter | Pass |
| 5 | One line with 0.01 and 0.001; step chip without "Step", Done without ✓ | Pass |
| 6 | Revert identical; one Undo brings all three; step by step; loop-only session clean | Pass |
| 7 | No clock restart in any case (samples below) | Fixed |
| 9 | `vec3 tint = vec3(0.20, 0.61, 1.00);` | Pass |
| 10 | Drawer closes, value kept; after switching file disk has last value (0.83) | Pass |
| 11 | Sweep OK | Pass |

### Pixel_API_37
Rows 1–6, 9–11 as on Android 6 (row 5: at default font one line, 5 px between chip and Done; at 1.3 "Step" drops). Row 8 TalkBack pass but see oddity 1. Row 7 skipped there on instruction.

Row 1 text: only `#define GLOW(d) (0.060 / (d))` (was 0.080) and `0.61,` (was 0.60) changed.

Row 6 Android 6 (lines 13/14): after Revert `0.75 + 0.25`, `i < 3`; Undo 1 `0.76 + 0.26`, `i < 4`; Undo 2 `0.76+0.26`, 3; Undo 3 `0.76+0.25`, 3; Undo 4 `0.75+0.25`, 3; Redo 1–4 forward in order. Loop-only: Revert 3; Undo 4; Redo 3; Undo 4; Redo 3; extra Redo nothing. Android 17 identical.

Row 7 (Android 6, 6.3 s rhythm, ~230 ms samples): a. Ctrl+Z at 7.2 s, minima 2.6/8.9/15.2 s; b. menu Undo 7.9 s, minima 2.0/8.3/14.6 s; c. (session cannot start with code hidden; variant: code hidden after Done) peaks 2.3/8.6/14.8 s; d. keyboard up, peaks 2.4/8.7 s. No jump. App logcat: only eglMakeCurrent, getSlotFromBufferLocked, InputEventReceiver lines at menu open/close. Reading: no longer happens on this head (matches commit 7d3a1dfd).

Row 8 speech: "Tune 0.93. Button" → "Tuning 0.93", "0.93, started at 0.93. Value. Slider"; "0.010, can be tuned", "0.31, can be tuned", "Tuning 0.93"; + "0.94"; double-tap 0.31 "Tuning 0.31"; "0.94, changed"; swipes: "Previous number 0.94. Button" → "… 0.010) * (0.94 + 0.31 * sin(time));" → "0.010, can be tuned" → "0.94, changed" → "Tuning 0.31" → "Next number 1. Button" → "Decrease by 0.01. Button".

Oddities: 1. TalkBack turning on (and its permission dialog) closes the bar like Done; my touches then typed "vetyiq" into line 13, repaired. 2. adb `--longpress` latches Before (not a real hold). 3. Emulator backslash key events did nothing on Android 17. 4. My save-helper slip typed four backslashes on Android 6; repaired.

## Part 2: delta on head b3db9ddd2ce6181eb887b8116e3d9f8f88c82099 + row 7 on debug/tuning-clock ae187bda (commit 631a3692)

Rows 13–17 pass on both emulators; no crash anywhere. Pixel_API_23's display went black mid-run; cold-booted with the agreed command.

| Row | Seen | Result |
|---|---|---|
| Re-check 1, 3, 6 | As expected | Pass |
| 13 rotate within ~0.3 s | Tune.frag 14 rotations, 2,816-line file 10 rotations; no FATAL/RejectedExecutionException; session ends, value kept | Pass |
| 14 Before + tap | Bar moves to the tapped number; no crash on last statement | Pass |
| 15 typed, no Enter | Save 0.33, eye 0.34, drawer 0.35, Home 0.36 (on disk); letters/empty keep old | Pass |
| 16 no flash | Bursts show no in-between values (cannot rule out 1–2 frame flashes) | Pass within limits |
| 17 Enter on focused button | Android 6: D-pad to Before, Enter toggles, bar stays; Android 17: D-pad to Step, Enter opens menu, bar stays | Pass |

Row 16 bursts: Done w/ Before on, A6: …19.5, 18.4 then 39.6, 39.3…; Revert A6: 0.0…6.5 then 40.1…; loop 30 w/ Before 3 then Done A6: 153–162 then 51.3, 54.3… (ref 30 loops ~53, 3 loops ~42). Android 17 similar.

Row 7 on debug build: no jump on either platform. TuningClock lines: A6 a: `edit after tuning at 329 '0.26' -> '0.25'` (stack via KeyCommands.keyDown ← CodeView.onKeyDown), then `run edit=true tuned=true state=1 states=[1, 0, 2]`; A6 b: same line twice (stack via CodeViewSourceEditor.undo ← MainMenuManager), `run … state=4 states=[4, 5, 1]`; A6 c: three times, `state=7 states=[8, 7, 4]`; A17 a: once, `state=1 states=[0, 1, 2]`; A17 b: twice, `state=4 states=[1, 4, 5]`. No `time restarts`, no `onStart: refresh`, never through the input connection. Note: one more identical `edit after tuning` line per session since app start (listener added per session, not removed?).

Oddities: start value shown with the new length ("Tuning 0.0100"); tapping the tuned number keeps Before on; arrow keys change the value while a button has focus; A6 display went black.

Cleanup (both parts): earlier fork builds back, data byte-identical, settings unchanged, shut down, worktrees removed.

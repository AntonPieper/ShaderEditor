# talkback: TalkBack with the new code editor

Builds of origin/integration/project-mode at 494e4876: release (throwaway keystore, deleted) and debug. Pixel_API_37 (Android 17), TalkBack 17.0.0.889642762 (com.google.android.marvin.talkback), Gboard.
Old editor = release with Settings > New editor off (default there); new editor = release with it on; project mode = debug with it on.

How it was driven: `adb input tap` bypasses touch exploration on this image (it clicks), and agent-device gestures/snapshots and uiautomator use UiAutomation, which unbinds TalkBack. So every touch went through the emulator console as real virtual-touchscreen events (`adb -s emulator-5554 emu event send EV_ABS:ABS_MT_...`): one touch = explore, two quick touches = double tap, swipes, angular swipes (reading controls), double-tap-and-hold. Spoken text: `logcat-talkback-spoken.txt` (Actors act() SPEAK and SpeechControllerImpl "Speaking fragment"), `spoken.txt` (per step), `marks.txt` (every step with time). Screenshots: O* old editor, N* new editor (release), D* project mode (debug), S* setup.

Crash: `crash-debug-234505.txt`, debug 494e4876, IllegalStateException "No file shown" in ProjectLinks.file <- FileFixes.shown <- CodeView.layRow <- LibraryScreen.showErrors, after deleting the shown files and switching New editor off (seen once).

Slips on my side (no lasting effect): a swipe hit Gboard and opened its settings (one Back); a Back left the app once; a double tap with the "Actions" reading control ran TalkBack selection mode and replaced the text of my fixture (undone); swipes on the "Speech rate" reading control lowered TalkBack's speech rate to 62% (put back to 100%, confirmed in the log: rate=100); the Accessibility Suite notification prompt was answered "Don't allow" twice (flags cleared afterwards).

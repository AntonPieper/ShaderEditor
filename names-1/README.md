# names-1: name actions, Definition, way back, row rule (0cd2ccde6)

Debug build of origin/name-actions/9-name-talkback at 0cd2ccde6. Pixel_API_37 (Android 17, Gboard, TalkBack 17.0.0.889642762) and Pixel_API_23 (Android 6, AOSP keyboard) for the [A6] rows.
Hardware keyboard: Pixel_API_37's AVD has hw.keyboard=yes (config "qwerty/v/v", device qwerty2). With hw.keyboard=no the emulator still attaches an "AT Translated Set 2 keyboard" (config stays qwerty/v/v), so no on-screen-only state could be made on API 37. Pixel_API_23 reports no hardware keyboard: rows 1, 2 there are the on-screen state.
Files: H0 open; R19-* hardware keyboard row; R20-* keys (F12, Shift+F12, F2, Ctrl+Q, Alt+Left, Ctrl+click); R04-R09 row states (hw keyboard attached); R14-R18 Definition/way back/Look up; T21-T23 TalkBack (speech in spoken.txt, logcat-talkback-spoken.txt); R26 holding an arrow; B* Android 6.

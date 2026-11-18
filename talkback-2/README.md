# talkback-2: TalkBack re-run after #762-#775

origin/integration/project-mode at f66ae197, debug (project mode) and release (throwaway key, deleted; New editor on). Pixel_API_37, TalkBack 17.0.0.889642762, Gboard.
Driven as in the first pass: emulator-console touches (explore, double tap, swipes, angular swipes for reading controls, double-tap-and-hold). Spoken text: spoken.txt (per step), logcat-talkback-spoken.txt, marks.txt (every step with time).
Prefixes: D* debug, R* release, ITEM8* crash check, S* setup.
Tool effect (not the app): `adb shell input keycombination 113 ...` leaves Ctrl down; afterwards Gboard letters acted as Ctrl+letter (release: 'a' = select all; debug: q/w/e lost, D5-k..D5-w). After releasing Ctrl, typing worked (D5-x/y).
Slip: the reading-control search stopped on "Speech rate" and lowered TalkBack's speech rate to 46 %; put back to 100 % (log: rate=100 afterwards).

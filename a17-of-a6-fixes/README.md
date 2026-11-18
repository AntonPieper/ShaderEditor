# a17-of-a6-fixes: the five Android 6 fixes on Android 17

Pixel_API_37 (Android 17), Gboard. Fix: origin/foreman/a6-fixes-combined cf89fd13 (debug + release, throwaway key, deleted). Before: release of origin/integration/project-mode 408f940a.
marks.txt: every step with its time. item6.txt: the state after each step of item 6. Long2k-saved.frag: the 2,000-line file after 2 minutes of typing (sha1 equal to the expected text).
Screenshot prefixes: I1 (#757 typing), C*/G1/H1/K-fx (typing after Settings, rotation, Home, IME switches; before and fix), E*/F* (#758 selection, before = F2-before), J* (#759 suggestions), L* (#760 text size/font), K* (#761 editor switch), ITEM6* (item 6).
Note: `adb shell input keycombination 113 123` (Ctrl+End) leaves Gboard's next letters undelivered until the keyboard reconnects, on the before build as well (C1-before); it is a test-tool effect, the steps then use DPAD keys.

# talkback-4: TalkBack 17 hearing check of the follow-ups

Debug builds: BEFORE origin/integration/project-mode 26078ec04, AFTER origin/foreman/followups-combined 09430bf3a. Pixel_API_37, TalkBack 17.0.0.889642762, Gboard. Touches through the emulator console as in talkback-2/-3; the "one letter" is `adb -s emulator-5554 shell input keyevent 29` (hardware key A from the host).
Files: T4.frag (`#include "x.glsl"` on line 2, function `shade` used 3 times), x.glsl (2 lines). Both deleted afterwards.
spoken.txt: speech per step; marks.txt: steps with times. Prefixes: B* BEFORE, A* AFTER; 2 Reload, 3 Keep, 4 Open x.glsl, 5 Uses/rename snackbar, 6 sweep.
Slips: a notification prompt of the Accessibility Suite took one double tap and one key (answered "Don't allow", flags cleared); `uiautomator dump` unbound TalkBack once in step 5 (rebound, step redone); one Back left the app once (returned, nothing sent to the launcher).

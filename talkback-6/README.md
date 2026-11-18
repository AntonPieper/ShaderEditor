# talkback-6: conflict bar on return, investigated with TalkBack 17

Builds (debug): integration 26078ec04 (base.apk), fix/open-link-lands 5041c58d (stack.apk), fix/conflict-return-focus 7d67ca84 (fix.apk). Pixel_API_37, TalkBack 17.0.0.889642762, Gboard. A letter = `adb shell input keyevent`.
Where input goes: `dumpsys input_method` mServedView and the focused view in `dumpsys activity top`; `dumpsys window` mCurrentFocus.
Key finding: talkback-4/5 returned to the app with `am start -a MAIN -c LAUNCHER -n .../SplashActivity` (no FLAG_ACTIVITY_RESET_TASK_IF_NEEDED) into a task the device tool had started; each return stacked a new MainActivity (task sz grew to 4 here, 9 starts in talkback-5's log). A launcher-style intent (flags 0x10200000) keeps one activity. All rounds here use it.
Prefixes: BASE-* integration, S* stack, F*/H* fix, C* Go to, S7 without TalkBack. marks.txt has every step with time; spoken.txt the speech per step.

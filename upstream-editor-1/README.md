# upstream-editor-1: device check of the upstream PR branch (new editor as a setting)

Built: `origin/upstream-new-editor` at **9a45b95b91e616ee57c971ea1d791a1634276825** (the newest head at start and still at the end; f1caaa93 was not on the remote) and `upstream/master` at **ef7615d976f3ddfd3337c2df6bec90d988496d82** as BEFORE.
Both APKs are `de.markusfisch.android.shadereditor.debug`, versionCode 94 — the same package as the fork's debug app, so they replaced it (data kept). The fork's app data was backed up with run-as tar and restored byte-identically, and its earlier build reinstalled, on both emulators.

Files: `u*` / `new-*` / `master-*` = Pixel_API_37 (Android 17). `a*` / `a6new-*` / `a6master-*` = Pixel_API_23 (Android 6).

| Row | Android 17 | Android 6 |
| --- | --- | --- |
| 1 selection | u1-lp, u1-lp3 (Paste shown once clipboard has text), u1a-sel, u1b-drag, u1b-held (auto-scroll), u1d-mag-down (magnifier), u1e-swap-*, u1f-swap-down, u1g-endback-up, u1h-dt, u1h-held | a1-lp, a1-ovf, a1b-drag, a1b-held, a1c-sel, a1e-swap-down, a1g-endback-*, a1h-* |
| 2 TalkBack | spoken.txt, marks.txt, logcat-talkback-spoken.txt, u2-editing | no TalkBack installed |
| 3 words | spoken.txt (marks "3 …"), u3-file, u3-fwd-end, u3-back-end | no TalkBack installed |
| 4 class checks | – | a4-logcat.txt |
| 5 setting OFF vs master | new-{closed,open,typed,select}-{1,2}, master-…, u5-typed-zoom | a6new-…, a6master-…, a5-closed-zoom |
| 6 paste | u6-built, u6-lp, u6-p1…p5, u6-after5 | a6-built, a6-pastebar, a6-p1…p5 |
| 7 rotation | u7-pre, u7-land, u7-port, u7b-land, u7b-port | a7-land, a7-port, a7b-land, a7b-port |

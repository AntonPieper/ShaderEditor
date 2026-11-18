# semicolon-1: device check of the missing-';' hint (feat/semicolon-hint c9597dddc, PR #790)

Debug build of c9597dddc (and a release build with a throwaway key, deleted, for the old editor in row 9). Pixel_API_37 (Android 17, Gboard) all rows; Pixel_API_23 (Android 6, AOSP keyboard) rows 1, 2, 5, 9.
Shader: Semi.frag in the library folder (the given working text). Prefixes: A* Android 17 new editor, R9* Android 17 old editor (release), B* Android 6 new editor, R23* Android 6 old editor. *-row: after the change and build; *-inserted: after Insert ';'; *-undo: after one Undo (menu).
Timing (marks.txt T1-*/T5-*/TB1-*/TB5-*): from the key event to the line number turning red in the gutter, polled by screenshots (~0.4 s per poll on API 37, ~0.3 s on API 23).

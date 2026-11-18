# semicolon-2: hint lives exactly as long as its error (feat/semicolon-hint 177a26468)

Debug build of 177a26468. Pixel_API_37 (A*, Gboard) and Pixel_API_23 (B*, AOSP keyboard; cold-booted after an all-white screen, B0 before was white).
Bursts (*-b1..b6): screencaps right after the key, times in marks/README of the report (API 37 from 0.49 s, API 23 from 0.68 s; one capture takes ~0.3 s, so 0.2 s was not reachable). Row stability checked by a pixel diff of the row and the code below it (y 400-1100 device px) against the first frame: 0 differing rows unless the build result arrived.

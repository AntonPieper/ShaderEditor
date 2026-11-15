## 12. Polish of milestone 1 as built

Skill `impeccable:impeccable`, polish mode, over what landed at `c8fe83f2`: the error row with its fix and the Look up sheet. The prior critique it reads is section 11. Evidence: the screenshots in `.foreman/show/editing-m1/`, measured in pixels (1080 px = 411 dp, 2.625 px per dp), and `CodeView.java` at `c8fe83f2`. Pictures: `.foreman/show/editing-m1-polish/`, each crop of a screenshot next to the same place drawn polished.

The owner, on the gap between the message and the button: "I get why it's that way and in most cases, squishing them in one line would look bad probably. Maybe try to polish with impeccable?" So the button keeps its own line. What changes is the rhythm around it.

### 12.1 What was measured

| Case | As built | Why it looks off |
| --- | --- | --- |
| One message and the fix (screenshot 10) | Row 69 dp. Message ink 6.5 dp under the row's top; 22 dp with nothing in it; the label; 20 dp under it. The row is 4 dp padding + a 16.4 dp text line + 4 dp + a 40 dp button box + 4 dp | The 40 dp box puts 10 dp of air above its label, so message and label sit a full empty line apart, and the row's top is tighter than its middle and bottom |
| Two errors (12) | Two message lines, then the button for the first app uniform; the second one is offered after the next build | Two taps and a wait for one cause |
| Font scale 2.0 (17) | Label cut mid-word: "Add uniform mat2 camera…" (`TextUtils.ellipsize`, CodeView.java:2626) | A cut name is neither readable nor the full fix |
| Landscape (16, and the code) | The button ends at the view's edge, `scrollX + getWidth() − fixInset` (1204, 2625), about 550 dp from the end of the messages; the text wrap stops at the right inset (2501), the button does not | Far from what it fixes; under a side navigation bar or cutout it can be covered |
| After the tap (11) | Every line moved one character left ("recision", "niform", "oid") | The fix changed the sideways scroll |
| Sideways scrolling | The row is drawn from `scrollX + textLeft` to `scrollX + getWidth()` (1204 to 1210, 2589 to 2610): it stays in the viewport while the code scrolls | Right as it is: the button is always reachable. Kept |

### 12.2 Punch list: the error row

In `CodeView` (drawRow, drawFix, the row height in the method around line 2500), `CodeAppearance` and `dimens.xml`.

1. **Row padding:** `se_row_padding_vertical` 4 dp → **6 dp**, top and bottom.
2. **Message lines:** the row's line height from the font's metrics (16.4 dp) → **20 dp** (BodyMedium 14/20), each line's baseline centred in its 20 dp.
3. **The fix's layout height:** `editor_fix_height` 40 dp → **20 dp**, the label's own line (LabelLarge 14/20). `editor_fix_inset` stays 4 dp, now the gap between the last message line and the label line. A row with one message: 4 + 16.4 + 4 + 40 + 4 = 69 dp → 6 + 20 + 4 + 20 + 6 = **56 dp**. Ink gap from message to label: 22 dp → about 13 dp. Ink above the message and below the label: about 11 dp each.
4. **Pressed state:** unchanged colour (12 % `colorPrimary`, 8 dp corners), now **32 dp** tall, centred on the label's line, from the label's start − 12 dp to its end + 12 dp (`editor_fix_padding` 12 dp stays).
5. **Touch target:** **48 dp** or more, from the top of the last message line to the row's bottom (20 + 4 + 20 + 6 = 50 dp), as wide as the pressed shape + 4 dp each side. It replaces the target centred on the 40 dp box (`slack`, 2640). The message takes no taps, so the target covering it steals nothing.
6. **End edge:** the label ends at `scrollX + getWidth() − insets.right − editor_fix_inset`, the edge the text wrap already uses.
7. **Wide screens:** a new `editor_row_max_width` **560 dp**. The messages wrap at the smaller of the room and 560 dp from the code column, and the button ends there (minus 4 dp). The background still runs to the edge.
8. **Alignment and kind: kept.** The button stays end-aligned, the place of a dialog's and a snackbar's action and the right thumb's side. It stays a text button: a tonal button would be a second container inside the row.
9. **Label by rule, never cut.** Take the first of these that fits the row's text width minus 24 dp; never ellipsize. TalkBack and the accessibility action keep the full label. The three are:
   - "Add uniform mat2 cameraOrientation" (`fix_add_uniform`, as now);
   - "Add uniform cameraOrientation" (`fix_add_uniform_name` "Add uniform %1$s");
   - "Add uniform" (`fix_add_uniform_short`).
10. **Several app uniforms missing on one line** (question 1): one button adds all of them in one change and one undo step, in the order the messages name them. Label `fix_add_uniforms` "Add %1$d uniforms"; spoken `a11y_fix_add_uniforms` "Add uniforms %1$s", the "type name" pairs joined by ", " and " and ". Each added line gets the static mark.
11. **After the fix:** the sideways scroll stays where it was. Only the vertical position may change, under the rule that the caret's line keeps its place (EDITOR 14 rule 3). The static `code_selection` mark on the inserted line until the next edit stays as built.
12. **Hardware keyboard:** Alt+Enter, Android Studio's quick-fix key, applies the fix of the row under the caret's line. The drawn row gets no focus ring because the code view is one node; the accessibility action remains the other way in.
13. **The message's three-line cap is unchanged;** the label's line does not count toward it, as built.
14. **STYLE 6.10, message row, new wording:** "6 dp above and below; lines of 20 dp; a fix is a text button on its own line 4 dp under the last message line, end-aligned within 560 dp of the code, with a 48 dp target that reaches up over the message; its label shortens by rule and is never cut."

### 12.3 Punch list: the Look up sheet

1. **Wrapping code in the example as in the forms.** A line wider than the block breaks after the last comma or operator that fits, and the rest is indented 4 characters; never at the block's edge in the middle of a call. Screenshot 02 shows "0.31, length(uv));" starting at column 0.
2. **A declared app uniform gets "Go to line %1$d"** (`docs_go_to_line`, existing) as a text button at the end, 24 dp under the block, as own names do. Screenshot 06 has no way to the declaration.

Checked and kept:
- the sheet's width is capped in landscape (16);
- it opens high and scrolls at font scale 2.0 (18);
- the meaning comes first; code is at 14 sp;
- the code view's handles under the sheet are dimmed like the rest: measured accent × 0.4, exactly the 60 % dim.

### 12.4 Question for the owner

1. **Two app uniforms missing on one line** (`sheet-2-two-and-large.png`): (a) one button, "Add 2 uniforms", adds both in one undo step (default). (b) As built: one at a time, the row offering the next after the build.

### 12.5 Not verified

- The polished row was drawn, not built; label widths in the drawings are estimated, not measured with the app's paint.
- The landscape "as built" evidence is the row behind the sheet in screenshot 16 plus the code. There is no capture with a navigation bar on the right side.
- No capture of a single long message at normal size exists; that pair is drawn with the measured metrics.

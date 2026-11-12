package de.markusfisch.android.shadereditor.editor.proto;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.text.InputType;
import android.text.Layout;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.style.ForegroundColorSpan;
import android.text.style.TabStopSpan;
import android.util.Log;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.OverScroller;

import androidx.core.content.ContextCompat;

import de.markusfisch.android.shadereditor.R;
import de.markusfisch.android.shadereditor.editor.Document;
import de.markusfisch.android.shadereditor.editor.LineStates;
import de.markusfisch.android.shadereditor.editor.Text;
import de.markusfisch.android.shadereditor.highlighter.Highlight;
import de.markusfisch.android.shadereditor.syntax.GlslLexer;
import de.markusfisch.android.shadereditor.syntax.LineTokens;
import de.markusfisch.android.shadereditor.syntax.Style;

/**
 * THROWAWAY PROTOTYPE (proto/editor-core). Not production code.
 * <p>
 * A text view that lays out and draws only the lines on screen and
 * takes text from the keyboard through its own InputConnection. It
 * exists to measure; it has no handles, no magnifier, no accessibility.
 */
public final class ProtoEditorView extends View implements Document.Listener {
	static final String TAG = "EdProto";
	private static final int TAB_COLUMNS = 2;
	private static final int CACHE = 256;

	private final TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
	private final Paint plain = new Paint();
	private final int[] colors = new int[Style.values().length];
	private final LineTokens tokens = new LineTokens();
	private final OverScroller scroller;
	private final GestureDetector gestures;
	private final StaticLayout[] layouts = new StaticLayout[CACHE];
	private final String[] layoutText = new String[CACHE];
	private final int[] layoutState = new int[CACHE];
	private final Runnable blink = this::blink;
	private final int textColor;
	private final int gutterColor;
	private final int selectionColor;
	private Document doc = new Document(Text.EMPTY);
	private LineStates states = new LineStates(Text.EMPTY);
	private float charWidth;
	private int lineHeight;
	private int ascent;
	private int gutter;
	private boolean caretOn = true;
	/** Lay out every line with a StaticLayout instead of drawing it directly. */
	boolean useLayouts;
	int batch;

	// Measurement.
	long keyStart;
	long editNanos;
	String keyLabel;
	final Stats stats;

	public ProtoEditorView(Context context, Stats stats) {
		super(context);
		this.stats = stats;
		setFocusable(true);
		setFocusableInTouchMode(true);
		setVerticalScrollBarEnabled(true);
		paint.setTypeface(Typeface.MONOSPACE);
		paint.setTextSize(TypedValue.applyDimension(
				TypedValue.COMPLEX_UNIT_SP, 12,
				getResources().getDisplayMetrics()));
		for (Style style : Style.values()) {
			colors[style.ordinal()] = ContextCompat.getColor(context,
					Highlight.colorOf(style));
		}
		textColor = ContextCompat.getColor(context, R.color.editor_text);
		gutterColor = ContextCompat.getColor(context,
				R.color.editor_line_numbers);
		selectionColor = 0x664488ff;
		Paint.FontMetricsInt fm = paint.getFontMetricsInt();
		lineHeight = fm.descent - fm.ascent + fm.leading;
		ascent = -fm.ascent;
		charWidth = paint.measureText("m");
		scroller = new OverScroller(context);
		gestures = new GestureDetector(context,
				new GestureDetector.SimpleOnGestureListener() {
					@Override
					public boolean onDown(MotionEvent e) {
						scroller.forceFinished(true);
						return true;
					}

					@Override
					public boolean onSingleTapUp(MotionEvent e) {
						tap(e.getX(), e.getY());
						return true;
					}

					@Override
					public boolean onScroll(MotionEvent e1, MotionEvent e2,
							float dx, float dy) {
						scrollTo(clampX(getScrollX() + (int) dx),
								clampY(getScrollY() + (int) dy));
						return true;
					}

					@Override
					public boolean onFling(MotionEvent e1, MotionEvent e2,
							float vx, float vy) {
						fling((int) -vx, (int) -vy);
						return true;
					}
				});
	}

	long firstDrawNanos;
	long firstDrawEnd;

	/** states were built on a background thread and are handed over. */
	void setDocument(Document document, LineStates lineStates) {
		doc = document;
		states = lineStates;
		firstDrawEnd = 0;
		document.addListener(this);
		updateGutter();
		scrollTo(0, 0);
		invalidate();
	}

	Document document() {
		return doc;
	}

	void fling(int vx, int vy) {
		scroller.fling(getScrollX(), getScrollY(), vx, vy, 0, maxX(), 0, maxY());
		postInvalidateOnAnimation();
	}

	@Override
	public void onChange(Document document, Document.Change change,
			Text before) {
		int first = before.lineOf(change.start);
		states.update(document.text(), first, change.removed.lineCount() - 1,
				change.inserted.lineCount() - 1, 200);
		if (states.dirtyFrom() < document.text().lineCount()) {
			// The design continues on a background thread; the prototype
			// finishes in slices on the main thread.
			post(this::advanceStates);
		}
		updateGutter();
	}

	private void advanceStates() {
		Text text = doc.text();
		states.advance(text, 2000);
		invalidate();
		if (states.dirtyFrom() < text.lineCount()) {
			post(this::advanceStates);
		}
	}

	private void updateGutter() {
		int digits = Integer.toString(doc.text().lineCount()).length();
		gutter = (int) (charWidth * (digits + 2));
	}

	// ---- scrolling ----

	private int maxX() {
		int width = (int) (gutter + (doc.text().maxLineLength() + 8) *
				charWidth * 1.2f);
		return Math.max(0, width - getWidth());
	}

	private int maxY() {
		return Math.max(0, doc.text().lineCount() * lineHeight -
				getHeight() / 2);
	}

	private int clampX(int x) {
		return Math.max(0, Math.min(x, maxX()));
	}

	private int clampY(int y) {
		return Math.max(0, Math.min(y, maxY()));
	}

	@Override
	public void computeScroll() {
		if (scroller.computeScrollOffset()) {
			scrollTo(scroller.getCurrX(), scroller.getCurrY());
			postInvalidateOnAnimation();
		}
	}

	@Override
	protected int computeVerticalScrollRange() {
		return doc.text().lineCount() * lineHeight;
	}

	@Override
	public boolean onTouchEvent(MotionEvent event) {
		return gestures.onTouchEvent(event);
	}

	private void tap(float x, float y) {
		Text text = doc.text();
		int line = Math.max(0, Math.min(text.lineCount() - 1,
				(int) ((y + getScrollY()) / lineHeight)));
		String s = text.line(line);
		int column = columnAt(line, s, x + getScrollX() - gutter);
		int offset = text.lineStart(line) + column;
		doc.setSelection(offset, offset);
		requestFocus();
		InputMethodManager imm = (InputMethodManager) getContext()
				.getSystemService(Context.INPUT_METHOD_SERVICE);
		imm.showSoftInput(this, 0);
		selectionChanged();
	}

	private void bringCaretIntoView() {
		Text text = doc.text();
		int caret = doc.selectionEnd();
		int line = text.lineOf(caret);
		int top = line * lineHeight;
		int y = getScrollY();
		if (top < y) {
			y = top;
		} else if (top + lineHeight > y + getHeight()) {
			y = top + lineHeight - getHeight();
		}
		String s = text.line(line);
		int x = getScrollX();
		int cx = (int) xOf(line, s, caret - text.lineStart(line));
		if (cx < x) {
			x = Math.max(0, cx - (int) (4 * charWidth));
		} else if (cx + gutter + charWidth > x + getWidth()) {
			x = cx + gutter + (int) (4 * charWidth) - getWidth();
		}
		if (x != getScrollX() || y != getScrollY()) {
			scrollTo(x, y);
		}
	}

	// ---- line layout ----

	private static boolean isSimple(String s) {
		for (int i = 0, n = s.length(); i < n; ++i) {
			char c = s.charAt(i);
			if ((c < 0x20 && c != '\t') || c > 0x7e) {
				return false;
			}
		}
		return true;
	}

	private StaticLayout layoutOf(int line, String s) {
		int slot = line & (CACHE - 1);
		int state = states.startOf(line);
		// The line strings are immutable: the same string in the same
		// state has the same layout.
		if (layoutText[slot] == s && layoutState[slot] == state) {
			return layouts[slot];
		}
		GlslLexer.lexLine(s, true, state, tokens);
		SpannableString spannable = new SpannableString(s);
		for (int i = 0; i < tokens.size; ++i) {
			int color = colors[tokens.style(i).ordinal()];
			if (color != textColor) {
				spannable.setSpan(new ForegroundColorSpan(color),
						tokens.starts[i], tokens.ends[i],
						Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
			}
		}
		if (s.indexOf('\t') >= 0) {
			int tab = (int) (charWidth * TAB_COLUMNS);
			for (int i = 1; i <= 32; ++i) {
				spannable.setSpan(new TabStopSpan.Standard(tab * i), 0,
						s.length(), Spanned.SPAN_INCLUSIVE_INCLUSIVE);
			}
		}
		StaticLayout layout = StaticLayout.Builder.obtain(spannable, 0,
						s.length(), paint, 1 << 20)
				.setIncludePad(false)
				.build();
		layouts[slot] = layout;
		layoutText[slot] = s;
		layoutState[slot] = state;
		return layout;
	}

	private float xOf(int line, String s, int column) {
		if (useLayouts || !isSimple(s)) {
			return layoutOf(line, s).getPrimaryHorizontal(
					Math.min(column, s.length()));
		}
		int columns = 0;
		for (int i = 0; i < column && i < s.length(); ++i) {
			columns += s.charAt(i) == '\t' ? TAB_COLUMNS : 1;
		}
		return columns * charWidth;
	}

	private int columnAt(int line, String s, float x) {
		if (useLayouts || !isSimple(s)) {
			return layoutOf(line, s).getOffsetForHorizontal(0, x);
		}
		float at = 0;
		for (int i = 0; i < s.length(); ++i) {
			float w = (s.charAt(i) == '\t' ? TAB_COLUMNS : 1) * charWidth;
			if (x < at + w / 2) {
				return i;
			}
			at += w;
		}
		return s.length();
	}

	// ---- drawing ----

	@Override
	protected void onDraw(Canvas canvas) {
		long start = System.nanoTime();
		++frame;
		Text text = doc.text();
		int lines = text.lineCount();
		int scrollY = getScrollY();
		int scrollX = getScrollX();
		int first = Math.max(0, scrollY / lineHeight);
		int last = Math.min(lines - 1, (scrollY + getHeight()) / lineHeight);
		int selStart = Math.min(doc.selectionStart(), doc.selectionEnd());
		int selEnd = Math.max(doc.selectionStart(), doc.selectionEnd());
		int offset = first <= last ? text.lineStart(first) : 0;
		int simple = 0;
		for (int line = first; line <= last; ++line) {
			String s = text.line(line);
			int top = line * lineHeight;
			float x0 = gutter;
			if (selEnd > selStart && selEnd > offset &&
					selStart <= offset + s.length()) {
				float a = xOf(line, s, Math.max(0, selStart - offset));
				float b = selEnd > offset + s.length()
						? xOf(line, s, s.length()) + charWidth
						: xOf(line, s, selEnd - offset);
				plain.setColor(selectionColor);
				canvas.drawRect(x0 + a, top, x0 + b, top + lineHeight, plain);
			}
			if (doc.composingStart() >= 0 &&
					doc.composingEnd() > offset &&
					doc.composingStart() <= offset + s.length()) {
				float a = xOf(line, s, Math.max(0,
						doc.composingStart() - offset));
				float b = xOf(line, s, Math.min(s.length(),
						doc.composingEnd() - offset));
				plain.setColor(textColor);
				canvas.drawRect(x0 + a, top + lineHeight - 3, x0 + b,
						top + lineHeight - 1, plain);
			}
			if (useNodes && canvas.isHardwareAccelerated()) {
				drawNode(canvas, line, s, x0, top);
			} else if (useLayouts || !prepare(line, s)) {
				canvas.save();
				canvas.translate(x0, top);
				layoutOf(line, s).draw(canvas);
				canvas.restore();
			} else {
				++simple;
				drawSimple(canvas, line, s, x0, top + ascent);
			}
			if (caretOn && selStart == selEnd && selStart >= offset &&
					selStart <= offset + s.length() && isFocused()) {
				float cx = x0 + xOf(line, s, selStart - offset);
				plain.setColor(textColor);
				canvas.drawRect(cx, top, cx + 3, top + lineHeight, plain);
			}
			offset += s.length() + 1;
		}
		// The gutter stays in place when the text scrolls sideways.
		plain.setColor(0xff101010);
		canvas.drawRect(scrollX, scrollY, scrollX + gutter - charWidth / 2,
				scrollY + getHeight(), plain);
		paint.setColor(gutterColor);
		for (int line = first; line <= last; ++line) {
			int slot = line & (CACHE - 1);
			String number = numbers[slot];
			if (number == null || numberLine[slot] != line) {
				number = numbers[slot] = Integer.toString(line + 1);
				numberLine[slot] = line;
			}
			canvas.drawText(number, scrollX + gutter - charWidth -
							number.length() * charWidth,
					line * lineHeight + ascent, paint);
		}
		long now = System.nanoTime();
		if (firstDrawEnd == 0 && lines > 1) {
			firstDrawEnd = now;
			firstDrawNanos = now - start;
		}
		stats.draw(now - start, last - first + 1);
		if (keyStart != 0) {
			stats.key(keyLabel, editNanos, now - start, now - keyStart,
					lines);
			keyStart = 0;
		}
	}

	private final String[] numbers = new String[CACHE];
	private final int[] numberLine = new int[CACHE];

	/** Keep the drawing of each line in a RenderNode (API 29). */
	boolean useNodes;
	/** Find a line's RenderNode by its string, not by its number. */
	boolean nodesByIdentity;
	private static final int NODES = 512;
	private final android.graphics.RenderNode[] nodes =
			new android.graphics.RenderNode[NODES];
	private final String[] nodeText = new String[NODES];
	private final int[] nodeState = new int[NODES];
	private final int[] nodeFrame = new int[NODES];
	private int frame;

	// The text of a line is recorded once and replayed until the line
	// or the state it starts in changes. Keyed by the identity of the
	// line's string, a line that only moved (Enter above it) or came
	// back (undo, scrolling back) is not recorded again.
	private void drawNode(Canvas canvas, int line, String s, float x0,
			int top) {
		if (s.isEmpty()) {
			return;
		}
		int slot = (nodesByIdentity
				? System.identityHashCode(s) * 0x9E3779B9 >>> 23
				: line) & (NODES - 1);
		int state = states.startOf(line);
		android.graphics.RenderNode node = nodes[slot];
		if (node == null) {
			node = nodes[slot] = new android.graphics.RenderNode("line");
			nodeText[slot] = null;
		}
		if (nodeFrame[slot] == frame && nodeText[slot] != s) {
			// Two lines of this frame share the slot: draw directly.
			if (useLayouts || !prepare(line, s)) {
				canvas.save();
				canvas.translate(x0, top);
				layoutOf(line, s).draw(canvas);
				canvas.restore();
			} else {
				drawSimple(canvas, line, s, x0, top + ascent);
			}
			return;
		}
		if (nodeText[slot] != s || nodeState[slot] != state ||
				!node.hasDisplayList()) {
			int width = (int) ((s.length() + 2) * charWidth * TAB_COLUMNS) + 8;
			node.setPosition(0, 0, width, lineHeight);
			Canvas recording = node.beginRecording();
			try {
				if (useLayouts || !prepare(line, s)) {
					layoutOf(line, s).draw(recording);
				} else {
					drawSimple(recording, line, s, 0, ascent);
				}
			} finally {
				node.endRecording();
			}
			nodeText[slot] = s;
			nodeState[slot] = state;
			++recorded;
		}
		nodeFrame[slot] = frame;
		canvas.save();
		canvas.translate(x0, top);
		canvas.drawRenderNode(node);
		canvas.restore();
	}

	int recorded;

	// The runs of a simple line, cached: start, end, column and colour
	// of each piece of text that one drawText call draws. A line string
	// is immutable, so string identity plus start state is the key.
	private final int[][] runs = new int[CACHE][];
	private final int[] runCount = new int[CACHE];
	private final String[] runText = new String[CACHE];
	private final int[] runState = new int[CACHE];
	private final boolean[] runSimple = new boolean[CACHE];

	private boolean prepare(int line, String s) {
		int slot = line & (CACHE - 1);
		int state = states.startOf(line);
		if (runText[slot] == s && runState[slot] == state) {
			return runSimple[slot];
		}
		runText[slot] = s;
		runState[slot] = state;
		runCount[slot] = 0;
		if (!(runSimple[slot] = isSimple(s))) {
			return false;
		}
		GlslLexer.lexLine(s, true, state, tokens);
		int[] out = runs[slot];
		if (out == null) {
			out = runs[slot] = new int[64];
		}
		int count = 0;
		int columns = 0;
		int at = 0;
		int n = tokens.size;
		for (int i = 0; i < n; ) {
			int color = colors[tokens.style(i).ordinal()];
			int start = tokens.starts[i];
			int end = tokens.ends[i];
			// Join the tokens of one colour that only spaces separate.
			int next = i + 1;
			while (next < n &&
					colors[tokens.style(next).ordinal()] == color &&
					onlySpaces(s, end, tokens.starts[next])) {
				end = tokens.ends[next];
				++next;
			}
			i = next;
			for (; at < start; ++at) {
				columns += s.charAt(at) == '\t' ? TAB_COLUMNS : 1;
			}
			// A run is drawn without the tabs inside it.
			int runStart = start;
			for (int c = start; c <= end; ++c) {
				if (c == end || s.charAt(c) == '\t') {
					if (c > runStart) {
						if (count + 4 > out.length) {
							out = runs[slot] = java.util.Arrays.copyOf(out,
									out.length * 2);
						}
						out[count++] = runStart;
						out[count++] = c;
						out[count++] = columns;
						out[count++] = color;
						columns += c - runStart;
					}
					if (c < end) {
						columns += TAB_COLUMNS;
					}
					runStart = c + 1;
				}
			}
			at = end;
		}
		runCount[slot] = count;
		return true;
	}

	private void drawSimple(Canvas canvas, int line, String s, float x0,
			int baseline) {
		int slot = line & (CACHE - 1);
		int[] r = runs[slot];
		for (int i = 0, n = runCount[slot]; i < n; i += 4) {
			paint.setColor(r[i + 3]);
			canvas.drawText(s, r[i], r[i + 1], x0 + r[i + 2] * charWidth,
					baseline, paint);
		}
	}

	private static boolean onlySpaces(String s, int from, int to) {
		for (int i = from; i < to; ++i) {
			if (s.charAt(i) != ' ') {
				return false;
			}
		}
		return true;
	}

	private void blink() {
		caretOn = !caretOn;
		invalidate();
		postDelayed(blink, 500);
	}

	@Override
	protected void onAttachedToWindow() {
		super.onAttachedToWindow();
		postDelayed(blink, 500);
	}

	@Override
	protected void onDetachedFromWindow() {
		removeCallbacks(blink);
		super.onDetachedFromWindow();
	}

	// ---- editing, shared by keys and the input method ----

	void beginKey(String label) {
		if (keyStart == 0) {
			keyStart = System.nanoTime();
			keyLabel = label;
		}
	}

	void endKey() {
		editNanos = System.nanoTime() - keyStart;
		if (batch == 0) {
			afterEdit();
		}
	}

	private void afterEdit() {
		caretOn = true;
		bringCaretIntoView();
		selectionChanged();
		invalidate();
	}

	private void selectionChanged() {
		InputMethodManager imm = (InputMethodManager) getContext()
				.getSystemService(Context.INPUT_METHOD_SERVICE);
		imm.updateSelection(this, doc.selectionStart(), doc.selectionEnd(),
				doc.composingStart(), doc.composingEnd());
		invalidate();
	}

	void insert(CharSequence s, boolean typing) {
		int a = Math.min(doc.selectionStart(), doc.selectionEnd());
		int b = Math.max(doc.selectionStart(), doc.selectionEnd());
		doc.replace(a, b, Text.of(normalize(s)), typing);
	}

	private static CharSequence normalize(CharSequence s) {
		String string = s.toString();
		if (string.indexOf('\r') < 0 && string.indexOf(' ') < 0) {
			return string;
		}
		return string.replace("\r\n", "\n").replace('\r', '\n')
				.replace(' ', ' ');
	}

	private void backspace() {
		int a = Math.min(doc.selectionStart(), doc.selectionEnd());
		int b = Math.max(doc.selectionStart(), doc.selectionEnd());
		if (a == b) {
			if (a == 0) {
				return;
			}
			--a;
		}
		doc.replace(a, b, Text.EMPTY, true);
	}

	@Override
	public boolean onKeyDown(int keyCode, KeyEvent event) {
		long delay = SystemClock.uptimeMillis() - event.getEventTime();
		if (event.isCtrlPressed()) {
			switch (keyCode) {
				case KeyEvent.KEYCODE_A:
					beginKey("ctrl+A");
					doc.setSelection(0, doc.text().length());
					endKey();
					return true;
				case KeyEvent.KEYCODE_Z:
					beginKey("undo");
					if (event.isShiftPressed()) {
						doc.redo();
					} else {
						doc.undo();
					}
					endKey();
					return true;
				case KeyEvent.KEYCODE_V:
					beginKey("paste");
					paste();
					endKey();
					return true;
				default:
					return super.onKeyDown(keyCode, event);
			}
		}
		if (keyCode == KeyEvent.KEYCODE_DEL) {
			beginKey("key DEL +" + delay + "ms");
			backspace();
			endKey();
			return true;
		}
		if (keyCode == KeyEvent.KEYCODE_ENTER) {
			beginKey("key ENTER +" + delay + "ms");
			insert("\n", true);
			endKey();
			return true;
		}
		if (keyCode == KeyEvent.KEYCODE_TAB) {
			beginKey("key TAB +" + delay + "ms");
			insert("\t", true);
			endKey();
			return true;
		}
		if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
				keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
			int caret = doc.selectionEnd() +
					(keyCode == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
			doc.setSelection(caret, caret);
			afterEdit();
			return true;
		}
		int c = event.getUnicodeChar();
		if (c >= 0x20) {
			beginKey("key '" + (char) c + "' +" + delay + "ms");
			insert(new String(Character.toChars(c)), true);
			endKey();
			return true;
		}
		return super.onKeyDown(keyCode, event);
	}

	void undo() {
		beginKey("undo");
		doc.undo();
		endKey();
	}

	void selectAll() {
		beginKey("selectAll");
		doc.setSelection(0, doc.text().length());
		endKey();
	}

	void connectionSetSelection(int offset) {
		doc.setSelection(offset, offset);
		afterEdit();
	}

	void paste() {
		ClipboardManager clipboard = (ClipboardManager) getContext()
				.getSystemService(Context.CLIPBOARD_SERVICE);
		ClipData clip = clipboard.getPrimaryClip();
		if (clip != null && clip.getItemCount() > 0) {
			insert(clip.getItemAt(0).coerceToText(getContext()), false);
		}
	}

	// ---- input method ----

	@Override
	public boolean onCheckIsTextEditor() {
		return true;
	}

	@Override
	public InputConnection onCreateInputConnection(EditorInfo out) {
		out.inputType = ProtoActivity.rawKeys
				? InputType.TYPE_NULL
				: InputType.TYPE_CLASS_TEXT |
				InputType.TYPE_TEXT_FLAG_MULTI_LINE |
				(ProtoActivity.suggestions
						? 0
						: InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
		out.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI |
				EditorInfo.IME_FLAG_NO_FULLSCREEN |
				EditorInfo.IME_FLAG_NO_ENTER_ACTION |
				EditorInfo.IME_ACTION_NONE;
		out.initialSelStart = doc.selectionStart();
		out.initialSelEnd = doc.selectionEnd();
		out.initialCapsMode = 0;
		Log.i(TAG, "ime onCreateInputConnection inputType=0x" +
				Integer.toHexString(out.inputType) + " sel=" +
				out.initialSelStart);
		return new Connection();
	}

	private final class Connection extends BaseInputConnection {
		private Connection() {
			super(ProtoEditorView.this, true);
		}

		private void log(String call) {
			if (ProtoActivity.trace) {
				Log.i(TAG, "ime " + call);
			}
		}

		@Override
		public android.text.Editable getEditable() {
			return null;
		}

		@Override
		public boolean beginBatchEdit() {
			log("beginBatchEdit");
			if (batch++ == 0) {
				doc.beginBatch();
			}
			return true;
		}

		@Override
		public boolean endBatchEdit() {
			log("endBatchEdit");
			if (batch > 0 && --batch == 0) {
				doc.endBatch();
				afterEdit();
			}
			return batch > 0;
		}

		@Override
		public boolean commitText(CharSequence text, int newCursorPosition) {
			log("commitText \"" + shorten(text) + "\" " + newCursorPosition);
			beginKey("commitText " + text.length());
			replaceComposing(text, newCursorPosition, false);
			endKey();
			return true;
		}

		@Override
		public boolean setComposingText(CharSequence text,
				int newCursorPosition) {
			log("setComposingText \"" + shorten(text) + "\" " +
					newCursorPosition);
			beginKey("setComposingText " + text.length());
			replaceComposing(text, newCursorPosition, true);
			endKey();
			return true;
		}

		// The text replaces the composing region if there is one, else
		// the selection. A position above 0 counts from the end of the
		// new text (1 is right behind it), others back from its start.
		private void replaceComposing(CharSequence text, int position,
				boolean composing) {
			int a;
			int b;
			if (doc.composingStart() >= 0) {
				a = doc.composingStart();
				b = doc.composingEnd();
			} else {
				a = Math.min(doc.selectionStart(), doc.selectionEnd());
				b = Math.max(doc.selectionStart(), doc.selectionEnd());
			}
			Text inserted = Text.of(normalize(text));
			doc.replace(a, b, inserted, text.length() <= 1 || composing);
			int end = a + inserted.length();
			doc.setComposing(composing ? a : -1, composing ? end : -1);
			int caret = position > 0 ? end + position - 1 : a + position;
			if (caret != end) {
				doc.setSelection(caret, caret);
			}
		}

		@Override
		public boolean setComposingRegion(int start, int end) {
			log("setComposingRegion " + start + " " + end);
			doc.setComposing(start, end);
			if (batch == 0) {
				selectionChanged();
			}
			return true;
		}

		@Override
		public boolean finishComposingText() {
			log("finishComposingText");
			if (doc.composingStart() >= 0) {
				doc.setComposing(-1, -1);
				if (batch == 0) {
					selectionChanged();
				}
			}
			return true;
		}

		@Override
		public boolean deleteSurroundingText(int before, int after) {
			log("deleteSurroundingText " + before + " " + after);
			beginKey("deleteSurroundingText " + before + "," + after);
			int a = Math.min(doc.selectionStart(), doc.selectionEnd());
			int b = Math.max(doc.selectionStart(), doc.selectionEnd());
			int length = doc.text().length();
			int to = Math.min(length, b + Math.max(0, after));
			if (to > b) {
				doc.replace(b, to, Text.EMPTY, true);
			}
			int from = Math.max(0, a - Math.max(0, before));
			if (from < a) {
				doc.replace(from, a, Text.EMPTY, true);
			}
			doc.setSelection(from, from + (b - a));
			endKey();
			return true;
		}

		@Override
		public boolean deleteSurroundingTextInCodePoints(int before,
				int after) {
			// The prototype's test text is ASCII.
			return deleteSurroundingText(before, after);
		}

		@Override
		public boolean setSelection(int start, int end) {
			log("setSelection " + start + " " + end);
			doc.setSelection(start, end);
			if (batch == 0) {
				afterEdit();
			}
			return true;
		}

		@Override
		public CharSequence getTextBeforeCursor(int n, int flags) {
			int a = Math.min(doc.selectionStart(), doc.selectionEnd());
			int from = Math.max(0, a - Math.min(n, 4096));
			String s = doc.text().substring(from, a);
			log("getTextBeforeCursor " + n + " -> " + s.length());
			return s;
		}

		@Override
		public CharSequence getTextAfterCursor(int n, int flags) {
			int b = Math.max(doc.selectionStart(), doc.selectionEnd());
			int to = Math.min(doc.text().length(), b + Math.min(n, 4096));
			String s = doc.text().substring(b, to);
			log("getTextAfterCursor " + n + " -> " + s.length());
			return s;
		}

		@Override
		public CharSequence getSelectedText(int flags) {
			int a = Math.min(doc.selectionStart(), doc.selectionEnd());
			int b = Math.max(doc.selectionStart(), doc.selectionEnd());
			log("getSelectedText");
			return a == b ? null : doc.text().substring(a,
					Math.min(b, a + 4096));
		}

		@Override
		public int getCursorCapsMode(int reqModes) {
			return 0;
		}

		@Override
		public ExtractedText getExtractedText(ExtractedTextRequest request,
				int flags) {
			log("getExtractedText flags=" + flags);
			// A window around the caret, not the whole text.
			Text text = doc.text();
			int a = Math.min(doc.selectionStart(), doc.selectionEnd());
			int b = Math.max(doc.selectionStart(), doc.selectionEnd());
			int from = Math.max(0, a - 2048);
			int to = Math.min(text.length(), Math.max(b, a) + 2048);
			ExtractedText extracted = new ExtractedText();
			extracted.text = text.substring(from, to);
			extracted.startOffset = from;
			extracted.partialStartOffset = -1;
			extracted.partialEndOffset = -1;
			extracted.selectionStart = a - from;
			extracted.selectionEnd = Math.min(b, to) - from;
			extracted.flags = 0;
			return extracted;
		}

		@Override
		public boolean performContextMenuAction(int id) {
			log("performContextMenuAction " + id);
			if (id == android.R.id.selectAll) {
				doc.setSelection(0, doc.text().length());
				afterEdit();
				return true;
			} else if (id == android.R.id.paste) {
				beginKey("paste");
				paste();
				endKey();
				return true;
			}
			return false;
		}

		@Override
		public android.view.inputmethod.SurroundingText getSurroundingText(
				int before, int after, int flags) {
			Text text = doc.text();
			int a = Math.min(doc.selectionStart(), doc.selectionEnd());
			int b = Math.max(doc.selectionStart(), doc.selectionEnd());
			int from = Math.max(0, a - Math.min(before, 4096));
			int to = Math.min(text.length(), b + Math.min(after, 4096));
			log("getSurroundingText " + before + " " + after + " -> " +
					(to - from));
			return new android.view.inputmethod.SurroundingText(
					text.substring(from, to), a - from, b - from, from);
		}

		@Override
		public android.view.inputmethod.TextSnapshot takeSnapshot() {
			log("takeSnapshot");
			android.view.inputmethod.SurroundingText surrounding =
					getSurroundingText(1024, 1024, 0);
			return new android.view.inputmethod.TextSnapshot(surrounding,
					doc.composingStart(), doc.composingEnd(), 0);
		}

		@Override
		public boolean requestCursorUpdates(int mode) {
			log("requestCursorUpdates " + mode);
			return ProtoActivity.cursorUpdates;
		}

		@Override
		public boolean performPrivateCommand(String action,
				android.os.Bundle data) {
			log("performPrivateCommand " + action);
			return false;
		}

		@Override
		public boolean performEditorAction(int action) {
			log("performEditorAction " + action);
			return super.performEditorAction(action);
		}

		@Override
		public boolean setImeConsumesInput(boolean consumes) {
			log("setImeConsumesInput " + consumes);
			return super.setImeConsumesInput(consumes);
		}

		@Override
		public boolean performSpellCheck() {
			log("performSpellCheck");
			return false;
		}

		@Override
		public boolean commitCompletion(
				android.view.inputmethod.CompletionInfo text) {
			log("commitCompletion");
			return false;
		}

		@Override
		public boolean commitCorrection(
				android.view.inputmethod.CorrectionInfo info) {
			log("commitCorrection");
			return false;
		}

		@Override
		public boolean clearMetaKeyStates(int states) {
			log("clearMetaKeyStates " + states);
			return true;
		}

		@Override
		public boolean reportFullscreenMode(boolean enabled) {
			log("reportFullscreenMode " + enabled);
			return super.reportFullscreenMode(enabled);
		}

		@Override
		public boolean sendKeyEvent(KeyEvent event) {
			log("sendKeyEvent " + event.getAction() + " " +
					event.getKeyCode());
			return super.sendKeyEvent(event);
		}

		@Override
		public void closeConnection() {
			log("closeConnection");
			super.closeConnection();
			if (batch > 0) {
				batch = 0;
				doc.endBatch();
			}
		}

		private String shorten(CharSequence text) {
			return text.length() > 24
					? text.subSequence(0, 24) + "…" + text.length()
					: text.toString().replace("\n", "\\n");
		}
	}
}

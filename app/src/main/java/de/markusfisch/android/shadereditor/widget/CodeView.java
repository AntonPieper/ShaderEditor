package de.markusfisch.android.shadereditor.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import de.markusfisch.android.shadereditor.editor.Document;
import de.markusfisch.android.shadereditor.editor.LineMarks;
import de.markusfisch.android.shadereditor.editor.LineStyles;
import de.markusfisch.android.shadereditor.editor.Text;
import de.markusfisch.android.shadereditor.project.ProjectDiagnostic;

/**
 * Shows a Document: the lines on screen in the colours of its
 * LineStyles, their numbers in a gutter that stays in place when the
 * text scrolls sideways, the lines its LineMarks mark, the selection,
 * the composing region and a blinking caret. It owns no text. It
 * redraws when the document changes, and when a line on screen changes
 * colour because the continuations of the line styles or the document's
 * analysis, which it runs on one background thread, made it current.
 * From Android 10 on, the drawing of a line is recorded once and
 * replayed until the line's string or colours change.
 */
public class CodeView extends View {
	/** Draws behind the text of each line on screen. */
	public interface LineBackdrop {
		/**
		 * Draw behind line, whose text spans left to right and whose
		 * box spans top to bottom.
		 */
		void draw(Canvas canvas, int line, float left, float top,
				float right, float bottom);
	}

	private static final long BLINK = 500;

	private final CodeAppearance appearance;
	private final Paint fill = new Paint();
	private final LineCache cache;
	private final char[] digits = new char[10];
	private final Handler handler = new Handler(Looper.getMainLooper());
	private final DocumentWork work = new DocumentWork();
	private final Document.Listener listener = new Document.Listener() {
		@Override
		public void onChange(Document.Change change, Text before) {
			invalidate();
		}

		@Override
		public void onUpdate() {
			restartBlink();
			invalidate();
		}
	};
	private final Runnable blink = this::blink;
	private Document document;
	private LineStyles styles;
	private LineMarks marks;
	private boolean connected;
	private LineBackdrop backdrop;
	private boolean showLineNumbers = true;
	private int composingStart = -1;
	private int composingEnd = -1;
	private boolean caretOn;
	// The lines drawn by the last frame, from shownFirst on.
	private LineCache.Line[] shown = new LineCache.Line[0];
	private int shownFirst;
	private int shownCount;

	public CodeView(Context context) {
		this(context, null);
	}

	public CodeView(Context context, @Nullable AttributeSet attrs) {
		super(context, attrs);
		appearance = new CodeAppearance(context);
		cache = new LineCache(appearance);
		setFocusable(true);
		setFocusableInTouchMode(true);
	}

	/**
	 * Show document with its styles and, if not null, its marks, in
	 * place of the document shown before.
	 */
	public void setDocument(@NonNull Document document,
			@NonNull LineStyles styles, @Nullable LineMarks marks) {
		disconnect();
		this.document = document;
		this.styles = styles;
		this.marks = marks;
		composingStart = composingEnd = -1;
		forgetLines();
		scrollTo(0, 0);
		if (isAttachedToWindow()) {
			connect();
		}
		invalidate();
	}

	@Nullable
	public Document getDocument() {
		return document;
	}

	/**
	 * Underline the range [start, end) as the keyboard's composing text;
	 * -1 for none.
	 */
	public void setComposingRegion(int start, int end) {
		composingStart = start;
		composingEnd = end;
		invalidate();
	}

	public void setTypeface(@NonNull Typeface typeface) {
		appearance.paint.setTypeface(typeface);
		appearanceChanged();
	}

	/** Set the font size, as TextView does. */
	public void setTextSize(int unit, float size) {
		appearance.paint.setTextSize(TypedValue.applyDimension(unit, size,
				getResources().getDisplayMetrics()));
		appearanceChanged();
	}

	/** Set font features, as TextView does; ligatures, for example. */
	public void setFontFeatureSettings(@Nullable String settings) {
		appearance.paint.setFontFeatureSettings(settings);
		appearanceChanged();
	}

	/** Set the number of columns between two tab stops. */
	public void setTabWidth(int tabWidth) {
		appearance.tabWidth = tabWidth;
		appearanceChanged();
	}

	public void setShowLineNumbers(boolean showLineNumbers) {
		this.showLineNumbers = showLineNumbers;
		invalidate();
	}

	public void setLineBackdrop(@Nullable LineBackdrop backdrop) {
		this.backdrop = backdrop;
		invalidate();
	}

	public int getLineHeight() {
		return appearance.lineHeight;
	}

	@Override
	protected void onAttachedToWindow() {
		super.onAttachedToWindow();
		if (document != null) {
			connect();
		}
	}

	@Override
	protected void onDetachedFromWindow() {
		disconnect();
		super.onDetachedFromWindow();
	}

	@Override
	protected void onFocusChanged(boolean gainFocus, int direction,
			@Nullable Rect previouslyFocusedRect) {
		super.onFocusChanged(gainFocus, direction, previouslyFocusedRect);
		restartBlink();
		invalidate();
	}

	@Override
	public void onWindowFocusChanged(boolean hasWindowFocus) {
		super.onWindowFocusChanged(hasWindowFocus);
		restartBlink();
		invalidate();
	}

	@Override
	protected void onDraw(@NonNull Canvas canvas) {
		if (document == null) {
			return;
		}
		cache.nextFrame();
		CodeAppearance a = appearance;
		Text text = document.text();
		int lineCount = text.lineCount();
		int lineHeight = a.lineHeight;
		int scrollX = getScrollX();
		int scrollY = getScrollY();
		int right = scrollX + getWidth();
		float textTop = textTop();
		float textLeft = textLeft(lineCount);
		float clipLeft = showLineNumbers
				? scrollX + textLeft - a.padding / 2
				: scrollX;
		int first = CodeGeometry.lineAt(scrollY, textTop, lineHeight,
				lineCount);
		int last = CodeGeometry.lineAt(scrollY + getHeight(), textTop,
				lineHeight, lineCount);
		if (styles != null) {
			styles.show(first, last + 1);
		}
		show(first, last + 1);
		Document.Selection selection = document.selection();
		int selStart = selection.start();
		int selEnd = selection.end();
		int save = canvas.save();
		canvas.clipRect(clipLeft, scrollY, right, scrollY + getHeight());
		int lineStart = text.lineStart(first);
		for (int n = first; n <= last; ++n) {
			String s = text.line(n);
			LineCache.Line line = cache.get(s,
					styles != null ? styles.runs(n) : null, true);
			shown[n - first] = line;
			LineLayout layout = line.layout();
			float top = textTop + n * lineHeight;
			int lineEnd = lineStart + s.length();
			if (backdrop != null && !s.isEmpty()) {
				backdrop.draw(canvas, n, textLeft, top,
						textLeft + layout.width(), top + lineHeight);
			}
			ProjectDiagnostic.Severity severity =
					marks != null ? marks.severity(n) : null;
			if (severity != null) {
				fill.setColor(severity == ProjectDiagnostic.Severity.ERROR
						? a.errorColor
						: a.warningColor);
				canvas.drawRect(clipLeft, top, right, top + lineHeight, fill);
			}
			canvas.translate(textLeft, top);
			if (selStart < selEnd && selStart <= lineEnd &&
					selEnd > lineStart) {
				fill.setColor(a.selectionColor);
				int from = Math.max(selStart, lineStart) - lineStart;
				int to = Math.min(selEnd, lineEnd) - lineStart;
				if (from < to) {
					layout.drawSelection(canvas, from, to, fill);
				}
				if (selEnd > lineEnd) {
					// The line break.
					float x = layout.xOf(s.length());
					canvas.drawRect(x, 0, x + a.charWidth, lineHeight, fill);
				}
			}
			if (!s.isEmpty()) {
				line.draw(canvas, clipLeft - textLeft, right - textLeft,
						lineHeight);
			}
			if (composingStart < composingEnd &&
					composingStart <= lineEnd && composingEnd > lineStart) {
				fill.setColor(a.textColor);
				canvas.drawRect(
						layout.xOf(Math.max(composingStart, lineStart) -
								lineStart),
						lineHeight - a.underline,
						layout.xOf(Math.min(composingEnd, lineEnd) -
								lineStart),
						lineHeight, fill);
			}
			if (caretOn && selStart == selEnd && selStart >= lineStart &&
					selStart <= lineEnd) {
				float x = layout.xOf(selStart - lineStart);
				fill.setColor(a.caretColor);
				canvas.drawRect(x - a.caretWidth / 2, 0,
						x + a.caretWidth / 2, lineHeight, fill);
			}
			canvas.translate(-textLeft, -top);
			lineStart = lineEnd + 1;
		}
		canvas.restoreToCount(save);
		if (showLineNumbers) {
			drawLineNumbers(canvas, first, last, scrollX + gutterLeft(),
					textTop, lineCount);
		}
	}

	/** Return where line 0 starts. */
	float textTop() {
		return getPaddingTop();
	}

	/** Return where the numbers of the lines start. */
	float gutterLeft() {
		return getPaddingLeft();
	}

	/** Return where the text of a line starts, without scrolling. */
	float textLeft(int lineCount) {
		return gutterLeft() + (showLineNumbers
				? CodeGeometry.gutterWidth(lineCount, appearance.digitWidth,
						appearance.padding)
				: 0);
	}

	/** Return the width of the widest line known. */
	float textWidth() {
		return Math.max(cache.widest(),
				document.text().longestLineLength() * appearance.charWidth);
	}

	/** Return the layout of line n of the current text. */
	LineLayout layoutOf(int n) {
		return cache.get(document.text().line(n),
				styles != null ? styles.runs(n) : null, false).layout();
	}

	private void drawLineNumbers(Canvas canvas, int first, int last,
			float left, float textTop, int lineCount) {
		CodeAppearance a = appearance;
		Paint paint = a.paint;
		paint.setColor(a.lineNumberColor);
		float right = left + CodeGeometry.digits(lineCount) * a.digitWidth;
		for (int n = first; n <= last; ++n) {
			int start = digits.length;
			for (int number = n + 1; number > 0; number /= 10) {
				digits[--start] = (char) ('0' + number % 10);
			}
			int count = digits.length - start;
			canvas.drawText(digits, start, count,
					right - count * a.digitWidth,
					textTop + n * a.lineHeight + a.baseline, paint);
		}
	}

	private void connect() {
		document.addListener(listener);
		work.start(document, styles, this::restyled);
		connected = true;
		restartBlink();
	}

	private void disconnect() {
		handler.removeCallbacks(blink);
		if (connected) {
			document.removeListener(listener);
			work.stop();
			connected = false;
		}
	}

	// Draws again only if a line on screen has other colours now.
	private void restyled() {
		Text text = document.text();
		for (int i = 0; i < shownCount; ++i) {
			int n = shownFirst + i;
			if (n >= text.lineCount() ||
					!shown[i].shows(text.line(n), styles.runs(n))) {
				invalidate();
				return;
			}
		}
	}

	private void appearanceChanged() {
		appearance.measure();
		forgetLines();
		invalidate();
	}

	// Remembers which lines this frame draws, so restyled can tell
	// whether one of them changed.
	private void show(int from, int to) {
		if (shown.length < to - from) {
			shown = new LineCache.Line[(to - from) * 2];
		}
		shownFirst = from;
		shownCount = to - from;
	}

	private void forgetLines() {
		cache.clear();
		shownCount = 0;
	}

	private void restartBlink() {
		handler.removeCallbacks(blink);
		caretOn = document != null && isAttachedToWindow() &&
				hasWindowFocus() && isFocused() &&
				document.selection().isEmpty();
		if (caretOn) {
			handler.postDelayed(blink, BLINK);
		}
	}

	private void blink() {
		caretOn = !caretOn;
		invalidate();
		handler.postDelayed(blink, BLINK);
	}
}

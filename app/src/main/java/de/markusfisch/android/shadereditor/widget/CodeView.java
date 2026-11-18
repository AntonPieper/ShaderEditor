package de.markusfisch.android.shadereditor.widget;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RecordingCanvas;
import android.graphics.RenderNode;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.ContextMenu;
import android.view.DragEvent;
import android.view.GestureDetector;
import android.view.HapticFeedbackConstants;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MotionEvent;
import android.view.PointerIcon;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowInsets;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.EdgeEffect;
import android.widget.EditText;
import android.widget.OverScroller;
import android.widget.Toast;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.ActivityResultRegistry;
import androidx.activity.result.ActivityResultRegistryOwner;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewConfigurationCompat;
import androidx.core.view.WindowInsetsCompat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import de.markusfisch.android.shadereditor.R;
import de.markusfisch.android.shadereditor.editor.Analysis;
import de.markusfisch.android.shadereditor.editor.AnalysisScheduler;
import de.markusfisch.android.shadereditor.editor.Document;
import de.markusfisch.android.shadereditor.editor.EditCommands;
import de.markusfisch.android.shadereditor.editor.KeyCommands;
import de.markusfisch.android.shadereditor.editor.KeyboardSession;
import de.markusfisch.android.shadereditor.editor.LineMarks;
import de.markusfisch.android.shadereditor.editor.LineStyles;
import de.markusfisch.android.shadereditor.editor.Motion;
import de.markusfisch.android.shadereditor.editor.Search;
import de.markusfisch.android.shadereditor.editor.Text;
import de.markusfisch.android.shadereditor.editor.TextInput;
import de.markusfisch.android.shadereditor.project.ProjectDiagnostic;

// Shows a Document: the lines on screen in the colours of its LineStyles, their
// numbers in a gutter that stays when the text scrolls sideways, the marks of
// its LineMarks, the selection, the composing region and a blinking caret. It
// owns no text. Under the open line of the marks a row shows the messages of
// its errors; opening, closing or resizing it keeps the caret's line where it
// is on screen.
//
// Keys become KeyCommands, and the soft keyboard edits through one TextInput
// per connection. Touch, handles, the floating toolbar, drag and drop and the
// mouse work as in the platform's text views; a tap inside the selection places
// the caret, as anywhere else, and a long press past the end of a line shows
// the toolbar for the caret.
//
// From Android 10 on, a line's drawing is recorded once and replayed until its
// string or colours change; a frame records at most a third of the lines on
// screen and draws the others directly. Accessibility services see it as an
// EditText, through CodeAccessibility.
public class CodeView extends View {
	public interface LookUp {
		boolean has(@NonNull String word);

		void lookUp(@NonNull String word);
	}

	// labels come longest first; the row shows the first that fits. When even
	// the last does not, its name from cutStart to cutEnd is cut in the middle;
	// -1 for a label that is never cut.
	public record Fix(@NonNull String spoken, @NonNull List<String> labels,
			int cutStart, int cutEnd) {
		@NonNull
		public static Fix of(@NonNull String label) {
			return new Fix(label, List.of(label), -1, -1);
		}

		@NonNull
		public static Fix of(@NonNull String spoken,
				@NonNull List<String> labels) {
			return new Fix(spoken, labels, -1, -1);
		}

		@NonNull
		public static Fix cutting(@NonNull String label, int start,
				int end) {
			return new Fix(label, List.of(label), start, end);
		}
	}

	// At most MAX_FIXES fixes for the messages of a line.
	public interface RowFix {
		// Empty for none.
		@NonNull
		List<Fix> fixes(int line, @NonNull List<String> messages);

		void apply(int line, @NonNull List<String> messages, int index);

		@NonNull
		default List<String> shown(int line, @NonNull List<String> messages) {
			return messages;
		}
	}

	public static final int MAX_FIXES = 2;

	private static final int[] NO_LINES = new int[0];

	// Its fixes are on fixLines lines of their own under the text. NONE while
	// no row is open.
	private record Row(int line, @Nullable List<String> messages, int width,
			@NonNull String[] text, @NonNull List<MessageRow.Placed> fixes,
			int fixLines) {
		static final Row NONE = new Row(-1, null, 0, new String[0],
				Collections.emptyList(), 0);

		// Its height: the padding, the lines of text, 4 dp, the lines of
		// fixes with a gap between them, the padding.
		int height(@NonNull CodeAppearance a) {
			return text.length * a.rowLineHeight + 2 * a.rowPaddingVertical +
					(fixLines > 0
							? a.fixInset + fixLines * a.fixHeight +
							(fixLines - 1) * a.fixLineGap
							: 0);
		}

		float fixTop(int line, @NonNull CodeAppearance a) {
			return a.rowPaddingVertical + text.length * a.rowLineHeight +
					a.fixInset + line * (a.fixHeight + a.fixLineGap);
		}
	}

	/** The input type of today's editor with native suggestions hidden. */
	public static final int INPUT_TYPE_NO_SUGGESTIONS = InputType.TYPE_NULL;
	/** The input type of today's editor with native suggestions shown. */
	public static final int INPUT_TYPE_TEXT = InputType.TYPE_CLASS_TEXT |
			InputType.TYPE_TEXT_FLAG_MULTI_LINE |
			InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;

	private static final long BLINK = 500;
	// As the platform's insertion handle.
	private static final long CARET_HANDLE = 4000;
	// What the clipboard takes: a clip goes to the system, and from it
	// to every app that reads it, in binder transactions of at most
	// 1 MB, at up to two bytes a character.
	private static final int MAX_CLIP = 400_000;
	private static final String PROCESS_TEXT = "CodeView.processText";
	private static final int HORIZONTAL = 1;
	private static final int VERTICAL = 2;
	private static final int DRAG_WORDS = 1;
	private static final int DRAG_HANDLE = 2;
	private static final int DRAG_MOUSE = 3;
	// While text dragged by the system is over the view.
	private static final int DRAG_DROP = 4;
	// The longest selection Look up is offered for.
	private static final int MAX_WORD = 64;

	private final CodeAppearance appearance;
	private final LineGeometry lines = new LineGeometry();
	private final Paint fill = new Paint();
	private final LineCache cache;
	private final char[] digits = new char[10];
	private final Handler handler = new Handler(Looper.getMainLooper());
	private final DocumentWork work = new DocumentWork();
	private final CodeAccessibility accessibility = new CodeAccessibility(this);
	private final Document.Listener listener = new Document.Listener() {
		@Override
		public void onChange(Document.Change change, Text before) {
			accessibility.changed(change, before);
			// A drag in progress goes on in the new text.
			touch.map(change);
			edited = true;
			markedLines = NO_LINES;
			invalidate();
		}

		@Override
		public void onUpdate() {
			if (!selecting && touched != null &&
					touched != document.selection()) {
				touched = null;
				toolbar.close();
			}
			placeRow();
			edited = false;
			toolbar.moved();
			restartBlink();
			scrollInside(getScrollX(), getScrollY());
			accessibility.updated();
			invalidate();
		}
	};
	private final Runnable blink = this::blink;
	private final Runnable edgeScroll = this::edgeScroll;
	private final Runnable hideCaretHandle = () -> {
		caretHandle = false;
		invalidate();
	};
	private final SelectionHandles handles;
	private final TouchSelection touch = new TouchSelection(new Motion());
	private final KeyCommands.Clipboard clipboard =
			new KeyCommands.Clipboard() {
				@Override
				public CharSequence get() {
					ClipboardManager manager = clipboardManager();
					ClipData clip = manager != null
							? manager.getPrimaryClip()
							: null;
					return clip != null && clip.getItemCount() > 0
							? clip.getItemAt(0).coerceToText(getContext())
							: null;
				}

				@Override
				public boolean set(String text) {
					ClipboardManager manager = clipboardManager();
					try {
						if (text.length() <= MAX_CLIP) {
							manager.setPrimaryClip(
									ClipData.newPlainText(null, text));
							return true;
						}
					} catch (RuntimeException e) {
						// Too large after all.
					}
					Toast.makeText(getContext(), R.string.copy_failed,
							Toast.LENGTH_SHORT).show();
					return false;
				}
			};
	private final SelectionToolbar toolbar = new SelectionToolbar(this,
			new SelectionToolbar.Host() {
				@Override
				public void contentRect(Rect out) {
					selectionRect(out);
				}

				@Override
				public int[] actions() {
					return offered();
				}

				@Override
				public boolean act(int id) {
					return toolbarAction(id);
				}

				@Override
				public void addProcessText(Menu menu) {
					if (!document.selection().isEmpty()) {
						ProcessText.addTo(menu, getContext(),
								resultRegistry() == null);
					}
				}

				@Override
				public boolean process(Intent intent) {
					return processText(intent);
				}

				@Override
				public void closed(boolean dismissed) {
					toolbarClosed(dismissed);
				}
			});
	private final OverScroller scroller;
	private final GestureDetector gestures;
	private final int touchSlop;
	private final EdgeEffect edgeTop;
	private final EdgeEffect edgeBottom;
	private final EdgeEffect edgeLeft;
	private final EdgeEffect edgeRight;
	private Insets insets = Insets.NONE;
	// What a touch, the mouse or a drop drags, 0 while no drag is in
	// progress; where it went down, whether it moved since, and where
	// the finger is.
	private int dragging;
	private float downX;
	private float downY;
	private boolean moved;
	private float dragX;
	private float dragY;
	private boolean edgeScrolling;
	// From the finger to the place a dragged handle points at.
	private float pointDx;
	private float pointDy;
	// Under touch exploration a service sends its long press to the middle
	// of the view: a touch is moved to where the code was explored last, or
	// to the caret, by touchDx and touchDy.
	private float exploredX = Float.NaN;
	private float exploredY;
	private float touchDx;
	private float touchDy;
	private boolean caretHandle;
	// A DragMagnifier from Android 9 on, once something was dragged.
	private Object magnifier;
	// When and where the last mouse click was that may become a double
	// click.
	private long clicked;
	private float clickX;
	private float clickY;
	// The selection the last touch made, while it is the document's.
	private Document.Selection touched;
	private boolean selecting;
	private boolean caretIntoViewWhenLaidOut;
	// The axis a drag is locked to.
	private int axis;
	private Document document;
	private EditCommands commands;
	private KeyCommands keys;
	// The accent of a dead key, for the next key to combine with.
	private int deadChar;
	private int inputType = INPUT_TYPE_NO_SUGGESTIONS;
	// The keyboard's open connections. It may go on using one after it
	// was given a newer one.
	private final ArrayList<CodeInputConnection> connections =
			new ArrayList<>();
	// The connection the keyboard used last; only its updates reach it.
	private CodeInputConnection active;
	private KeyboardSession session;
	private LineStyles styles;
	private LineMarks marks;
	private boolean connected;
	private LookUp lookUp;
	private RowFix rowFix;
	// The lines marked after a fix put them in, until the next edit.
	private int[] markedLines = NO_LINES;
	// What Find marks on the lines on screen; nothing when empty.
	private Search.Query found = Search.Query.NONE;
	private Runnable documentShown;
	// The fix pressed, or -1.
	private int fixPressed = -1;
	private AnalysisScheduler.Callback analysisListener;
	private boolean tabIndents = true;
	private boolean showLineNumbers = true;
	private int composingStart = -1;
	private int composingEnd = -1;
	private boolean caretOn;
	// Where the last frame put the caret, if on screen.
	private boolean caretShown;
	private float caretX;
	private float caretTop;
	private float caretClipLeft;
	// From Android 10 on, the drawing of everything but the caret,
	// replayed when only the caret blinked.
	private Object content;
	private boolean caretOnly;
	// From Android 10 on, the numbers of the lines from gutterFirst to
	// gutterLast, whole blocks around those on screen, recorded until a
	// line of another block comes into view.
	private Object gutter;
	private int gutterFirst;
	private int gutterLast = -1;
	private int gutterDigits;
	private int gutterMarks;
	// The row under the open line of the marks: the messages it shows,
	// the width it was laid out for, and its lines of text.
	private final MessageRow.Style rowStyle = new MessageRow.Style() {
		@Override
		public float width(String text) {
			return appearance.rowPaint.measureText(text);
		}

		@Override
		public int fit(String text, int start, float width) {
			return appearance.rowPaint.breakText(text, start, text.length(),
					true, width, null);
		}

		@Override
		public String ellipsis() {
			return "\u2026";
		}

		@Override
		public String more(int count) {
			return getResources().getString(R.string.error_row_more, count);
		}
	};
	private final RectF rowRect = new RectF();
	// What the open row shows, laid out: the one value of the row,
	// made when its messages, its fixes or its width change.
	@NonNull
	private Row row = Row.NONE;
	// Whether the document changed since the last update; a row that
	// moved with its line then moved with the text, and the scroll
	// position stays.
	private boolean edited;
	// The lines with errors when the user last opened or closed the row,
	// while that choice stands.
	private int[] chosenFor;
	// The lines drawn by the last frame, from shownFirst on.
	private LineCache.Line[] shown = new LineCache.Line[0];
	private int shownFirst;
	private int shownCount;

	public CodeView(Context context) {
		this(context, null);
	}

	public CodeView(Context context, @Nullable AttributeSet attrs) {
		super(context, attrs, 0, R.style.CodeView);
		appearance = new CodeAppearance(context);
		lines.setLineHeight(appearance.lineHeight);
		cache = new LineCache(appearance);
		handles = new SelectionHandles(context, appearance.handleColor,
				getResources().getDimension(R.dimen.se_tap_target));
		setFocusable(true);
		setFocusableInTouchMode(true);
		if (getImportantForAccessibility() ==
				IMPORTANT_FOR_ACCESSIBILITY_AUTO) {
			setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
		}
		setAccessibilityDelegate(accessibility);
		scroller = new OverScroller(context);
		edgeTop = new EdgeEffect(context);
		edgeBottom = new EdgeEffect(context);
		edgeLeft = new EdgeEffect(context);
		edgeRight = new EdgeEffect(context);
		touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
		gestures = new GestureDetector(context,
				new GestureDetector.SimpleOnGestureListener() {
					@Override
					public boolean onDown(@NonNull MotionEvent e) {
						scroller.forceFinished(true);
						axis = 0;
						return true;
					}

					@Override
					public boolean onSingleTapUp(@NonNull MotionEvent e) {
						int mark = markAt(e.getX(), e.getY());
						int fix = fixAt(e.getX(), e.getY());
						if (fix >= 0) {
							applyRowFix(fix);
						} else if (mark >= 0) {
							toggleRow(mark);
						} else {
							placeCaret(e.getX(), e.getY());
						}
						return true;
					}

					@Override
					public boolean onDoubleTap(@NonNull MotionEvent e) {
						dragWords(e.getX(), e.getY(), false);
						return true;
					}

					@Override
					public void onLongPress(@NonNull MotionEvent e) {
						if (dragging != 0 || fixPressed >= 0) {
							// After a double tap, or held on a fix of the
							// row, which is not the code.
							return;
						}
						if (toolbar.isShown() && inSelection(e.getX(), e.getY())) {
							dragText();
						} else if (onText(e.getX(), e.getY())) {
							dragWords(e.getX(), e.getY(), true);
						} else {
							dragCaret(e.getX(), e.getY());
						}
					}

					@Override
					public boolean onScroll(@Nullable MotionEvent down,
							@NonNull MotionEvent e, float dx, float dy) {
						if (axis == 0) {
							axis = down != null
									? dominant(e.getX() - down.getX(),
											e.getY() - down.getY())
									: dominant(dx, dy);
						}
						drag(axis == HORIZONTAL ? dx : 0,
								axis == VERTICAL ? dy : 0, e);
						return true;
					}

					@Override
					public boolean onFling(@Nullable MotionEvent down,
							@NonNull MotionEvent e, float vx, float vy) {
						fling(axis == HORIZONTAL ? (int) -vx : 0,
								axis == VERTICAL ? (int) -vy : 0);
						return true;
					}
				});
	}

	// marks may be null.
	public void setDocument(@NonNull Document document,
			@NonNull LineStyles styles, @Nullable LineMarks marks) {
		disconnect();
		closeInput();
		toolbar.close();
		this.document = document;
		this.styles = styles;
		this.marks = marks;
		commands = new EditCommands(document);
		commands.setTabWidth(appearance.tabWidth);
		commands.setIndentation(styles.syntax().indentation());
		keys = new KeyCommands(document, commands, clipboard);
		deadChar = 0;
		composingStart = composingEnd = -1;
		touched = null;
		dragging = 0;
		forgetLines();
		lines.setRow(-1, 0);
		row = Row.NONE;
		fixPressed = -1;
		// A mark of the last document's lines is not one of these.
		markedLines = NO_LINES;
		chosenFor = null;
		scrollTo(0, 0);
		if (isAttachedToWindow()) {
			connect();
			restartInput();
		}
		accessibility.documentChanged();
		invalidate();
		if (documentShown != null) {
			documentShown.run();
		}
	}

	// None when null.
	public void setOnDocumentShown(@Nullable Runnable shown) {
		documentShown = shown;
	}

	// From the lines' own text in the frame that draws them; nothing for an
	// empty query.
	public void setFound(@NonNull Search.Query query) {
		if (!query.equals(found)) {
			found = query;
			invalidate();
		}
	}

	// From the next connection on; nothing for null.
	public void setKeyboardSession(@Nullable KeyboardSession session) {
		this.session = session;
		closeInput();
		restartInput();
	}

	// INPUT_TYPE_NO_SUGGESTIONS by default.
	public void setInputType(int inputType) {
		this.inputType = inputType;
		restartInput();
	}

	@Nullable
	public Document getDocument() {
		return document;
	}

	int inputType() {
		return inputType;
	}

	// -1 for none.
	public void setComposingRegion(int start, int end) {
		composingStart = start;
		composingEnd = end;
		invalidate();
	}

	public void setTypeface(@NonNull Typeface typeface) {
		appearance.paint.setTypeface(typeface);
		appearanceChanged();
	}

	// As TextView does.
	public void setTextSize(int unit, float size) {
		appearance.paint.setTextSize(TypedValue.applyDimension(unit, size,
				getResources().getDisplayMetrics()));
		appearanceChanged();
	}

	// As TextView does; ligatures, for example.
	public void setFontFeatureSettings(@Nullable String settings) {
		appearance.paint.setFontFeatureSettings(settings);
		appearanceChanged();
	}

	public void setTabWidth(int tabWidth) {
		appearance.tabWidth = tabWidth;
		if (commands != null) {
			commands.setTabWidth(tabWidth);
		}
		appearanceChanged();
	}

	public void setShowLineNumbers(boolean showLineNumbers) {
		this.showLineNumbers = showLineNumbers;
		placeRow();
		invalidate();
	}

	// On this thread.
	public void setAnalysisListener(
			@Nullable AnalysisScheduler.Callback listener) {
		analysisListener = listener;
	}

	// Possibly of an older revision.
	@NonNull
	public Analysis getAnalysis() {
		return work.latest();
	}

	// True by default; false leaves Tab to move the focus, as the platform
	// does.
	public void setTabIndents(boolean tabIndents) {
		this.tabIndents = tabIndents;
	}

	// For a selected word that lookUp has; none for null, the default.
	public void setLookUp(@Nullable LookUp lookUp) {
		this.lookUp = lookUp;
	}

	// None for null, the default.
	public void setRowFix(@Nullable RowFix rowFix) {
		this.rowFix = rowFix;
		fixesChanged();
	}

	// Undo or redo asked for outside the code: done is the string that
	// tells services of it, with what changed.
	public void tell(int done, @NonNull Runnable edit) {
		accessibility.tell(done, edit);
	}

	public void fixesChanged() {
		row = Row.NONE;
		placeRow();
	}

	// As ones that were just put in, until the next edit.
	public void markLines(@NonNull int[] lines) {
		markedLines = lines.clone();
		invalidate();
	}

	// With the row of the line MessageRow.choose picks, unless the user opened
	// or closed one by tapping a mark while the lines with errors were the
	// same.
	public void marksChanged() {
		if (document == null || marks == null) {
			return;
		}
		int[] failing = marks.errorLines();
		if (!Arrays.equals(failing, chosenFor)) {
			chosenFor = null;
			chooseRow();
		}
		placeRow();
		invalidate();
		accessibility.errorsChanged(failing.length > 0
				? withFix(errorOnLine(failing[0],
				marks.messages(failing[0]).get(0)), failing[0],
				marks.messages(failing[0]))
				: null);
	}

	private void chooseRow() {
		marks.open(MessageRow.choose(marks.errorLines(), marks.open(),
				document.text().lineOf(document.selection().head()),
				firstShown(), lastShown()));
	}

	// With the line, or null if it has none.
	@Nullable
	CharSequence caretLineError() {
		if (document == null || marks == null) {
			return null;
		}
		int line = document.text().lineOf(document.selection().head());
		List<String> messages = marks.messages(line);
		return messages.isEmpty()
				? null
				: errorOnLine(line, TextUtils.join("\n", messages));
	}

	private String errorOnLine(int line, String message) {
		return getResources().getString(R.string.error_on_line, line + 1,
				message);
	}

	public int getLineHeight() {
		return appearance.lineHeight;
	}

	// The caret was put somewhere else on purpose, by Go to or a link.
	public void jumped() {
		bringCaretIntoView();
		accessibility.jumped();
	}

	public void bringCaretIntoView() {
		if (document == null) {
			return;
		}
		if (getWidth() == 0 || getHeight() == 0) {
			caretIntoViewWhenLaidOut = true;
			return;
		}
		caretIntoViewWhenLaidOut = false;
		Text text = document.text();
		int head = document.selection().head();
		int n = text.lineOf(head);
		float x = textLeft(text.lineCount()) +
				layoutOf(n).xOf(head - text.lineStart(n));
		float margin = appearance.charWidth * 4;
		int scrollX = getScrollX();
		int scrollY = getScrollY();
		float visibleLeft = scrollX + textLeft(text.lineCount());
		float visibleRight = scrollX + getWidth() - insets.right;
		if (x - margin < visibleLeft) {
			scrollX -= (int) Math.ceil(visibleLeft - x + margin);
		} else if (x + margin > visibleRight) {
			scrollX += (int) Math.ceil(x + margin - visibleRight);
		}
		scrollY = revealLine(scrollY, n);
		scroller.forceFinished(true);
		scrollInside(scrollX, scrollY);
	}

	// As Find shows a match: its line stays where it is when all of it is on
	// screen, else it jumps to a third of the way down.
	public void showFound() {
		if (document == null || getWidth() == 0 || getHeight() == 0) {
			bringCaretIntoView();
			return;
		}
		int n = document.text().lineOf(document.selection().start());
		int scrollY = getScrollY();
		if (n < firstShown() || n > lastShown()) {
			int visible = getHeight() - insets.bottom - insets.top;
			scrollY = Math.round(lineTop(n) - insets.top - visible / 3f);
		}
		scroller.forceFinished(true);
		scrollInside(getScrollX(), scrollY);
		bringCaretIntoView();
		// Its start too, which a long line can leave beside the screen.
		Text text = document.text();
		int start = document.selection().start();
		float left = textLeft(text.lineCount());
		float x = left + layoutOf(n).xOf(start - text.lineStart(n)) -
				appearance.charWidth * 4;
		if (x < getScrollX() + left) {
			scrollInside((int) Math.floor(x - left), getScrollY());
		}
	}

	// In pixels per second.
	public void fling(int velocityX, int velocityY) {
		scroller.fling(getScrollX(), getScrollY(), velocityX, velocityY,
				0, maxScrollX(), 0, maxScrollY());
		postInvalidateOnAnimation();
	}

	// Kept inside the range of the text.
	@Override
	public void scrollTo(int x, int y) {
		caretIntoViewWhenLaidOut = false;
		scrollInside(x, y);
	}

	@Override
	protected void onSizeChanged(int w, int h, int oldw, int oldh) {
		super.onSizeChanged(w, h, oldw, oldh);
		// Marks set before the first size, as after a rotation, saw no
		// line on screen.
		if (oldh == 0 && document != null && marks != null &&
				chosenFor == null) {
			chooseRow();
		}
		placeRow();
		if (h < oldh && !isFocused() && !found.isEmpty()) {
			// The keyboard came up for Find's field after the jump: the
			// match goes where Find puts one.
			showFound();
		} else if (caretIntoViewWhenLaidOut || (h < oldh && isFocused())) {
			bringCaretIntoView();
		} else {
			int scrollY = getScrollY();
			// The row of the caret's line stays in view with it.
			int caret = document != null
					? document.text().lineOf(document.selection().head())
					: -1;
			if (h < oldh && caret >= 0 && caret == lines.rowLine() &&
					anchorLine() == caret) {
				scrollY = revealLine(scrollY, caret);
			}
			scrollInside(getScrollX(), scrollY);
		}
	}

	@Override
	public void computeScroll() {
		if (!scroller.computeScrollOffset()) {
			return;
		}
		int y = scroller.getCurrY();
		int velocity = (int) scroller.getCurrVelocity();
		if (y <= 0 && getScrollY() > 0) {
			edgeTop.onAbsorb(velocity);
		} else if (y >= maxScrollY() && getScrollY() < maxScrollY()) {
			edgeBottom.onAbsorb(velocity);
		}
		scrollTo(scroller.getCurrX(), y);
		postInvalidateOnAnimation();
	}

	@Override
	public boolean onHoverEvent(MotionEvent event) {
		if (!event.isFromSource(InputDevice.SOURCE_MOUSE) &&
				accessibility.exploresByTouch()) {
			exploredX = event.getX();
			exploredY = event.getY();
		}
		return super.onHoverEvent(event);
	}

	void forgetExplored() {
		exploredX = Float.NaN;
	}

	// The gesture detector calls performClick for a tap.
	@SuppressLint("ClickableViewAccessibility")
	@Override
	public boolean onTouchEvent(MotionEvent event) {
		if (event.isFromSource(InputDevice.SOURCE_MOUSE)) {
			return mouse(event) || super.onTouchEvent(event);
		}
		if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
			touchDx = touchDy = 0;
			if (document != null && accessibility.exploresByTouch()) {
				placeExploredTouch(event.getX(), event.getY());
			}
		}
		if (touchDx == 0 && touchDy == 0) {
			return touch(event);
		}
		MotionEvent moved = MotionEvent.obtain(event);
		moved.offsetLocation(touchDx, touchDy);
		try {
			return touch(moved);
		} finally {
			moved.recycle();
		}
	}

	private void placeExploredTouch(float x, float y) {
		if (Float.isNaN(exploredX)) {
			Text text = document.text();
			int head = document.selection().head();
			int n = text.lineOf(head);
			touchDx = textLeft(text.lineCount()) +
					layoutOf(n).xOf(head - text.lineStart(n)) -
					getScrollX() - x;
			touchDy = lineTop(n) + appearance.lineHeight / 2f -
					getScrollY() - y;
		} else {
			touchDx = exploredX - x;
			touchDy = exploredY - y;
		}
	}

	private boolean touch(MotionEvent event) {
		int action = event.getActionMasked();
		if (dragging == DRAG_DROP && action != MotionEvent.ACTION_DOWN) {
			// The touch that started a drag of text ends under it.
			return true;
		}
		if (action == MotionEvent.ACTION_DOWN) {
			dragging = 0;
			moved = false;
			downX = event.getX();
			downY = event.getY();
			grabHandle(downX, downY);
			fixPressed = fixAt(downX, downY);
			if (fixPressed >= 0) {
				invalidate();
			}
		} else if (fixPressed >= 0 && (action != MotionEvent.ACTION_MOVE ||
				fixAt(event.getX(), event.getY()) != fixPressed)) {
			fixPressed = -1;
			invalidate();
		}
		boolean handled = dragging == DRAG_HANDLE ||
				gestures.onTouchEvent(event);
		if (dragging != 0 && document != null) {
			dragX = event.getX();
			dragY = event.getY();
			if (action == MotionEvent.ACTION_MOVE) {
				if (moved || Math.abs(dragX - downX) > touchSlop ||
						Math.abs(dragY - downY) > touchSlop) {
					moved = true;
					toolbar.hide();
				}
				dragSelection();
			} else if (action == MotionEvent.ACTION_UP ||
					action == MotionEvent.ACTION_CANCEL) {
				drop();
			}
			return true;
		}
		if (action == MotionEvent.ACTION_UP ||
				action == MotionEvent.ACTION_CANCEL) {
			edgeTop.onRelease();
			edgeBottom.onRelease();
			edgeLeft.onRelease();
			edgeRight.onRelease();
			postInvalidateOnAnimation();
		}
		return handled || super.onTouchEvent(event);
	}

	@Override
	public boolean onGenericMotionEvent(MotionEvent event) {
		if (document != null &&
				event.isFromSource(InputDevice.SOURCE_CLASS_POINTER) &&
				event.getAction() == MotionEvent.ACTION_SCROLL) {
			float v = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
			float h = event.getAxisValue(MotionEvent.AXIS_HSCROLL);
			if ((event.getMetaState() & KeyEvent.META_SHIFT_ON) != 0) {
				h = -v;
				v = 0;
			}
			if (v != 0 || h != 0) {
				ViewConfiguration config = ViewConfiguration.get(getContext());
				scroller.forceFinished(true);
				scrollTo(getScrollX() + Math.round(h * ViewConfigurationCompat
								.getScaledHorizontalScrollFactor(config,
										getContext())),
						getScrollY() - Math.round(v * ViewConfigurationCompat
								.getScaledVerticalScrollFactor(config,
										getContext())));
				awakenScrollBars();
				return true;
			}
		}
		return super.onGenericMotionEvent(event);
	}

	@RequiresApi(Build.VERSION_CODES.N)
	@Override
	public PointerIcon onResolvePointerIcon(MotionEvent event, int index) {
		if (document != null && event.isFromSource(InputDevice.SOURCE_MOUSE) &&
				event.getX(index) >= textLeft(document.text().lineCount())) {
			return PointerIcon.getSystemIcon(getContext(),
					PointerIcon.TYPE_TEXT);
		}
		return super.onResolvePointerIcon(event, index);
	}

	@Override
	protected void onCreateContextMenu(ContextMenu menu) {
		if (document != null) {
			SelectionToolbar.fill(menu, offered(),
					item -> contextMenuAction(item.getItemId()));
		}
	}

	@Override
	public void addExtraDataToAccessibilityNodeInfo(
			@NonNull AccessibilityNodeInfo info, @NonNull String key,
			@Nullable Bundle arguments) {
		super.addExtraDataToAccessibilityNodeInfo(info, key, arguments);
		accessibility.addExtraData(info, key, arguments);
	}

	@Override
	public CharSequence getAccessibilityClassName() {
		return EditText.class.getName();
	}

	// Takes text dragged over the view, as TextView does: the caret
	// follows it, the text scrolls near an edge, and a drop moves text
	// dragged from here and inserts text from elsewhere, as one step.
	@Override
	public boolean onDragEvent(DragEvent event) {
		if (document == null) {
			return false;
		}
		switch (event.getAction()) {
			case DragEvent.ACTION_DRAG_STARTED:
				return event.getClipDescription() != null &&
						event.getClipDescription().hasMimeType("text/*");
			case DragEvent.ACTION_DRAG_ENTERED:
				requestFocus();
				// A caret without its handle follows the drag.
				handler.removeCallbacks(hideCaretHandle);
				caretHandle = false;
				int offset = offsetAt(event.getX(), event.getY());
				touch.dragHandle(offset, offset);
				startDrag(DRAG_DROP, event.getX(), event.getY());
				return true;
			case DragEvent.ACTION_DRAG_LOCATION:
				if (dragging == DRAG_DROP) {
					dragX = event.getX();
					dragY = event.getY();
					dragSelection();
				}
				return true;
			case DragEvent.ACTION_DROP:
				dragging = 0;
				drop(event);
				return true;
			default:
				if (dragging == DRAG_DROP) {
					dragging = 0;
				}
				return true;
		}
	}

	@Override
	public boolean onCheckIsTextEditor() {
		return true;
	}

	@Override
	public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
		if (document == null) {
			return null;
		}
		outAttrs.inputType = inputType;
		outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI |
				EditorInfo.IME_FLAG_NO_FULLSCREEN |
				EditorInfo.IME_FLAG_NO_ENTER_ACTION;
		Document.Selection selection = document.selection();
		outAttrs.initialSelStart = selection.anchor();
		outAttrs.initialSelEnd = selection.head();
		outAttrs.initialCapsMode = 0;
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
				selection.end() - selection.start() <= TextInput.MAX_READ) {
			Text text = document.text();
			int from = Math.max(0, selection.start() - TextInput.MAX_READ / 4);
			outAttrs.setInitialSurroundingSubText(text.substring(from,
					Math.min(text.length(),
							selection.end() + TextInput.MAX_READ / 4)), from);
		}
		CodeInputConnection connection = new CodeInputConnection(this,
				document, session);
		connections.add(connection);
		if (active == null) {
			active = connection;
		}
		return connection;
	}

	@Override
	public boolean onKeyDown(int keyCode, KeyEvent event) {
		if (keys == null ||
				(keyCode == KeyEvent.KEYCODE_TAB && !tabIndents)) {
			return super.onKeyDown(keyCode, event);
		}
		int meta = event.getMetaState();
		// Alt+Enter, the quick fix key of Android Studio, applies the
		// first fix of the caret's line.
		if ((keyCode == KeyEvent.KEYCODE_ENTER ||
				keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) &&
				event.isAltPressed() && !event.isCtrlPressed() &&
				!caretLineFixes().isEmpty()) {
			action("key", event.getAction(), keyCode, meta, null);
			if (event.getRepeatCount() == 0) {
				applyCaretLineFix(0);
			}
			return true;
		}
		int c = event.getUnicodeChar(meta);
		if ((c & KeyCharacterMap.COMBINING_ACCENT) != 0 &&
				KeyCommands.types(meta)) {
			action("key", event.getAction(), keyCode, meta, null);
			deadChar = c & KeyCharacterMap.COMBINING_ACCENT_MASK;
			return true;
		}
		if (deadChar != 0 && c != 0) {
			int combined = KeyEvent.getDeadChar(deadChar, c);
			c = combined != 0 ? combined : c;
			deadChar = 0;
		}
		String typed = c != 0 ? new String(Character.toChars(c)) : null;
		if (KeyCommands.command(keyCode, meta) ==
				KeyCommands.Command.PASTE) {
			recordClip();
		}
		action("key", event.getAction(), keyCode, meta, typed);
		return key(keyCode, meta, typed) || super.onKeyDown(keyCode, event);
	}

	// Back closes the toolbar first, before Android 13.
	@Override
	public boolean onKeyPreIme(int keyCode, KeyEvent event) {
		if (keyCode == KeyEvent.KEYCODE_BACK && toolbar.isShown()) {
			if (event.getAction() == KeyEvent.ACTION_UP) {
				toolbar.dismiss();
			}
			return true;
		}
		return super.onKeyPreIme(keyCode, event);
	}

	@Override
	public boolean onKeyMultiple(int keyCode, int count, KeyEvent event) {
		String characters = event.getCharacters();
		action("key", event.getAction(), keyCode, 0, characters);
		return keyCode == KeyEvent.KEYCODE_UNKNOWN && characters != null &&
				keys != null && key(keyCode, 0, characters) ||
				super.onKeyMultiple(keyCode, count, event);
	}

	@Override
	public boolean onKeyUp(int keyCode, KeyEvent event) {
		int meta = event.getMetaState();
		action("key", event.getAction(), keyCode, meta, null);
		return keys != null &&
				(keyCode != KeyEvent.KEYCODE_TAB || tabIndents) &&
				(KeyCommands.command(keyCode, meta) != null ||
				KeyCommands.types(meta) && event.getUnicodeChar(meta) != 0) ||
				super.onKeyUp(keyCode, event);
	}

	@Override
	public WindowInsets onApplyWindowInsets(WindowInsets windowInsets) {
		int bottom = insets.bottom;
		// Only the insets given: with the view, AndroidX below Android 11
		// adds the keyboard read from the window, which a host that made
		// room for it has already taken off the view's height.
		insets = WindowInsetsCompat.toWindowInsetsCompat(windowInsets)
				.getInsets(WindowInsetsCompat.Type.systemBars() |
						WindowInsetsCompat.Type.displayCutout() |
						WindowInsetsCompat.Type.ime());
		if (insets.bottom > bottom && isFocused()) {
			bringCaretIntoView();
		} else {
			scrollInside(getScrollX(), getScrollY());
		}
		invalidate();
		return windowInsets;
	}

	@Override
	public void draw(@NonNull Canvas canvas) {
		super.draw(canvas);
		int w = getWidth();
		int h = getHeight();
		boolean more = drawEdge(canvas, edgeTop, 0, 0, 0, w, h);
		more |= drawEdge(canvas, edgeBottom, w, h, 180, w, h);
		more |= drawEdge(canvas, edgeLeft, 0, h, 270, h, w);
		more |= drawEdge(canvas, edgeRight, w, 0, 90, h, w);
		if (more) {
			postInvalidateOnAnimation();
		}
	}

	@Override
	protected int computeVerticalScrollRange() {
		return document == null ? 0 : maxScrollY() + getHeight();
	}

	@Override
	protected int computeHorizontalScrollRange() {
		return document == null ? 0 : maxScrollX() + getWidth();
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
		closeInput();
		toolbar.close();
		super.onDetachedFromWindow();
	}

	@Override
	protected void onScrollChanged(int l, int t, int oldl, int oldt) {
		super.onScrollChanged(l, t, oldl, oldt);
		toolbar.moved();
	}

	@Override
	protected void onFocusChanged(boolean gainFocus, int direction,
			@Nullable Rect previouslyFocusedRect) {
		super.onFocusChanged(gainFocus, direction, previouslyFocusedRect);
		if (!gainFocus) {
			touched = null;
			toolbar.close();
		}
		restartBlink();
		invalidate();
	}

	@Override
	public void onWindowFocusChanged(boolean hasWindowFocus) {
		super.onWindowFocusChanged(hasWindowFocus);
		if (hasWindowFocus) {
			accessibility.windowFocused();
		}
		restartBlink();
		invalidate();
	}

	@Override
	public void invalidate() {
		caretOnly = false;
		super.invalidate();
	}

	@Override
	protected void onDraw(@NonNull Canvas canvas) {
		if (document == null) {
			return;
		}
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
				canvas.isHardwareAccelerated()) {
			drawRecorded(canvas);
		} else {
			drawText(canvas);
		}
		drawCaret(canvas);
		drawHandles(canvas);
	}

	@RequiresApi(Build.VERSION_CODES.Q)
	private void drawRecorded(Canvas canvas) {
		if (content == null) {
			content = new RenderNode("text");
		}
		RenderNode node = (RenderNode) content;
		int scrollX = getScrollX();
		int scrollY = getScrollY();
		if (!caretOnly || !node.hasDisplayList() ||
				node.getLeft() != scrollX || node.getTop() != scrollY ||
				node.getWidth() != getWidth() ||
				node.getHeight() != getHeight()) {
			node.setPosition(scrollX, scrollY, scrollX + getWidth(),
					scrollY + getHeight());
			RecordingCanvas recording = node.beginRecording();
			try {
				recording.translate(-scrollX, -scrollY);
				drawText(recording);
			} finally {
				node.endRecording();
			}
		}
		canvas.drawRenderNode(node);
	}

	private void drawCaret(Canvas canvas) {
		if (!caretOn || !caretShown) {
			return;
		}
		CodeAppearance a = appearance;
		int scrollY = getScrollY();
		int save = canvas.save();
		canvas.clipRect(caretClipLeft, scrollY, getScrollX() + getWidth(),
				scrollY + getHeight());
		fill.setColor(a.caretColor);
		canvas.drawRect(caretX - a.caretWidth / 2, caretTop,
				caretX + a.caretWidth / 2, caretTop + a.lineHeight, fill);
		canvas.restoreToCount(save);
	}

	// Draws everything but the caret, and remembers where the caret goes.
	private void drawText(Canvas canvas) {
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
		int first = lineAt(scrollY);
		int last = lineAt(scrollY + getHeight());
		if (styles != null) {
			styles.show(first, last + 1);
		}
		show(first, last + 1);
		Document.Selection selection = document.selection();
		int selStart = selection.start();
		int selEnd = selection.end();
		int save = canvas.save();
		canvas.clipRect(clipLeft, scrollY, right, scrollY + getHeight());
		caretShown = false;
		caretClipLeft = clipLeft;
		int lineStart = text.lineStart(first);
		int recordable = RecordBudget.lines(last - first + 1);
		boolean unrecorded = false;
		for (int n = first; n <= last; ++n) {
			String s = text.line(n);
			LineCache.Line line = cache.get(s,
					styles != null ? styles.runs(n) : null, true);
			shown[n - first] = line;
			LineLayout layout = line.layout();
			float top = textTop + lines.top(n);
			int lineEnd = lineStart + s.length();
			ProjectDiagnostic.Severity severity =
					marks != null ? marks.severity(n) : null;
			if (severity != null) {
				fill.setColor(severity == ProjectDiagnostic.Severity.ERROR
						? a.errorColor
						: a.warningColor);
				canvas.drawRect(clipLeft, top, right, top + lineHeight, fill);
			}
			if (marked(n)) {
				fill.setColor(a.selectionColor);
				canvas.drawRect(clipLeft, top, right, top + lineHeight, fill);
			}
			canvas.translate(textLeft, top);
			if (!found.isEmpty()) {
				drawFound(canvas, s, layout, selStart - lineStart,
						selEnd - lineStart);
			}
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
				boolean record = recordable > 0;
				if (line.needsRecording(canvas)) {
					if (record) {
						--recordable;
					} else {
						unrecorded = true;
					}
				}
				line.draw(canvas, clipLeft - textLeft, right - textLeft,
						lineHeight, record);
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
			if (selStart == selEnd && selStart >= lineStart &&
					selStart <= lineEnd) {
				caretShown = true;
				caretX = textLeft + layout.xOf(selStart - lineStart);
				caretTop = top;
			}
			canvas.translate(-textLeft, -top);
			lineStart = lineEnd + 1;
		}
		drawRow(canvas, clipLeft, right, textLeft);
		canvas.restoreToCount(save);
		if (showLineNumbers) {
			drawLineNumbers(canvas, first, last, scrollX + gutterLeft(),
					textTop, lineCount);
		}
		if (unrecorded) {
			// Record the lines drawn directly in the frames to come.
			postInvalidateOnAnimation();
		}
	}

	// Marks the matches of found in line s, but the one that is the
	// selection, which the selection marks.
	private void drawFound(Canvas canvas, String s, LineLayout layout,
			int selStart, int selEnd) {
		fill.setColor(appearance.foundColor);
		int length = found.text().length();
		for (int at = Search.inLine(s, 0, found); at > -1;
				at = Search.inLine(s, at + length, found)) {
			if (at != selStart || at + length != selEnd) {
				layout.drawSelection(canvas, at, at + length, fill);
			}
		}
	}

	float textTop() {
		return getPaddingTop() + insets.top;
	}

	// Without scrolling.
	float lineTop(int n) {
		return textTop() + lines.top(n);
	}

	// Without scrolling.
	int lineAt(float y) {
		return lines.lineAt(y - textTop(), document.text().lineCount());
	}

	float gutterLeft() {
		return getPaddingLeft() + insets.left;
	}

	// Without scrolling.
	float textLeft(int lineCount) {
		return gutterLeft() + (showLineNumbers
				? CodeGeometry.gutterWidth(lineCount, appearance.digitWidth,
						appearance.padding)
				: 0);
	}

	float textWidth() {
		return Math.max(cache.widest(),
				document.text().longestLineLength() * appearance.charWidth);
	}

	LineLayout layoutOf(int n) {
		return cache.get(document.text().line(n),
				styles != null ? styles.runs(n) : null, false).layout();
	}

	// The last line, or the row under it, can scroll up to the bottom of
	// the space above the system bars or the keyboard, and no further.
	private int maxScrollY() {
		return lines.maxScroll(document.text().lineCount(), textTop(),
				getPaddingBottom() + insets.bottom, getHeight());
	}

	private int maxScrollX() {
		float right = textLeft(document.text().lineCount()) + textWidth() +
				appearance.charWidth + getPaddingRight() + insets.right;
		return Math.max(0, (int) Math.ceil(right - getWidth()));
	}

	private void drag(float dx, float dy, MotionEvent e) {
		float x = getScrollX() + dx;
		float y = getScrollY() + dy;
		int maxX = maxScrollX();
		int maxY = maxScrollY();
		float w = getWidth();
		float h = getHeight();
		if (y < 0) {
			edgeTop.onPull(-y / h, e.getX() / w);
		} else if (y > maxY) {
			edgeBottom.onPull((y - maxY) / h, 1 - e.getX() / w);
		}
		if (x < 0) {
			edgeLeft.onPull(-x / w, 1 - e.getY() / h);
		} else if (x > maxX) {
			edgeRight.onPull((x - maxX) / w, e.getY() / h);
		}
		scrollTo(Math.round(x), Math.round(y));
		postInvalidateOnAnimation();
	}

	// For a line break the keyboard commits.
	void enter() {
		if (keys != null) {
			action("command", "enter");
			commands.enter();
			bringCaretIntoView();
		}
	}

	// For a Tab key on the screen.
	public void tab() {
		if (keys != null) {
			action("key", KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_TAB, 0, null);
			key(KeyEvent.KEYCODE_TAB, 0, null);
		}
	}

	// Returns whether it is known.
	boolean contextMenuAction(int id) {
		if (id == R.id.look_up) {
			return lookUpSelection();
		} else if (id == android.R.id.shareText) {
			share();
			return true;
		}
		KeyCommands.Command command = KeyCommands.contextMenuCommand(id);
		if (command != null) {
			menuCommand(id, command);
		}
		return command != null;
	}

	// Runs the command of context menu action id; returns whether it did
	// what it was asked.
	private boolean menuCommand(int id, KeyCommands.Command command) {
		if (keys == null) {
			return false;
		}
		if (command == KeyCommands.Command.PASTE) {
			recordClip();
		}
		action("contextMenu", id);
		boolean done = keys.run(command, false, 0);
		bringCaretIntoView();
		return done;
	}

	// Returns whether the toolbar is done. Select all keeps it, and a copy
	// leaves the caret behind the text, as the platform's do.
	boolean toolbarAction(int id) {
		if (id == R.id.look_up) {
			return lookUpSelection();
		}
		KeyCommands.Command command = KeyCommands.contextMenuCommand(id);
		boolean done;
		selecting = true;
		try {
			done = command != null
					? menuCommand(id, command)
					: id == android.R.id.shareText && share();
		} finally {
			selecting = false;
		}
		Document.Selection selection = document.selection();
		if (command == KeyCommands.Command.SELECT_ALL) {
			touched = selection;
			invalidate();
			toolbar.show();
			return false;
		}
		if (done) {
			select(selection.end(), selection.end());
			touched = null;
			invalidate();
		}
		return done;
	}

	// Offers the selection to other apps through the platform's chooser,
	// as TextView does; returns false if it did not, which it says when
	// the selection is too large to send.
	private boolean share() {
		Document.Selection selection = document.selection();
		if (selection.isEmpty() || tooLargeToSend(selection)) {
			return false;
		}
		Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain")
				.putExtra(Intent.EXTRA_TEXT, document.text().substring(
						selection.start(), selection.end()));
		try {
			getContext().startActivity(Intent.createChooser(send, null));
			return true;
		} catch (RuntimeException e) {
			// Too large after all, or nothing to share with.
			showTooLarge();
			return false;
		}
	}

	// Starts intent, an activity of another app that processes text,
	// with the selected text. The text it returns replaces the
	// selection as one step, as in TextView, if the host takes results;
	// else the intent says the text is read only. Returns false if the
	// selection is too large to send, which it says.
	private boolean processText(Intent intent) {
		Document.Selection selection = document.selection();
		if (tooLargeToSend(selection)) {
			return false;
		}
		intent.putExtra(Intent.EXTRA_PROCESS_TEXT, document.text().substring(
				selection.start(), selection.end()));
		ActivityResultRegistry registry = resultRegistry();
		AtomicReference<ActivityResultLauncher<Intent>> launcher =
				new AtomicReference<>();
		try {
			if (registry == null) {
				getContext().startActivity(intent);
				return true;
			}
			launcher.set(registry.register(PROCESS_TEXT,
					new ActivityResultContracts.StartActivityForResult(),
					result -> {
						launcher.get().unregister();
						processed(result);
					}));
			launcher.get().launch(intent);
			return true;
		} catch (RuntimeException e) {
			if (launcher.get() != null) {
				launcher.get().unregister();
			}
			showTooLarge();
			return false;
		}
	}

	// The text an activity returned replaces the selection; without
	// one, the selection collapses to its end, as in TextView.
	private void processed(ActivityResult result) {
		Intent data = result.getData();
		if (document == null || keys == null) {
			return;
		}
		if (result.getResultCode() != Activity.RESULT_OK || data == null) {
			Document.Selection selection = document.selection();
			select(selection.end(), selection.end());
			touched = null;
			invalidate();
			return;
		}
		CharSequence text = data.getCharSequenceExtra(
				Intent.EXTRA_PROCESS_TEXT);
		if (text != null && keys.paste(Text.of(text))) {
			bringCaretIntoView();
		}
	}

	private boolean tooLargeToSend(Document.Selection selection) {
		if (selection.end() - selection.start() <= MAX_CLIP) {
			return false;
		}
		showTooLarge();
		return true;
	}

	private void showTooLarge() {
		Toast.makeText(getContext(), R.string.selection_too_large,
				Toast.LENGTH_SHORT).show();
	}

	private ActivityResultRegistry resultRegistry() {
		for (Context context = getContext(); context instanceof ContextWrapper;
				context = ((ContextWrapper) context).getBaseContext()) {
			if (context instanceof ActivityResultRegistryOwner owner) {
				return owner.getActivityResultRegistry();
			}
		}
		return null;
	}

	// The user closed the toolbar, which then drops a selection as the
	// platform's does, or something else closed it.
	private void toolbarClosed(boolean dismissed) {
		if (dismissed && touched != null) {
			// The document's end: a change may have moved it since.
			int end = document.selection().end();
			select(end, end);
			touched = null;
			invalidate();
		} else {
			showCaretHandle();
		}
	}

	int[] offered() {
		Document.Selection selection = document.selection();
		ClipboardManager manager = clipboardManager();
		int[] ids = TextActions.offered(selection.start(), selection.end(),
				document.text().length(),
				manager != null && manager.hasPrimaryClip());
		if (selectedWord() == null) {
			return ids;
		}
		// After paste, before what goes to the overflow first.
		int at = 0;
		while (at < ids.length && ids[at] != android.R.id.shareText &&
				ids[at] != android.R.id.selectAll) {
			++at;
		}
		int[] withLookUp = new int[ids.length + 1];
		System.arraycopy(ids, 0, withLookUp, 0, at);
		withLookUp[at] = R.id.look_up;
		System.arraycopy(ids, at, withLookUp, at + 1, ids.length - at);
		return withLookUp;
	}

	// Returns the selection if it is one word that can be looked up,
	// else null.
	@Nullable
	private String selectedWord() {
		Document.Selection selection = document.selection();
		int start = selection.start();
		int end = selection.end();
		if (lookUp == null || start == end || end - start > MAX_WORD) {
			return null;
		}
		String word = document.text().substring(start, end);
		for (int i = 0; i < word.length(); ++i) {
			char c = word.charAt(i);
			if (!(c == '_' || (c >= 'a' && c <= 'z') ||
					(c >= 'A' && c <= 'Z') ||
					(i > 0 && c >= '0' && c <= '9'))) {
				return null;
			}
		}
		return lookUp.has(word) ? word : null;
	}

	@Nullable
	String lookUpWord() {
		return document != null ? selectedWord() : null;
	}

	// Returns whether it could.
	boolean lookUpSelection() {
		String word = selectedWord();
		if (word != null) {
			lookUp.lookUp(word);
		}
		return word != null;
	}

	private void toggleToolbar() {
		if (toolbar.isShown()) {
			toolbar.close();
		} else {
			showToolbar();
		}
	}

	// Shows the toolbar for a touch, and, as Editor does when it starts
	// one for a selection, the keyboard, to type over the selection.
	private void showToolbar() {
		handler.removeCallbacks(hideCaretHandle);
		if (dragging != 0) {
			return;
		}
		boolean starting = !toolbar.isShown();
		toolbar.show();
		if (starting && toolbar.isShown() &&
				!document.selection().isEmpty()) {
			InputMethodManager imm = inputMethodManager();
			if (imm != null) {
				imm.showSoftInput(this, 0);
			}
		}
	}

	// Puts the part of the selection on screen, and the handles under
	// it, into out, in the view's coordinates; above the view if none is.
	private void selectionRect(Rect out) {
		Text text = document.text();
		Document.Selection selection = document.selection();
		int lineCount = text.lineCount();
		int first = text.lineOf(selection.start());
		int last = text.lineOf(selection.end());
		int scrollX = getScrollX();
		int scrollY = getScrollY();
		float visibleLeft = textLeft(lineCount);
		float left = visibleLeft;
		float right = getWidth() - insets.right;
		if (first == last) {
			LineLayout layout = layoutOf(first);
			int start = text.lineStart(first);
			left += layout.xOf(selection.start() - start) - scrollX;
			right = visibleLeft + layout.xOf(selection.end() - start) -
					scrollX;
		}
		float top = lineTop(first) - scrollY;
		float bottom = lineTop(last) + appearance.lineHeight - scrollY +
				handles.height();
		left = Math.max(left, visibleLeft);
		right = Math.min(right, getWidth() - insets.right);
		top = Math.max(top, insets.top);
		bottom = Math.min(bottom, getHeight() - insets.bottom);
		if (left > right || top >= bottom) {
			out.set(0, -2, 0, -1);
		} else {
			out.set((int) left, (int) top, (int) Math.ceil(right),
					(int) Math.ceil(bottom));
		}
	}

	// null for a character of a line that is not on screen. A character of
	// a line on screen that runs past its edge has its place beyond the
	// edge: TalkBack finds lines by the places of characters, and would
	// cut such a line into pieces at each character it gets no place for.
	void characterBounds(int offset, RectF[] out) {
		Text text = document.text();
		int lineCount = text.lineCount();
		int[] location = new int[2];
		getLocationOnScreen(location);
		float left = textLeft(lineCount);
		float dx = location[0] - getScrollX();
		float dy = location[1] - getScrollY();
		for (int i = 0; i < out.length; ++i) {
			int n = text.lineOf(offset + i);
			int column = offset + i - text.lineStart(n);
			LineLayout layout = layoutOf(n);
			float x = left + layout.xOf(column);
			float right = left + layout.xOf(Math.min(column + 1,
					text.line(n).length()));
			float top = lineTop(n);
			float bottom = top + appearance.lineHeight;
			boolean shown = bottom > getScrollY() + insets.top &&
					top < getScrollY() + getHeight() - insets.bottom;
			out[i] = shown
					? new RectF(x + dx, top + dy, right + dx, bottom + dy)
					: null;
		}
	}

	int pageLines() {
		return Math.max(1, (getHeight() - insets.top - insets.bottom) /
				appearance.lineHeight - 1);
	}

	// With its handles and toolbar if shown; scrolls the head into view.
	void selectForAccessibility(int anchor, int head, boolean shown) {
		toolbar.close();
		select(anchor, head);
		touched = shown && anchor != head ? document.selection() : null;
		if (touched != null) {
			showToolbar();
		}
		bringCaretIntoView();
	}

	// null for none, as one step with the caret behind; false if the document
	// refused it.
	boolean replaceAll(@Nullable CharSequence text) {
		Text replacement = Text.of(text != null ? text : "");
		action("setText", replacement.toString());
		document.beginBatch();
		try {
			if (!document.replace(0, document.text().length(), replacement)) {
				return false;
			}
			document.select(replacement.length(), replacement.length());
		} finally {
			document.endBatch();
		}
		bringCaretIntoView();
		return true;
	}

	// As an accessibility service's tap on an EditText: focus and show the
	// keyboard; the caret stays.
	boolean clickForAccessibility() {
		if (!isFocused()) {
			requestFocus();
		}
		performClick();
		InputMethodManager imm = inputMethodManager();
		if (imm != null) {
			imm.showSoftInput(this, 0);
		}
		return true;
	}

	// As an accessibility service's long press on an EditText: show or hide the
	// toolbar for the selection or the caret.
	boolean longClickForAccessibility() {
		sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_LONG_CLICKED);
		performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
		touched = document.selection();
		toggleToolbar();
		return true;
	}

	// dx and dy are -1, 0 or 1; returns whether the view scrolled.
	boolean scrollByPage(int dx, int dy) {
		int x = getScrollX();
		int y = getScrollY();
		scroller.forceFinished(true);
		scrollTo(x + dx * Math.round(getWidth() - insets.right -
						textLeft(document.text().lineCount())),
				y + dy * (getHeight() - insets.top - insets.bottom));
		awakenScrollBars();
		return x != getScrollX() || y != getScrollY();
	}

	void note(String name, Object answer, Object... args) {
		if (session != null) {
			session.note(name, answer, args);
		}
	}

	private void action(String name, Object... args) {
		if (session != null) {
			session.action(name, args);
		}
	}

	private void recordClip() {
		if (session != null) {
			session.action("clip", clipboard.get());
		}
	}

	private void restartInput() {
		InputMethodManager imm = inputMethodManager();
		if (imm != null) {
			imm.restartInput(this);
		}
	}

	// The keyboard does not go back to the older ones, so they are closed.
	void use(CodeInputConnection connection) {
		if (active != connection) {
			active = connection;
			for (int i = connections.indexOf(connection); i-- > 0; ) {
				connections.get(i).closeConnection();
			}
			setComposingRegion(connection.composingStart(),
					connection.composingEnd());
		}
	}

	// If the keyboard uses connection.
	void updateSelection(CodeInputConnection connection, int selStart,
			int selEnd, int composingStart, int composingEnd) {
		InputMethodManager imm = inputMethodManager();
		if (connection == active && imm != null) {
			setComposingRegion(composingStart, composingEnd);
			imm.updateSelection(this, selStart, selEnd, composingStart,
					composingEnd);
		}
	}

	void updateExtractedText(CodeInputConnection connection, int token,
			TextInput.Window window) {
		InputMethodManager imm = inputMethodManager();
		if (connection == active && imm != null) {
			imm.updateExtractedText(this, token,
					CodeInputConnection.extracted(window));
		}
	}

	void closed(CodeInputConnection connection) {
		connections.remove(connection);
		if (connection == active) {
			active = connections.isEmpty()
					? null
					: connections.get(connections.size() - 1);
			setComposingRegion(-1, -1);
		}
	}

	private void closeInput() {
		for (int i = connections.size(); i-- > 0; ) {
			connections.get(i).closeConnection();
		}
	}

	private InputMethodManager inputMethodManager() {
		return (InputMethodManager) getContext().getSystemService(
				Context.INPUT_METHOD_SERVICE);
	}

	private boolean key(int keyCode, int meta, String typed) {
		if (!keys.keyDown(keyCode, meta, typed, pageLines())) {
			return false;
		}
		bringCaretIntoView();
		return true;
	}

	private ClipboardManager clipboardManager() {
		return (ClipboardManager) getContext().getSystemService(
				Context.CLIPBOARD_SERVICE);
	}

	private boolean drawEdge(Canvas canvas, EdgeEffect edge, int x, int y,
			int degrees, int width, int height) {
		if (edge.isFinished()) {
			return false;
		}
		int save = canvas.save();
		canvas.translate(getScrollX() + x, getScrollY() + y);
		canvas.rotate(degrees);
		edge.setSize(width, height);
		boolean more = edge.draw(canvas);
		canvas.restoreToCount(save);
		return more;
	}

	private void placeCaret(float x, float y) {
		if (document == null) {
			return;
		}
		toolbar.close();
		int offset = offsetAt(x, y);
		select(offset, offset);
		showCaretHandle();
		requestFocus();
		InputMethodManager imm = inputMethodManager();
		if (imm != null) {
			imm.showSoftInput(this, 0);
		}
		performClick();
	}

	private void dragWords(float x, float y, boolean pressed) {
		if (document == null) {
			return;
		}
		toolbar.close();
		touch.dragWords(document.text(), offsetAt(x, y));
		select(touch.anchor(), touch.head());
		requestFocus();
		if (pressed) {
			performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
		}
		startDrag(DRAG_WORDS, x, y);
	}

	private void grabHandle(float x, float y) {
		int which = handles.at(x + getScrollX(), y + getScrollY());
		if (which < 0) {
			return;
		}
		Document.Selection shown = touch.handles(touched,
				document.selection(), false);
		if (shown == null) {
			return;
		}
		int head = which == SelectionHandles.START
				? shown.start()
				: shown.end();
		touch.dragHandle(shown.isEmpty() ? head : shown.start() +
				shown.end() - head, head);
		// The handle points at the bottom of a line; the drag at its
		// middle.
		pointDx = handles.x(which) - getScrollX() - x;
		pointDy = handles.y(which) - getScrollY() -
				appearance.lineHeight / 2f - y;
		handler.removeCallbacks(hideCaretHandle);
		startDrag(DRAG_HANDLE, x, y);
	}

	// Places the caret, with its handle, where a long press past the
	// end of a line was; a drag after it moves the caret.
	private void dragCaret(float x, float y) {
		if (document == null) {
			return;
		}
		toolbar.close();
		int offset = offsetAt(x, y);
		touch.dragHandle(offset, offset);
		select(offset, offset);
		requestFocus();
		performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
		caretHandle = true;
		startDrag(DRAG_WORDS, x, y);
	}

	private void startDrag(int what, float x, float y) {
		if (what != DRAG_HANDLE) {
			pointDx = pointDy = 0;
		}
		dragging = what;
		dragX = x;
		dragY = y;
		scroller.forceFinished(true);
		if (getParent() != null) {
			getParent().requestDisallowInterceptTouchEvent(true);
		}
	}

	// The finger lifted from a drag. The toolbar shows for a selection,
	// for the caret after a long press, again if it showed before, and
	// a tap on the caret's handle shows or hides it.
	private void drop() {
		boolean tapped = dragging == DRAG_HANDLE && !moved;
		boolean pressed = dragging == DRAG_WORDS;
		dragging = 0;
		dismissMagnifier();
		invalidate();
		if (touched == null) {
			return;
		}
		showCaretHandle();
		if (tapped && touched.isEmpty()) {
			toggleToolbar();
		} else if (!touched.isEmpty() || pressed || toolbar.isShown()) {
			showToolbar();
		}
		if (!touched.isEmpty() && accessibility.exploresByTouch()) {
			accessibility.selectedByTouch();
		}
	}

	// Selects up to the place of the finger, and scrolls while it is
	// near an edge.
	private void dragSelection() {
		Text text = document.text();
		if (touch.dragTo(text, offsetAt(dragX + pointDx, dragY + pointDy))) {
			select(touch.anchor(), touch.head());
		}
		if (dragging == DRAG_WORDS || (dragging == DRAG_HANDLE && moved)) {
			magnify();
		}
		if (!edgeScrolling && (edgeStepX() != 0 || edgeStepY() != 0)) {
			edgeScrolling = true;
			postOnAnimation(edgeScroll);
		}
	}

	// Shows the magnifier over the line of the dragged end, at the
	// finger, from Android 9 on, as the platform's text views do: kept
	// over the text of that line beyond the other end, hidden when the
	// finger is far from it, the line out of view or its text too tall.
	private void magnify() {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
			return;
		}
		if (magnifier == null) {
			magnifier = new DragMagnifier(this);
		}
		DragMagnifier m = (DragMagnifier) magnifier;
		Text text = document.text();
		int head = touch.head();
		int anchor = touch.anchor();
		int n = text.lineOf(head);
		int start = text.lineStart(n);
		LineLayout layout = layoutOf(n);
		float visibleLeft = textLeft(text.lineCount());
		float left = visibleLeft - getScrollX();
		float right = left + layout.width();
		if (anchor != head && text.lineOf(anchor) == n) {
			float x = left + layout.xOf(anchor - start);
			if (head > anchor) {
				left = x;
			} else {
				right = x;
			}
		}
		float x = TouchSelection.magnifierX(dragX, Math.max(left,
				visibleLeft), right, m.halfWidth());
		float y = lineTop(n) + appearance.lineHeight / 2f - getScrollY();
		if (Float.isNaN(x) || !m.fits(appearance.lineHeight) ||
				y < insets.top || y > getHeight() - insets.bottom) {
			m.dismiss();
		} else {
			m.show(x, y);
		}
	}

	private void dismissMagnifier() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
				magnifier != null) {
			((DragMagnifier) magnifier).dismiss();
		}
	}

	// A click places the caret, a double click selects a word, with
	// Shift held a click extends the selection; a drag after each
	// selects by character or by word. A secondary click opens the
	// context menu. A mouse shows no handles.
	private boolean mouse(MotionEvent event) {
		if (document == null) {
			return false;
		}
		float x = event.getX();
		float y = event.getY();
		switch (event.getActionMasked()) {
			case MotionEvent.ACTION_DOWN:
				requestFocus();
				toolbar.close();
				touched = null;
				int mark = markAt(x, y);
				int fix = fixAt(x, y);
				if (fix >= 0) {
					applyRowFix(fix);
					return true;
				} else if (mark >= 0 &&
						!event.isButtonPressed(MotionEvent.BUTTON_SECONDARY)) {
					toggleRow(mark);
					return true;
				}
				startDrag(DRAG_MOUSE, x, y);
				if (event.isButtonPressed(MotionEvent.BUTTON_SECONDARY)) {
					dragging = 0;
					contextClick(x, y);
					return true;
				}
				click(event, offsetAt(x, y));
				return true;
			case MotionEvent.ACTION_MOVE:
				if (dragging == DRAG_MOUSE) {
					dragX = x;
					dragY = y;
					dragSelection();
				}
				return true;
			case MotionEvent.ACTION_UP:
			case MotionEvent.ACTION_CANCEL:
				dragging = 0;
				return true;
			default:
				return false;
		}
	}

	private void click(MotionEvent event, int offset) {
		long time = event.getEventTime();
		float x = event.getX();
		float y = event.getY();
		boolean twice = time - clicked < ViewConfiguration.getDoubleTapTimeout() &&
				Math.abs(x - clickX) < touchSlop &&
				Math.abs(y - clickY) < touchSlop;
		// A third click is a first one again.
		clicked = twice ? 0 : time;
		clickX = x;
		clickY = y;
		if (twice) {
			touch.dragWords(document.text(), offset);
		} else if ((event.getMetaState() & KeyEvent.META_SHIFT_ON) != 0) {
			touch.dragChars(document.selection().anchor(), offset);
		} else {
			touch.dragChars(offset, offset);
		}
		select(touch.anchor(), touch.head());
		InputMethodManager imm = inputMethodManager();
		if (imm != null) {
			imm.showSoftInput(this, 0);
		}
	}

	// Opens the context menu at x, y, before Android 7 the toolbar, for
	// the selection if x, y is in it, else for a caret placed there.
	private void contextClick(float x, float y) {
		int offset = offsetAt(x, y);
		Document.Selection selection = document.selection();
		if (offset < selection.start() || offset > selection.end()) {
			select(offset, offset);
		}
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
			touched = null;
			showContextMenu(x, y);
		} else {
			// Not the keyboard, which a click does not show.
			toolbar.show();
		}
	}

	private void edgeScroll() {
		edgeScrolling = false;
		if (dragging == 0 || document == null) {
			return;
		}
		int x = getScrollX();
		int y = getScrollY();
		scrollInside(x + edgeStepX(), y + edgeStepY());
		if (x != getScrollX() || y != getScrollY()) {
			dragSelection();
		}
	}

	private int edgeStepX() {
		return edgeStep(dragX, textLeft(document.text().lineCount()),
				getWidth() - insets.right);
	}

	private int edgeStepY() {
		return edgeStep(dragY, insets.top, getHeight() - insets.bottom);
	}

	// Returns how far to scroll in a frame for a finger at, from 0 when it
	// is two lines from the edge low or high to a line beyond it.
	private int edgeStep(float at, float low, float high) {
		float zone = appearance.lineHeight * 2f;
		float depth = at < low + zone
				? at - low - zone
				: at > high - zone ? at - high + zone : 0;
		return Math.round(Math.max(-1, Math.min(1, depth / zone)) *
				appearance.lineHeight);
	}

	// Starts a drag of the selection, as TextView does on a long press
	// inside it while its toolbar shows: other apps may take a copy, and
	// a drop into this view moves it.
	private void dragText() {
		Document.Selection selection = document.selection();
		if (tooLargeToSend(selection)) {
			return;
		}
		int start = selection.start();
		int end = selection.end();
		Text all = document.text();
		String text = all.substring(start, end);
		Document.Selection shown = touch.shadowOf(all, start, end);
		int n = all.lineOf(shown.start());
		int lineStart = all.lineStart(n);
		LineLayout layout = layoutOf(n);
		DragShadowBuilder builder = new TextShadow(layout,
				layout.xOf(shown.start() - lineStart),
				layout.xOf(shown.end() - lineStart), appearance.lineHeight,
				appearance);
		toolbar.close();
		ClipData clip = ClipData.newPlainText(null, text);
		TextDrag drag = new TextDrag(document.text(), start, end);
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
			// Opaque: a card that lets the code through is hard to read.
			startDragAndDrop(clip, builder, drag, View.DRAG_FLAG_GLOBAL |
					View.DRAG_FLAG_OPAQUE);
		} else {
			startDrag(clip, builder, drag, 0);
		}
	}

	// Moves text dragged from this document to the drop, or inserts
	// what another drag carries there, as one step with the caret
	// behind, as TextView does; a drop the document refuses changes
	// nothing.
	private void drop(DragEvent event) {
		int offset = offsetAt(event.getX(), event.getY());
		if (event.getLocalState() instanceof TextDrag drag &&
				drag.text() == document.text()) {
			int to = TouchSelection.dropAt(drag.start(), drag.end(), offset);
			if (to < 0) {
				return;
			}
			Text moved = document.text().slice(drag.start(), drag.end());
			int caret = to + moved.length();
			document.beginBatch();
			try {
				document.replace(drag.start(), drag.end(), Text.EMPTY);
				document.replace(to, to, moved);
				document.select(caret, caret);
			} finally {
				document.endBatch();
			}
		} else {
			ClipData clip = event.getClipData();
			StringBuilder text = new StringBuilder();
			for (int i = 0; clip != null && i < clip.getItemCount(); ++i) {
				if (i > 0) {
					text.append('\n');
				}
				text.append(clip.getItemAt(i).coerceToText(getContext()));
			}
			if (text.length() == 0 || keys == null) {
				return;
			}
			select(offset, offset);
			keys.paste(Text.of(text));
		}
		touched = null;
		bringCaretIntoView();
	}

	// What a drag of text started here carries: the text it was taken
	// from, which the document still has if nothing changed it since,
	// and where.
	private record TextDrag(Text text, int start, int end) {
	}

	// Returns whether x and y of the view are inside the selection the
	// last touch made.
	private boolean inSelection(float x, float y) {
		if (document == null || touched == null || touched.isEmpty()) {
			return false;
		}
		int offset = offsetAt(x, y);
		return offset >= touched.start() && offset <= touched.end();
	}

	// Returns whether x and y of the view are on the text of a line, not
	// past its end or below the last.
	private boolean onText(float x, float y) {
		Text text = document.text();
		int n = lineAt(y + getScrollY());
		return y + getScrollY() < lineTop(n) + appearance.lineHeight &&
				x + getScrollX() - textLeft(text.lineCount()) <
						layoutOf(n).width();
	}

	private int offsetAt(float x, float y) {
		Text text = document.text();
		int n = lineAt(y + getScrollY());
		return touch.offsetAt(text, n,
				x + getScrollX() - textLeft(text.lineCount()), layoutOf(n));
	}

	private void select(int anchor, int head) {
		action("select", anchor, head);
		selecting = true;
		try {
			document.select(anchor, head);
		} finally {
			selecting = false;
		}
		touched = dragging == DRAG_MOUSE ? null : document.selection();
	}

	private void showCaretHandle() {
		handler.removeCallbacks(hideCaretHandle);
		if (touched != null && touched.isEmpty()) {
			caretHandle = true;
			handler.postDelayed(hideCaretHandle, CARET_HANDLE);
			invalidate();
		}
	}

	// Draws the handles of the selection the last touch made while it is
	// the document's, those whose place is in view.
	private void drawHandles(Canvas canvas) {
		handles.clear();
		Document.Selection shown = touch.handles(touched,
				document.selection(), dragging != 0);
		if (shown == null || (shown.isEmpty() && !caretHandle) ||
				!isFocused()) {
			return;
		}
		if (shown.isEmpty()) {
			placeHandle(-1, shown.head());
		} else {
			placeHandle(SelectionHandles.START, shown.start());
			placeHandle(SelectionHandles.END, shown.end());
		}
		handles.draw(canvas);
	}

	private void placeHandle(int which, int offset) {
		Text text = document.text();
		int n = text.lineOf(offset);
		int scrollX = getScrollX();
		int scrollY = getScrollY();
		float textLeft = textLeft(text.lineCount());
		float bottom = lineTop(n) + appearance.lineHeight;
		if (bottom <= scrollY + insets.top ||
				bottom > scrollY + getHeight() - insets.bottom) {
			return;
		}
		float x = textLeft + layoutOf(n).xOf(offset - text.lineStart(n));
		// Not under the line numbers.
		if (x < scrollX + textLeft - 1 ||
				x > scrollX + getWidth() - insets.right) {
			return;
		}
		if (which < 0) {
			handles.caret(x, bottom);
		} else {
			handles.end(which, x, bottom);
		}
	}

	private void scrollInside(int x, int y) {
		super.scrollTo(document == null ? 0 : clamp(x, maxScrollX()),
				document == null ? 0 : clamp(y, maxScrollY()));
	}

	private static int dominant(float dx, float dy) {
		return Math.abs(dx) > Math.abs(dy) ? HORIZONTAL : VERTICAL;
	}

	private static int clamp(int value, int max) {
		return Math.max(0, Math.min(value, max));
	}

	private void drawLineNumbers(Canvas canvas, int first, int last,
			float left, float textTop, int lineCount) {
		int digitCount = CodeGeometry.digits(lineCount);
		boolean recorded = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
				canvas.isHardwareAccelerated();
		if (recorded) {
			first = CodeGeometry.numbersFrom(first);
			last = CodeGeometry.numbersTo(last, lineCount);
		}
		float top = textTop + lines.top(first);
		int save = canvas.save();
		if (recorded) {
			// The lines of the blocks that are out of view.
			int scrollX = getScrollX();
			int scrollY = getScrollY();
			canvas.clipRect(scrollX, scrollY, scrollX + getWidth(),
					scrollY + getHeight());
		}
		canvas.translate(left, top);
		if (recorded) {
			drawRecordedNumbers(canvas, first, last, digitCount);
		} else {
			drawNumbers(canvas, first, last, digitCount);
		}
		canvas.restoreToCount(save);
	}

	@RequiresApi(Build.VERSION_CODES.Q)
	private void drawRecordedNumbers(Canvas canvas, int first, int last,
			int digitCount) {
		if (gutter == null) {
			gutter = new RenderNode("gutter");
		}
		RenderNode node = (RenderNode) gutter;
		int markChanges = marks != null ? marks.changes() : 0;
		if (first != gutterFirst || last != gutterLast ||
				digitCount != gutterDigits || markChanges != gutterMarks ||
				!node.hasDisplayList()) {
			node.setPosition(0, 0,
					(int) Math.ceil(digitCount * appearance.digitWidth) + 1,
					(int) Math.ceil(lines.top(last) - lines.top(first)) +
							appearance.lineHeight);
			node.setClipToBounds(false);
			RecordingCanvas recording = node.beginRecording();
			try {
				drawNumbers(recording, first, last, digitCount);
			} finally {
				node.endRecording();
			}
			gutterFirst = first;
			gutterLast = last;
			gutterDigits = digitCount;
			gutterMarks = markChanges;
		}
		canvas.drawRenderNode(node);
	}

	// Draws the numbers of the lines from first to last, the top of the
	// first at 0.
	private void drawNumbers(Canvas canvas, int first, int last,
			int digitCount) {
		CodeAppearance a = appearance;
		Paint paint = a.paint;
		float right = digitCount * a.digitWidth;
		float firstTop = lines.top(first);
		// The leading edge of the gutter, left of the padding.
		float edge = -getPaddingLeft();
		fill.setColor(a.markColor);
		for (int n = first; n <= last; ++n) {
			boolean marked = marks != null &&
					marks.severity(n) == ProjectDiagnostic.Severity.ERROR;
			if (marked) {
				float top = lines.top(n) - firstTop;
				canvas.drawRect(edge, top, edge + a.markWidth,
						top + a.lineHeight +
								(n == lines.rowLine() ? lines.rowHeight() : 0),
						fill);
			}
			paint.setColor(marked ? a.markColor : a.lineNumberColor);
			int start = digits.length;
			for (int number = n + 1; number > 0; number /= 10) {
				digits[--start] = (char) ('0' + number % 10);
			}
			int count = digits.length - start;
			canvas.drawText(digits, start, count,
					right - count * a.digitWidth,
					lines.top(n) - firstTop + a.baseline, paint);
		}
	}

	// Opens or closes the row of line and keeps that choice while the
	// lines with errors stay the same.
	private void toggleRow(int line) {
		marks.open(line == marks.open() ? -1 : line);
		chosenFor = marks.errorLines();
		placeRow();
	}

	// Lays the row out under the open line of the marks, if it changed,
	// with the scroll position that keeps the caret's line, or else the
	// first line on screen, where it is.
	private void placeRow() {
		if (document == null) {
			return;
		}
		int line = marks != null ? marks.open() : -1;
		List<String> messages = line >= 0 ? marks.messages(line) : null;
		// The text wraps short of the edge, or of the widest a row is.
		int width = Math.round(Math.min(getWidth() - insets.right -
						textLeft(document.text().lineCount()),
				appearance.rowMaxWidth) - appearance.rowPadding);
		if (line == row.line() && messages == row.messages() &&
				(line < 0 || width == row.width())) {
			return;
		}
		int anchor = anchorLine();
		int oldLine = lines.rowLine();
		int oldHeight = lines.rowHeight();
		row = line < 0 ? Row.NONE : layRow(line, messages, width);
		lines.setRow(line, line < 0 ? 0 : row.height(appearance));
		int scrollY = getScrollY();
		if (!edited || line == oldLine) {
			scrollY = lines.keep(scrollY, anchor, oldLine, oldHeight);
		}
		// The row of the caret's line belongs to it: both stay in view.
		int caret = document.text().lineOf(document.selection().head());
		if (line == caret && anchor == caret) {
			scrollY = revealLine(scrollY, caret);
		}
		scrollInside(getScrollX(), scrollY);
		gutterLast = -1;
		invalidate();
	}

	// Returns the scroll position nearest scrollY that shows line n, with
	// its row, between the insets.
	private int revealLine(int scrollY, int n) {
		float textTop = textTop();
		return lines.reveal(scrollY, n, insets.top - textTop,
				getHeight() - insets.bottom - textTop);
	}

	// Returns the caret's line if any of it is on screen, else the first
	// line on screen.
	private int anchorLine() {
		int caret = document.text().lineOf(document.selection().head());
		float top = lineTop(caret) - getScrollY();
		if (top + appearance.lineHeight > insets.top &&
				top < getHeight() - insets.bottom) {
			return caret;
		}
		return lineAt(getScrollY() + insets.top);
	}

	// Returns the line with an error whose mark is nearest x and y of the
	// view, inside a tap target as wide as the gutter, so that a tap on
	// the text always places the caret, and a tap target tall around its
	// number; -1 for none.
	private int markAt(float x, float y) {
		if (document == null || marks == null || !showLineNumbers ||
				x >= textLeft(document.text().lineCount())) {
			return -1;
		}
		float at = y + getScrollY();
		float reach = appearance.tapTarget / 2;
		int found = -1;
		for (int n = lineAt(at - reach), last = lineAt(at + reach);
				n <= last; ++n) {
			float distance = Math.abs(lineTop(n) +
					appearance.lineHeight / 2f - at);
			if (distance <= reach &&
					marks.severity(n) == ProjectDiagnostic.Severity.ERROR) {
				reach = distance;
				found = n;
			}
		}
		return found;
	}

	// Draws the row under its line, from left to right, its text aligned
	// with the code at textLeft; square where it touches the screen edge.
	private void drawRow(Canvas canvas, float left, float right,
			float textLeft) {
		int line = lines.rowLine();
		if (line < 0) {
			return;
		}
		CodeAppearance a = appearance;
		float top = lineTop(line) + a.lineHeight;
		float bottom = top + lines.rowHeight();
		int scrollY = getScrollY();
		if (bottom <= scrollY || top >= scrollY + getHeight()) {
			return;
		}
		float corner = showLineNumbers ? a.corner : 0;
		fill.setColor(a.rowColor);
		rowRect.set(left, top, right, bottom);
		canvas.drawRoundRect(rowRect, corner, corner, fill);
		canvas.drawRect(right - corner, top, right, bottom, fill);
		float x = getScrollX() + textLeft;
		float y = top + a.rowPaddingVertical + a.rowBaseline;
		for (String text : row.text()) {
			canvas.drawText(text, x, y, a.rowPaint);
			y += a.rowLineHeight;
		}
		for (MessageRow.Placed fix : row.fixes()) {
			float lineTop = top + row.fixTop(fix.line(), a);
			if (fixPressed == fix.index()) {
				// 12 % of the accent, 32 dp centred on the label's line.
				float middle = lineTop + a.fixHeight / 2f;
				fill.setColor(a.fixPressedColor);
				rowRect.set(x + fix.start(), middle - a.fixPressedHeight / 2f,
						x + fix.end(), middle + a.fixPressedHeight / 2f);
				canvas.drawRoundRect(rowRect, a.corner, a.corner, fill);
			}
			canvas.drawText(fix.label(), x + fix.start() + a.fixPadding,
					lineTop + a.fixBaseline, a.fixPaint);
		}
	}

	// Lays out the row of line with messages for text width wide: its
	// lines of text, and its fixes end-aligned short of its edge.
	@NonNull
	private Row layRow(int line, @NonNull List<String> messages,
			int width) {
		CodeAppearance a = appearance;
		String[] text = MessageRow.lines(rowFix != null
				? rowFix.shown(line, messages)
				: messages, Math.max(1, width), rowStyle);
		List<MessageRow.Placed> placed = MessageRow.place(fixes(line,
						messages), width + a.rowPadding - a.fixInset,
				new MessageRow.FixMetrics(a.fixPadding, a.fixGap,
						a.fixPaint::measureText, "\u2026"));
		int fixLines = 0;
		for (MessageRow.Placed fix : placed) {
			fixLines = Math.max(fixLines, fix.line() + 1);
		}
		return new Row(line, messages, width, text, placed, fixLines);
	}

	// Returns the fix of the row at the view's point x, y, or -1: each
	// target is as wide as the button and a little more, and reaches up
	// over the last line of text, so it is at least a tap target high.
	private int fixAt(float x, float y) {
		int line = lines.rowLine();
		if (line < 0 || row.fixes().isEmpty() || document == null) {
			return -1;
		}
		CodeAppearance a = appearance;
		float top = lineTop(line) + a.lineHeight;
		float left = getScrollX() + textLeft(document.text().lineCount());
		x += getScrollX();
		y += getScrollY();
		for (MessageRow.Placed fix : row.fixes()) {
			float fixTop = top + row.fixTop(fix.line(), a);
			float targetTop = fix.line() == 0
					? fixTop - a.fixInset - a.rowLineHeight
					: fixTop - a.fixLineGap / 2f;
			float targetBottom = fix.line() == row.fixLines() - 1
					? Math.max(top + row.height(a), targetTop + a.tapTarget)
					: fixTop + a.fixHeight + a.fixLineGap / 2f;
			targetTop = Math.min(targetTop, targetBottom - a.tapTarget);
			if (y >= targetTop && y <= targetBottom &&
					x >= left + fix.start() - a.fixInset &&
					x <= left + fix.end() + a.fixInset) {
				return fix.index();
			}
		}
		return -1;
	}

	// Whether line n was just put in by a fix.
	private boolean marked(int n) {
		for (int line : markedLines) {
			if (line == n) {
				return true;
			}
		}
		return false;
	}

	// At most MAX_FIXES: what the list of errors offers for an error of that
	// line.
	@NonNull
	public List<Fix> fixesOf(int line) {
		return marks != null
				? fixes(line, marks.messages(line))
				: Collections.emptyList();
	}

	// As the row does.
	public void applyFixOf(int line, int index) {
		if (index < fixesOf(line).size()) {
			applyFix(line, marks.messages(line), index);
		}
	}

	private void applyRowFix(int index) {
		if (rowFix != null && row.messages() != null) {
			applyFix(row.line(), row.messages(), index);
		}
	}

	// Applies fix index of messages of line. A fix puts text in, so only
	// where the code is seen from above may change; the sideways scroll
	// stays.
	private void applyFix(int line, @NonNull List<String> messages,
			int index) {
		int scrollX = getScrollX();
		rowFix.apply(line, messages, index);
		scrollInside(scrollX, getScrollY());
	}

	@NonNull
	private List<Fix> fixes(int line, List<String> messages) {
		List<Fix> fixes = rowFix != null
				? rowFix.fixes(line, messages)
				: Collections.emptyList();
		return fixes.size() > MAX_FIXES
				? fixes.subList(0, MAX_FIXES)
				: fixes;
	}

	@NonNull
	private static List<String> spoken(@NonNull List<Fix> fixes) {
		List<String> spoken = new ArrayList<>(fixes.size());
		for (Fix fix : fixes) {
			spoken.add(fix.spoken());
		}
		return spoken;
	}

	// Returns what is said of an error, and of its fixes if it has any.
	private String withFix(String said, int line, List<String> messages) {
		List<String> fixes = spoken(fixes(line, messages));
		return !fixes.isEmpty()
				? said + " " + getResources().getString(
				R.string.a11y_fix_available, TextUtils.join(", ", fixes))
				: said;
	}

	@NonNull
	List<String> caretLineFixes() {
		List<String> messages = caretLineMessages();
		return messages != null
				? spoken(fixes(caretLine(), messages))
				: Collections.emptyList();
	}

	// Returns whether it had one.
	boolean applyCaretLineFix(int index) {
		List<String> messages = caretLineMessages();
		if (messages == null ||
				index >= fixes(caretLine(), messages).size()) {
			return false;
		}
		applyFix(caretLine(), messages, index);
		return true;
	}

	private int caretLine() {
		return document.text().lineOf(document.selection().head());
	}

	@Nullable
	private List<String> caretLineMessages() {
		if (rowFix == null || document == null || marks == null) {
			return null;
		}
		List<String> messages = marks.messages(document.text().lineOf(
				document.selection().head()));
		return messages.isEmpty() ? null : messages;
	}

	// Returns the first line all of which is on screen.
	private int firstShown() {
		int top = getScrollY() + insets.top;
		int n = lineAt(top);
		return lineTop(n) < top ? n + 1 : n;
	}

	// Returns the last line all of which is on screen.
	private int lastShown() {
		int bottom = getScrollY() + getHeight() - insets.bottom;
		int n = lineAt(bottom);
		return lineTop(n) + appearance.lineHeight > bottom ? n - 1 : n;
	}

	private void connect() {
		document.addListener(listener);
		work.start(document, styles, this::restyled, analysis -> {
			if (analysisListener != null) {
				analysisListener.onAnalysis(analysis);
			}
		});
		connected = true;
		restartBlink();
	}

	private void disconnect() {
		handler.removeCallbacks(blink);
		handler.removeCallbacks(hideCaretHandle);
		caretHandle = false;
		dismissMagnifier();
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
		lines.setLineHeight(appearance.lineHeight);
		forgetLines();
		// The row's text follows the code's size: lay it out again.
		row = Row.NONE;
		placeRow();
		gutterLast = -1;
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
		// Unless something else was invalidated since the last frame.
		caretOnly = caretOnly || !isDirty();
		super.invalidate();
		handler.postDelayed(blink, BLINK);
	}
}

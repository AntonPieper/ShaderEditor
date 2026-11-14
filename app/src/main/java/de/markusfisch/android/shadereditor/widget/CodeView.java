package de.markusfisch.android.shadereditor.widget;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
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
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.ContextMenu;
import android.view.GestureDetector;
import android.view.HapticFeedbackConstants;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
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
import android.widget.Magnifier;
import android.widget.OverScroller;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewConfigurationCompat;
import androidx.core.view.WindowInsetsCompat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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
import de.markusfisch.android.shadereditor.editor.Text;
import de.markusfisch.android.shadereditor.editor.TextInput;
import de.markusfisch.android.shadereditor.project.ProjectDiagnostic;

/**
 * Shows a Document: the lines on screen in the colours of its
 * LineStyles, their numbers in a gutter that stays in place when the
 * text scrolls sideways, the lines its LineMarks mark, the selection,
 * the composing region and a blinking caret. Under the open line of the
 * marks, a row shows the messages of its errors; it moves the lines
 * after it down, and opening, closing or resizing it scrolls so that
 * the caret's line stays where it is. A tap on the mark at a line's
 * number opens its row or closes it. It owns no text. Keys
 * become KeyCommands, and the caret is kept in view after each and
 * above the soft keyboard when that opens. The soft keyboard edits the
 * document through one TextInput per connection; a tap shows it.
 * A tap places the caret; a double tap or a long press selects a word
 * and a drag after it extends the selection by words, scrolling while
 * the finger is near an edge. A selection made by touch has the
 * platform's handles, which drag its ends, by words while it grows, with
 * the platform's magnifier from Android 9 on; a tap shows the caret's
 * handle until a while has passed or the text changes. When the
 * finger lifts, the platform's floating toolbar offers the clipboard
 * for the selection; a tap inside the selection hides or shows it
 * again. A tap on the caret's handle, or a long press past the end of a
 * line, shows it for the caret. Cut, copy and paste of the toolbar, the
 * keyboard's context menu and the keys are the same KeyCommands; a copy
 * the clipboard refuses says so. A mouse click places the caret, a
 * double click selects a word, Shift extends the selection, a drag
 * selects by character or by word, the secondary button opens the
 * context menu and the wheel scrolls; over the text the pointer is the
 * text cursor.
 * Given a KeyboardSession, it records the connections and, as actions,
 * every key event (action, key code, meta state, the text it types),
 * the text on the clipboard before a paste, a tap's selection, the
 * keyboard's context menu actions and the Enter command it runs for a
 * committed line break. It
 * redraws when the document changes, and when a line on screen changes
 * colour because the continuations of the line styles or the document's
 * analysis, which it runs on one background thread, made it current.
 * From Android 10 on, the drawing of a line is recorded once and
 * replayed until the line's string or colours change. A frame records
 * at most a third of the lines on screen, draws the others directly and
 * leaves them to the frames after it. Accessibility services see it as
 * an EditText, through CodeAccessibility.
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
	private static final int HORIZONTAL = 1;
	private static final int VERTICAL = 2;
	private static final int DRAG_WORDS = 1;
	private static final int DRAG_HANDLE = 2;
	private static final int DRAG_MOUSE = 3;

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
			edited = true;
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
	// What a touch drags, where it went down, whether it moved since,
	// and where the finger is.
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
	private boolean caretHandle;
	// A Magnifier from Android 9 on, once a handle was dragged.
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
	private LineBackdrop backdrop;
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
	private List<String> rowMessages;
	private int rowWidth;
	private String[] rowText = new String[0];
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
						if (mark >= 0) {
							toggleRow(mark);
						} else if (inSelection(e.getX(), e.getY())) {
							toggleToolbar();
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
						if (dragging != 0) {
							// After a double tap.
							return;
						}
						if (onText(e.getX(), e.getY())) {
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

	/**
	 * Show document with its styles and, if not null, its marks, in
	 * place of the document shown before.
	 */
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
		keys = new KeyCommands(document, commands, clipboard);
		deadChar = 0;
		composingStart = composingEnd = -1;
		touched = null;
		dragging = 0;
		forgetLines();
		lines.setRow(-1, 0);
		rowMessages = null;
		chosenFor = null;
		scrollTo(0, 0);
		if (isAttachedToWindow()) {
			connect();
			restartInput();
		}
		accessibility.documentChanged();
		invalidate();
	}

	/**
	 * Record keyboard connections from the next one on to session, or
	 * nothing if it is null.
	 */
	public void setKeyboardSession(@Nullable KeyboardSession session) {
		this.session = session;
		closeInput();
		restartInput();
	}

	/**
	 * Set the input type keyboards are given, INPUT_TYPE_NO_SUGGESTIONS
	 * by default.
	 */
	public void setInputType(int inputType) {
		this.inputType = inputType;
		restartInput();
	}

	@Nullable
	public Document getDocument() {
		return document;
	}

	/** Return the input type keyboards are given. */
	int inputType() {
		return inputType;
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

	/** Hear of every analysis of the document shown, on this thread. */
	public void setAnalysisListener(
			@Nullable AnalysisScheduler.Callback listener) {
		analysisListener = listener;
	}

	/**
	 * Return the last analysis of the document shown, possibly of an
	 * older revision of it.
	 */
	@NonNull
	public Analysis getAnalysis() {
		return work.latest();
	}

	/**
	 * Let Tab indent, true by default, or leave it to move the focus,
	 * as the platform does.
	 */
	public void setTabIndents(boolean tabIndents) {
		this.tabIndents = tabIndents;
	}

	public void setLineBackdrop(@Nullable LineBackdrop backdrop) {
		this.backdrop = backdrop;
		invalidate();
	}

	/**
	 * Show the marks after they were set from a result, with the row of
	 * the line MessageRow.choose picks, unless the user opened or closed
	 * one by tapping a mark while the lines with errors were the same.
	 */
	public void marksChanged() {
		if (document == null || marks == null) {
			return;
		}
		int[] failing = marks.errorLines();
		if (!Arrays.equals(failing, chosenFor)) {
			chosenFor = null;
			marks.open(MessageRow.choose(failing, marks.open(),
					document.text().lineOf(document.selection().head()),
					firstShown(), lastShown()));
		}
		placeRow();
		invalidate();
	}

	public int getLineHeight() {
		return appearance.lineHeight;
	}

	/** Scroll as little as it takes to show the selection's head. */
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
		float top = lineTop(n);
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
		if (top < scrollY + insets.top) {
			scrollY = (int) top - insets.top;
		} else if (top + appearance.lineHeight >
				scrollY + getHeight() - insets.bottom) {
			scrollY = (int) Math.ceil(top + appearance.lineHeight -
					getHeight() + insets.bottom);
		}
		scroller.forceFinished(true);
		scrollInside(scrollX, scrollY);
	}

	/** Fling with the given velocity in pixels per second. */
	public void fling(int velocityX, int velocityY) {
		scroller.fling(getScrollX(), getScrollY(), velocityX, velocityY,
				0, maxScrollX(), 0, maxScrollY());
		postInvalidateOnAnimation();
	}

	/** Scroll to x and y, kept inside the range of the text. */
	@Override
	public void scrollTo(int x, int y) {
		caretIntoViewWhenLaidOut = false;
		scrollInside(x, y);
	}

	@Override
	protected void onSizeChanged(int w, int h, int oldw, int oldh) {
		super.onSizeChanged(w, h, oldw, oldh);
		placeRow();
		if (caretIntoViewWhenLaidOut || (h < oldh && isFocused())) {
			bringCaretIntoView();
		} else {
			scrollInside(getScrollX(), getScrollY());
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

	// The gesture detector calls performClick for a tap.
	@SuppressLint("ClickableViewAccessibility")
	@Override
	public boolean onTouchEvent(MotionEvent event) {
		if (event.isFromSource(InputDevice.SOURCE_MOUSE)) {
			return mouse(event) || super.onTouchEvent(event);
		}
		int action = event.getActionMasked();
		if (action == MotionEvent.ACTION_DOWN) {
			dragging = 0;
			moved = false;
			downX = event.getX();
			downY = event.getY();
			grabHandle(downX, downY);
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
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
			// Android 6 never calls closeConnection, and its keyboard may
			// keep using the connection it used last; close the others.
			for (int i = connections.size(); i-- > 0; ) {
				if (connections.get(i) != active) {
					connections.get(i).closeConnection();
				}
			}
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
		insets = WindowInsetsCompat.toWindowInsetsCompat(windowInsets, this)
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

	/** Return where line 0 starts. */
	float textTop() {
		return getPaddingTop() + insets.top;
	}

	/** Return where line n starts, without scrolling. */
	float lineTop(int n) {
		return textTop() + lines.top(n);
	}

	/** Return the line at y, without scrolling. */
	int lineAt(float y) {
		return lines.lineAt(y - textTop(), document.text().lineCount());
	}

	/** Return where the numbers of the lines start. */
	float gutterLeft() {
		return getPaddingLeft() + insets.left;
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

	// The last line can scroll up to the middle of the space between the
	// system bars, or above the keyboard.
	private int maxScrollY() {
		int visible = getHeight() - insets.top - insets.bottom;
		float bottom = textTop() + lines.height(document.text().lineCount()) +
				getPaddingBottom() + insets.bottom + visible / 2f;
		return Math.max(0, (int) Math.ceil(bottom - getHeight()));
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

	/** Run the Enter command, for a line break the keyboard commits. */
	void enter() {
		if (keys != null) {
			action("command", "enter");
			commands.enter();
			bringCaretIntoView();
		}
	}

	/** Do a context menu action of the keyboard; return if it is known. */
	boolean contextMenuAction(int id) {
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

	/**
	 * Do action id of the toolbar; return whether the toolbar is done.
	 * Select all keeps it, and a copy leaves the caret behind the text,
	 * as the platform's do; an action that did nothing changes nothing.
	 */
	boolean toolbarAction(int id) {
		KeyCommands.Command command = KeyCommands.contextMenuCommand(id);
		boolean done;
		selecting = true;
		try {
			done = command != null && menuCommand(id, command);
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

	// The user closed the toolbar, which then drops a selection as the
	// platform's does, or something else closed it.
	private void toolbarClosed(boolean dismissed) {
		if (dismissed && touched != null) {
			select(touched.end(), touched.end());
			touched = null;
			invalidate();
		} else {
			showCaretHandle();
		}
	}

	/** Return the ids of the actions the toolbar offers now. */
	int[] offered() {
		Document.Selection selection = document.selection();
		ClipboardManager manager = clipboardManager();
		return TextActions.offered(selection.start(), selection.end(),
				document.text().length(),
				manager != null && manager.hasPrimaryClip());
	}

	private void toggleToolbar() {
		if (toolbar.isShown()) {
			toolbar.close();
		} else {
			showToolbar();
		}
	}

	private void showToolbar() {
		handler.removeCallbacks(hideCaretHandle);
		toolbar.show();
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

	/**
	 * Put the bounds on screen of the characters from offset on into
	 * out, null for one not on screen.
	 */
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
			boolean shown = right >= getScrollX() + left &&
					x <= getScrollX() + getWidth() - insets.right &&
					bottom > getScrollY() + insets.top &&
					top < getScrollY() + getHeight() - insets.bottom;
			out[i] = shown
					? new RectF(x + dx, top + dy, right + dx, bottom + dy)
					: null;
		}
	}

	/** Return the lines Page Up and Down move. */
	int pageLines() {
		return Math.max(1, (getHeight() - insets.top - insets.bottom) /
				appearance.lineHeight - 1);
	}

	/**
	 * Select from anchor to head for an accessibility service, a
	 * selection with its handles and toolbar if shown, and scroll the
	 * head into view.
	 */
	void selectForAccessibility(int anchor, int head, boolean shown) {
		toolbar.close();
		select(anchor, head);
		touched = shown && anchor != head ? document.selection() : null;
		if (touched != null) {
			showToolbar();
		}
		bringCaretIntoView();
	}

	/**
	 * Replace the whole text by text, null for none, as one step with
	 * the caret behind it; return false if the document refused it.
	 */
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

	/**
	 * Do what an accessibility service's tap does to an EditText: focus
	 * and show the keyboard; the caret stays.
	 */
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

	/**
	 * Do what an accessibility service's long press does to an EditText:
	 * show or hide the toolbar for the selection or the caret.
	 */
	boolean longClickForAccessibility() {
		sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_LONG_CLICKED);
		performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
		touched = document.selection();
		toggleToolbar();
		return true;
	}

	/**
	 * Scroll a page in the direction of dx and dy, each -1, 0 or 1;
	 * return whether the view scrolled.
	 */
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

	/** Record a call the connection does not pass on. */
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

	/**
	 * Make connection the one whose updates reach the keyboard, and close
	 * the older ones: the keyboard does not go back to them.
	 */
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

	/** Pass an update of connection on, if the keyboard uses it. */
	void updateSelection(CodeInputConnection connection, int selStart,
			int selEnd, int composingStart, int composingEnd) {
		InputMethodManager imm = inputMethodManager();
		if (connection == active && imm != null) {
			setComposingRegion(composingStart, composingEnd);
			imm.updateSelection(this, selStart, selEnd, composingStart,
					composingEnd);
		}
	}

	/** Pass extracted text of connection on, if the keyboard uses it. */
	void updateExtractedText(CodeInputConnection connection, int token,
			TextInput.Window window) {
		InputMethodManager imm = inputMethodManager();
		if (connection == active && imm != null) {
			imm.updateExtractedText(this, token,
					CodeInputConnection.extracted(window));
		}
	}

	/** Forget connection, which closed. */
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

	// Selects the word at x, y, which a drag extends by words.
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

	// Starts dragging the handle at x, y of the view, if there is one.
	private void grabHandle(float x, float y) {
		int which = handles.at(x + getScrollX(), y + getScrollY());
		if (which < 0 || touched == null) {
			return;
		}
		int head = which == SelectionHandles.START
				? touched.start()
				: touched.end();
		touch.dragHandle(touched.isEmpty() ? head : touched.start() +
				touched.end() - head, head);
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
		if (touched == null) {
			return;
		}
		showCaretHandle();
		if (tapped && touched.isEmpty()) {
			toggleToolbar();
		} else if (!touched.isEmpty() || pressed || toolbar.isShown()) {
			showToolbar();
		}
	}

	// Selects up to the place of the finger, and scrolls while it is
	// near an edge.
	private void dragSelection() {
		Text text = document.text();
		if (touch.dragTo(text, offsetAt(dragX + pointDx, dragY + pointDy))) {
			select(touch.anchor(), touch.head());
		}
		if (dragging == DRAG_HANDLE && moved) {
			magnify();
		}
		if (!edgeScrolling && (edgeStepX() != 0 || edgeStepY() != 0)) {
			edgeScrolling = true;
			postOnAnimation(edgeScroll);
		}
	}

	// Shows the magnifier over the line of the dragged end, at the
	// finger but not past the text of that line, from Android 9 on.
	private void magnify() {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
			return;
		}
		Text text = document.text();
		int n = text.lineOf(touch.head());
		float left = textLeft(text.lineCount());
		float x = Math.max(left, Math.min(dragX + pointDx,
				left - getScrollX() + layoutOf(n).width()));
		float y = lineTop(n) + appearance.lineHeight / 2f - getScrollY();
		if (y < insets.top || y > getHeight() - insets.bottom) {
			dismissMagnifier();
			return;
		}
		if (magnifier == null) {
			// With the defaults of the platform's text views.
			magnifier = new Magnifier(this);
		}
		((Magnifier) magnifier).show(x, y);
	}

	private void dismissMagnifier() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
				magnifier != null) {
			((Magnifier) magnifier).dismiss();
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
				if (mark >= 0 &&
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
			showToolbar();
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

	// Returns the offset nearest x and y of the view.
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

	// Draws the handles of the selection the last touch made, those
	// whose place is in view.
	private void drawHandles(Canvas canvas) {
		handles.clear();
		if (touched == null || (touched.isEmpty() && !caretHandle) ||
				!isFocused()) {
			return;
		}
		if (touched.isEmpty()) {
			placeHandle(-1, touched.head());
		} else {
			placeHandle(SelectionHandles.START, touched.start());
			placeHandle(SelectionHandles.END, touched.end());
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
		int width = Math.round(getWidth() - insets.right -
				textLeft(document.text().lineCount()) - appearance.rowPadding);
		if (line == lines.rowLine() && messages == rowMessages &&
				(line < 0 || width == rowWidth)) {
			return;
		}
		int anchor = anchorLine();
		int oldLine = lines.rowLine();
		int oldHeight = lines.rowHeight();
		rowMessages = messages;
		rowWidth = width;
		if (line < 0) {
			lines.setRow(-1, 0);
		} else {
			CodeAppearance a = appearance;
			rowText = MessageRow.lines(messages, Math.max(1, width),
					rowStyle);
			lines.setRow(line, rowText.length * a.rowLineHeight +
					2 * a.rowPaddingVertical);
		}
		if (!edited || line == oldLine) {
			scrollInside(getScrollX(), lines.keep(getScrollY(), anchor,
					oldLine, oldHeight));
		}
		gutterLast = -1;
		invalidate();
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
		for (String text : rowText) {
			canvas.drawText(text, x, y, a.rowPaint);
			y += a.rowLineHeight;
		}
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

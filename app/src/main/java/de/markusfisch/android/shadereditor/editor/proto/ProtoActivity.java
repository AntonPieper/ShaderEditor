package de.markusfisch.android.shadereditor.editor.proto;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ScrollView;

import androidx.core.content.ContextCompat;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import de.markusfisch.android.shadereditor.editor.Document;
import de.markusfisch.android.shadereditor.editor.LineStates;
import de.markusfisch.android.shadereditor.editor.Text;
import de.markusfisch.android.shadereditor.highlighter.Highlight;
import de.markusfisch.android.shadereditor.syntax.GlslLexer;
import de.markusfisch.android.shadereditor.syntax.Tokens;

/**
 * THROWAWAY PROTOTYPE (proto/editor-core). Not production code.
 * <p>
 * Shows a large shader in the prototype view or in the platform's
 * EditText and logs what each keystroke and frame costs. Extras:
 * mode (view, layout, stock, stocknested), copies (of the 5,002-line
 * file), spans (stock: one colour span per token), raw (TYPE_NULL),
 * suggestions, trace, drive (type, paste, selectall, fling).
 */
public final class ProtoActivity extends Activity {
	static boolean rawKeys;
	static boolean suggestions;
	static boolean trace;
	static boolean cursorUpdates;

	private final Handler main = new Handler(Looper.getMainLooper());
	private final Stats stats = new Stats();
	private HandlerThread metricsThread;
	private ProtoEditorView view;
	private TimedEditText stock;
	private ScrollView stockScroll;
	private String base;
	private String mode;
	private long created;

	@Override
	protected void onCreate(Bundle state) {
		super.onCreate(state);
		created = System.nanoTime();
		getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
		// A launcher alias carries its configuration as meta-data
		// "args", for example "mode=view;copies=10;drive=type,big".
		java.util.Map<String, String> args = new java.util.HashMap<>();
		try {
			Bundle meta = getPackageManager().getActivityInfo(
					getIntent().getComponent(),
					android.content.pm.PackageManager.GET_META_DATA).metaData;
			String line = meta != null ? meta.getString("args") : null;
			if (line != null) {
				for (String pair : line.split(";")) {
					int eq = pair.indexOf('=');
					args.put(pair.substring(0, eq), pair.substring(eq + 1));
				}
			}
		} catch (Exception e) {
			Log.w(ProtoEditorView.TAG, "no args", e);
		}
		Bundle extras = getIntent().getExtras();
		if (extras != null) {
			for (String key : extras.keySet()) {
				args.put(key, String.valueOf(extras.get(key)));
			}
		}
		mode = args.getOrDefault("mode", "view");
		int copies = Integer.parseInt(args.getOrDefault("copies", "1"));
		boolean spans = "1".equals(args.get("spans"));
		rawKeys = "1".equals(args.get("raw"));
		suggestions = "1".equals(args.get("suggestions"));
		trace = "1".equals(args.get("trace"));
		cursorUpdates = "1".equals(args.get("cursor"));
		String drive = args.get("drive");
		Log.i(ProtoEditorView.TAG, "config " + args);

		FrameLayout root = new FrameLayout(this);
		root.setBackgroundColor(0xff101010);
		root.setFitsSystemWindows(true);
		setContentView(root);
		if (mode.startsWith("stock")) {
			stock = new TimedEditText(this, stats);
			stock.setTypeface(Typeface.MONOSPACE);
			stock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
			stock.setTextColor(0xffdddddd);
			stock.setBackground(null);
			stock.setGravity(Gravity.LEFT | Gravity.TOP);
			stock.setSaveEnabled(false);
			// As the app's layout does. Without it an autofill service
			// is sent the whole text and the transaction is too large.
			stock.setImportantForAutofill(
					View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
			stock.setInputType(InputType.TYPE_CLASS_TEXT |
					InputType.TYPE_TEXT_FLAG_MULTI_LINE |
					(suggestions ? 0 : InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS));
			stock.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI);
			stock.setHorizontallyScrolling(true);
			if (mode.equals("stocknested")) {
				// As the app hosts its editor today.
				stockScroll = new ScrollView(this);
				stockScroll.setFillViewport(true);
				HorizontalScrollView horizontal = new HorizontalScrollView(this);
				horizontal.setFillViewport(true);
				horizontal.addView(stock, new ViewGroup.LayoutParams(
						ViewGroup.LayoutParams.WRAP_CONTENT,
						ViewGroup.LayoutParams.MATCH_PARENT));
				stockScroll.addView(horizontal, new ViewGroup.LayoutParams(
						ViewGroup.LayoutParams.MATCH_PARENT,
						ViewGroup.LayoutParams.WRAP_CONTENT));
				root.addView(stockScroll, new ViewGroup.LayoutParams(
						ViewGroup.LayoutParams.MATCH_PARENT,
						ViewGroup.LayoutParams.MATCH_PARENT));
			} else {
				// The best case: the size of the screen, scrolling itself.
				stock.setVerticalScrollBarEnabled(true);
				root.addView(stock, new ViewGroup.LayoutParams(
						ViewGroup.LayoutParams.MATCH_PARENT,
						ViewGroup.LayoutParams.MATCH_PARENT));
			}
		} else {
			view = new ProtoEditorView(this, stats);
			view.useLayouts = mode.startsWith("layout");
			view.useNodes = args.containsKey("nodes");
			view.nodesByIdentity = "2".equals(args.get("nodes"));
			root.addView(view, new ViewGroup.LayoutParams(
					ViewGroup.LayoutParams.MATCH_PARENT,
					ViewGroup.LayoutParams.MATCH_PARENT));
		}

		metricsThread = new HandlerThread("metrics");
		metricsThread.start();
		getWindow().addOnFrameMetricsAvailableListener(
				(window, metrics, dropped) -> stats.frame(metrics),
				new Handler(metricsThread.getLooper()));

		new Thread(() -> load(copies, spans, drive), "load").start();
	}

	@Override
	protected void onDestroy() {
		metricsThread.quitSafely();
		main.removeCallbacksAndMessages(null);
		super.onDestroy();
	}

	private void load(int copies, boolean spans, String drive) {
		long start = System.nanoTime();
		try (InputStream in = getAssets().open("horizon-5000.glsl")) {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] buffer = new byte[65536];
			for (int n; (n = in.read(buffer)) > 0; ) {
				out.write(buffer, 0, n);
			}
			base = new String(out.toByteArray(), StandardCharsets.UTF_8);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
		StringBuilder sb = new StringBuilder(base.length() * copies);
		for (int i = 0; i < copies; ++i) {
			sb.append(base);
		}
		if (copies == 0) {
			int end = 0;
			for (int i = 0; i < 500; ++i) {
				end = base.indexOf('\n', end) + 1;
			}
			sb.append(base, 0, end);
		}
		String string = sb.toString();
		long read = System.nanoTime();
		if (view != null) {
			Text text = Text.of(string);
			LineStates states = new LineStates(text);
			long built = System.nanoTime();
			main.post(() -> {
				long shown = System.nanoTime();
				view.setDocument(new Document(text), states);
				long set = System.nanoTime();
				view.post(() -> Log.i(ProtoEditorView.TAG, "open " + mode +
						" lines=" + text.lineCount() + " read=" +
						(read - start) / 1000 + "us build(bg)=" +
						(built - read) / 1000 + "us setDocument(main)=" +
						(set - shown) / 1000 + "us firstDraw(main)=" +
						view.firstDrawNanos / 1000 +
						"us createToFirstDrawEnd=" +
						(view.firstDrawEnd - created) / 1000 + "us"));
				after(drive);
			});
		} else {
			CharSequence text = string;
			if (spans) {
				SpannableStringBuilder colored =
						new SpannableStringBuilder(string);
				Tokens tokens = GlslLexer.lex(string);
				int plain = 0xffdddddd;
				for (int i = 0, n = tokens.size(); i < n; ++i) {
					int color = ContextCompat.getColor(this,
							Highlight.colorOf(tokens.style(i)));
					if (color != plain) {
						colored.setSpan(new ForegroundColorSpan(color),
								tokens.start(i), tokens.end(i),
								Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
					}
				}
				text = colored;
			}
			CharSequence shown = text;
			long built = System.nanoTime();
			main.post(() -> {
				long before = System.nanoTime();
				stock.setText(shown);
				long set = System.nanoTime();
				stock.measureNanos = 0;
				stock.firstDrawEnd = 0;
				stock.post(() -> stock.post(() -> {
					Log.i(ProtoEditorView.TAG, "open " + mode + " lines=" +
							stock.getLineCount() + " read=" +
							(read - start) / 1000 + "us build(bg)=" +
							(built - read) / 1000 + "us setText(main)=" +
							(set - before) / 1000 + "us measure(main)=" +
							stock.measureNanos / 1000 +
							"us setTextToFirstDrawEnd(main)=" +
							(stock.firstDrawEnd - before) / 1000 +
							"us createToFirstDrawEnd=" +
							(stock.firstDrawEnd - created) / 1000 + "us");
					after(drive);
				}));
			});
		}
	}

	private void after(String drive) {
		if (drive == null) {
			return;
		}
		main.postDelayed(() -> {
			if (drive.contains("type")) {
				type(() -> next(drive));
			} else {
				next(drive);
			}
		}, 1500);
	}

	private void next(String drive) {
		Runnable flings = () -> {
			if (drive.contains("fling")) {
				fling();
			}
		};
		if (drive.contains("big")) {
			big(flings);
		} else {
			flings.run();
		}
	}

	private InputConnection connection() {
		View target = view != null ? view : stock;
		target.requestFocus();
		return target.onCreateInputConnection(new EditorInfo());
	}

	private int length() {
		return view != null
				? view.document().text().length()
				: stock.length();
	}

	private void caretToMiddle() {
		if (view != null) {
			Text text = view.document().text();
			int line = text.lineCount() / 2;
			int offset = text.lineStart(line) + text.line(line).length();
			view.connectionSetSelection(offset);
		} else {
			int line = stock.getLineCount() / 2;
			stock.setSelection(stock.getLayout().getLineEnd(line) - 1);
		}
	}

	// 60 characters, every 20th a line break, then 20 deletes, one
	// every 150 ms, in the middle of the text.
	private void type(Runnable then) {
		caretToMiddle();
		InputConnection ic = connection();
		main.postDelayed(() -> {
			stats.reset();
			for (int i = 0; i < 80; ++i) {
				int n = i;
				main.postDelayed(() -> {
					if (n < 60) {
						ic.commitText(n % 20 == 19 ? "\n" : "x", 1);
					} else {
						ic.deleteSurroundingText(1, 0);
					}
				}, 150L * i);
			}
			main.postDelayed(() -> {
				stats.summary(mode + " type");
				then.run();
			}, 150L * 80 + 500);
		}, 1000);
	}

	// Paste of the 5,002-line file, undo of it, select all, and replacing
	// everything by one character.
	private void big(Runnable then) {
		caretToMiddle();
		InputConnection ic = connection();
		stats.reset();
		long[] t = new long[8];
		main.postDelayed(() -> {
			t[0] = System.nanoTime();
			ic.commitText(base, 1);
			t[1] = System.nanoTime();
		}, 500);
		main.postDelayed(() -> {
			t[2] = System.nanoTime();
			if (view != null) {
				view.undo();
			} else {
				stock.onTextContextMenuItem(android.R.id.undo);
			}
			t[3] = System.nanoTime();
		}, 3000);
		main.postDelayed(() -> {
			t[4] = System.nanoTime();
			if (view != null) {
				view.selectAll();
			} else {
				stock.selectAll();
			}
			t[5] = System.nanoTime();
		}, 5500);
		main.postDelayed(() -> {
			t[6] = System.nanoTime();
			ic.commitText("x", 1);
			t[7] = System.nanoTime();
		}, 8000);
		main.postDelayed(() -> {
			Log.i(ProtoEditorView.TAG, "big " + mode + " paste235k=" +
					(t[1] - t[0]) / 1000 + "us undoPaste=" +
					(t[3] - t[2]) / 1000 + "us selectAll=" +
					(t[5] - t[4]) / 1000 + "us replaceAllByOneChar=" +
					(t[7] - t[6]) / 1000 + "us length=" + length());
			stats.summary(mode + " big");
			if (view != null) {
				view.undo();
			} else {
				stock.onTextContextMenuItem(android.R.id.undo);
			}
			main.postDelayed(then, 2500);
		}, 10500);
	}

	private void fling() {
		stats.reset();
		if (view != null) {
			view.scrollTo(0, 0);
			view.fling(0, 24000);
		} else if (stockScroll != null) {
			stockScroll.scrollTo(0, 0);
			stockScroll.fling(24000);
		} else {
			Log.i(ProtoEditorView.TAG, "note EditText has no fling of its own");
			return;
		}
		main.postDelayed(() -> stats.summary(mode + " fling"), 4000);
	}
}

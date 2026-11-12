package de.markusfisch.android.shadereditor.activity;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;

import androidx.annotation.NonNull;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import de.markusfisch.android.shadereditor.R;
import de.markusfisch.android.shadereditor.editor.Document;
import de.markusfisch.android.shadereditor.editor.LineMarks;
import de.markusfisch.android.shadereditor.editor.LineStyles;
import de.markusfisch.android.shadereditor.editor.Text;
import de.markusfisch.android.shadereditor.project.ProjectDiagnostic;
import de.markusfisch.android.shadereditor.widget.CodeView;

/**
 * Development harness, debug builds only: shows a CodeView over a text
 * chosen by the extras of the intent that starts it, and logs what
 * opening cost with the tag "CodeViewBench". Extras:
 * <ul>
 * <li>"shader", the name of a raw resource, "file", a path, or "sample",
 * "unicode" for lines of accented, CJK, emoji and Arabic text and a line
 * of 20,000 characters: the text; "lines" repeats its lines up to that
 * many;</li>
 * <li>"tab", the tab width; "size", the text size in sp;</li>
 * <li>"select", "anchor,head", or "caret", an offset; "line", a line to
 * put the caret at; "composing", "start,end"; "marks",
 * "line:error,line:warning" with lines from 1; "scrollx", pixels.</li>
 * </ul>
 */
public class CodeViewActivity extends Activity {
	private static final String TAG = "CodeViewBench";

	private final Handler main = new Handler(Looper.getMainLooper());
	private CodeView view;
	private Document document;
	private long created;
	private long firstDrawEnd;
	private long firstDraw;

	@Override
	protected void onCreate(Bundle state) {
		super.onCreate(state);
		created = System.nanoTime();
		view = new TimedCodeView(this);
		view.setBackgroundResource(R.color.background_editor);
		int padding = getResources().getDimensionPixelSize(
				R.dimen.editor_padding);
		view.setPadding(padding, padding, padding, padding);
		view.setTabWidth(getIntent().getIntExtra("tab", 4));
		view.setTextSize(TypedValue.COMPLEX_UNIT_SP,
				getIntent().getFloatExtra("size", 12));
		setContentView(view);
		getWindow().getDecorView().setBackgroundColor(0xff000000);
		new Thread(this::load, "load").start();
	}

	@Override
	protected void onDestroy() {
		main.removeCallbacksAndMessages(null);
		super.onDestroy();
	}

	private void load() {
		long start = System.nanoTime();
		String string;
		try {
			string = read();
		} catch (IOException e) {
			Log.e(TAG, "cannot read", e);
			return;
		}
		int lines = getIntent().getIntExtra("lines", 0);
		if (lines > 0) {
			string = repeat(string, lines);
		}
		Text text = Text.of(string);
		long loaded = System.nanoTime();
		main.post(() -> show(text, (loaded - start) / 1000));
	}

	private void show(Text text, long loadMicros) {
		long start = System.nanoTime();
		document = new Document(text);
		LineStyles styles = new LineStyles(document, 200);
		LineMarks marks = new LineMarks(document);
		view.setDocument(document, styles, marks);
		long shown = System.nanoTime();
		Bundle extras = getIntent().getExtras();
		if (extras != null) {
			apply(extras, marks);
		}
		view.requestFocus();
		view.post(() -> Log.i(TAG, "open lines=" + text.lineCount() +
				" chars=" + text.length() + " load=" + loadMicros +
				"us main=" + (shown - start) / 1000 + "us firstDraw=" +
				firstDraw / 1000 + "us createToFirstDraw=" +
				(firstDrawEnd - created) / 1000 + "us"));
	}

	private void apply(Bundle extras, LineMarks marks) {
		Text text = document.text();
		if (extras.containsKey("line")) {
			int offset = text.lineStart(Math.min(text.lineCount() - 1,
					extras.getInt("line")));
			document.select(offset, offset);
		}
		if (extras.containsKey("caret")) {
			int caret = extras.getInt("caret");
			document.select(caret, caret);
		}
		int[] select = pair(extras.getString("select"));
		if (select != null) {
			document.select(select[0], select[1]);
		}
		int[] composing = pair(extras.getString("composing"));
		if (composing != null) {
			view.setComposingRegion(composing[0], composing[1]);
		}
		String marked = extras.getString("marks");
		if (marked != null) {
			List<ProjectDiagnostic> diagnostics = new ArrayList<>();
			for (String mark : marked.split(",")) {
				String[] parts = mark.split(":");
				diagnostics.add(new ProjectDiagnostic(
						"warning".equals(parts[1])
								? ProjectDiagnostic.Severity.WARNING
								: ProjectDiagnostic.Severity.ERROR,
						mark, null, Integer.parseInt(parts[0])));
			}
			marks.set(document.revision(), diagnostics);
		}
		view.bringCaretIntoView();
		if (extras.containsKey("scrollx")) {
			view.scrollTo(extras.getInt("scrollx"), view.getScrollY());
		}
	}

	private void drawn(long start, long end) {
		if (firstDrawEnd == 0 && document != null) {
			firstDrawEnd = end;
			firstDraw = end - start;
		}
	}

	// The shader is named by an extra, so it is found by its name.
	@SuppressLint("DiscouragedApi")
	private String read() throws IOException {
		String sample = getIntent().getStringExtra("sample");
		if ("unicode".equals(sample)) {
			return unicode();
		}
		String file = getIntent().getStringExtra("file");
		String shader = getIntent().getStringExtra("shader");
		try (InputStream in = file != null
				? new FileInputStream(file)
				: getResources().openRawResource(getResources().getIdentifier(
						shader != null ? shader : "sample_horizon", "raw",
						getPackageName()))) {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] buffer = new byte[65536];
			for (int n; (n = in.read(buffer)) > 0; ) {
				out.write(buffer, 0, n);
			}
			return out.toString("UTF-8");
		}
	}

	private static String unicode() {
		StringBuilder sb = new StringBuilder();
		sb.append("// Accented: café, naïve, Ångström," +
				" Žluťoučký, é (combining)\n");
		sb.append("// CJK: 着色器编辑器 " +
				"シェーダー 셰이더\n");
		sb.append("// Emoji: 🎨 👩‍💻 " +
				"🇩🇪 👍🏽\n");
		sb.append("float x = 1.0; // تعليق " +
				"بالعربية end\n");
		sb.append("\tvec3 c = vec3(.5);\t// ü after a tab\n");
		sb.append("\tint a;\t\t// tab stops\n\t\tint bb;\t// tab stops\n");
		sb.append("float long = 0.0;");
		for (int i = 0; sb.length() < 20_300; ++i) {
			sb.append(" long += ").append(i % 10).append(".0;");
		}
		sb.append("\nvoid main() {\n\tgl_FragColor = vec4(1.0);\n}\n");
		return sb.toString();
	}

	private static String repeat(String text, int lines) {
		StringBuilder sb = new StringBuilder();
		String[] source = text.split("\n", -1);
		for (int i = 0; i < lines; ++i) {
			sb.append(source[i % source.length]);
			if (i < lines - 1) {
				sb.append('\n');
			}
		}
		return sb.toString();
	}

	private static int[] pair(String value) {
		if (value == null) {
			return null;
		}
		String[] parts = value.split(",");
		return new int[]{Integer.parseInt(parts[0]),
				Integer.parseInt(parts[1])};
	}

	private final class TimedCodeView extends CodeView {
		private TimedCodeView(Context context) {
			super(context);
		}

		@Override
		protected void onDraw(@NonNull Canvas canvas) {
			long start = System.nanoTime();
			super.onDraw(canvas);
			drawn(start, System.nanoTime());
		}
	}
}

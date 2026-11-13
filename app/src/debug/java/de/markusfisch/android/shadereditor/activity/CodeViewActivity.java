package de.markusfisch.android.shadereditor.activity;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.util.TypedValue;
import android.view.FrameMetrics;
import android.view.Window;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import de.markusfisch.android.shadereditor.R;
import de.markusfisch.android.shadereditor.editor.Document;
import de.markusfisch.android.shadereditor.editor.EditCommands;
import de.markusfisch.android.shadereditor.editor.LineMarks;
import de.markusfisch.android.shadereditor.editor.LineStyles;
import de.markusfisch.android.shadereditor.editor.Text;
import de.markusfisch.android.shadereditor.project.ProjectDiagnostic;
import de.markusfisch.android.shadereditor.widget.CodeView;

/**
 * Development harness, debug builds only: shows a CodeView over a text
 * chosen by the extras of the intent that starts it, and drives itself
 * without any input events, logging with the tag "CodeViewBench" what
 * opening, each frame and each scripted edit cost. Extras:
 * <ul>
 * <li>"shader", the name of a raw resource, "file", a path, or "sample",
 * "unicode" for lines of accented, CJK, emoji and Arabic text and a line
 * of 20,000 characters: the text; "lines" repeats its lines up to that
 * many;</li>
 * <li>"tab", the tab width; "size", the text size in sp;</li>
 * <li>"select", "anchor,head", or "caret", an offset; "line", a line to
 * put the caret at; "composing", "start,end"; "marks",
 * "line:error,line:warning" with lines from 1; "scrollx", pixels;</li>
 * <li>"drive", phases run in order: "idle" (5 s of nothing), "type" (60
 * characters, every 20th a line break, then 20 deletes, one every
 * 150 ms), "fling" (down and up, "velocity" px/s), "paste" (5,000
 * lines, then undo), "select" (select all, then put the caret back),
 * "jump" (to the end, then back to the start), "big" (paste 1 MB,
 * then undo).</li>
 * </ul>
 * Lines logged, one per kind; fields are added at the end only:
 * <pre>
 * open lines=N chars=N load=Nus main=Nus firstDraw=Nus createToFirstDraw=Nus
 * edit phase=P i=N change=Nus onDraw=Nus frame=Nus
 * draws phase=idle at(ms)= N N ...
 * summary phase=P frames=N onDraws=N onDraw STATS | frameMain STATS | frameTotal STATS
 * summary phase=P edits=N change STATS | onDraw STATS | frame STATS | change+frame STATS
 * jank phase=P refresh=F.FHz frameTime=Nus frames=N over=N
 * cpu phase=P main=Nms render=Nms other=Nms
 * done
 * </pre>
 * STATS is "p50=Nus p90=Nus p99=Nus max=Nus". An edit's onDraw and frame
 * are those of the first frame drawn after it. "over" counts the frames
 * whose total duration exceeded frameTime, one period of the display's
 * refresh rate. "main" and "render" are the CPU time of the main thread
 * and the render thread during the phase (render in steps of 10 ms),
 * "other" that of every other thread: background work such as the
 * document's analysis.
 */
public class CodeViewActivity extends Activity {
	private static final String TAG = "CodeViewBench";
	private static final String TYPED = "float v = fract(t * 2.0) + 1.0;";

	private final Handler main = new Handler(Looper.getMainLooper());
	// Vsync, total and main-thread time of each frame.
	private final List<long[]> frames = new ArrayList<>();
	// Start and end of each onDraw.
	private final List<long[]> draws = new ArrayList<>();
	// Change, onDraw and onDraw end of each scripted edit.
	private final List<long[]> edits = new ArrayList<>();
	private HandlerThread metricsThread;
	private CodeView view;
	private Document document;
	private long created;
	private long firstDrawEnd;
	private long firstDraw;
	private long[] pendingEdit;
	private String phase = "open";
	private long phaseCpu;
	private long phaseMainCpu;
	private long phaseRenderCpu;

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
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			metricsThread = new HandlerThread("metrics");
			metricsThread.start();
			getWindow().addOnFrameMetricsAvailableListener(
					this::frame, new Handler(metricsThread.getLooper()));
		}
		new Thread(this::load, "load").start();
	}

	@Override
	protected void onDestroy() {
		main.removeCallbacksAndMessages(null);
		if (metricsThread != null) {
			metricsThread.quitSafely();
		}
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
		String drive = getIntent().getStringExtra("drive");
		if (drive != null) {
			main.postDelayed(() -> drive(new ArrayList<>(
					Arrays.asList(drive.split(",")))), 3000);
		}
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

	private void drive(List<String> phases) {
		if (phases.isEmpty()) {
			Log.i(TAG, "done");
			return;
		}
		String next = phases.remove(0);
		Runnable then = () -> {
			summary();
			main.postDelayed(() -> drive(phases), 1000);
		};
		start(next);
		switch (next) {
			case "type":
				type(then);
				break;
			case "fling":
				fling(then);
				break;
			case "paste":
				paste(then);
				break;
			case "select":
				select(then);
				break;
			case "jump":
				jump(then);
				break;
			case "big":
				big(then);
				break;
			default:
				main.postDelayed(then, 5000);
				break;
		}
	}

	private void start(String name) {
		synchronized (frames) {
			frames.clear();
		}
		draws.clear();
		edits.clear();
		phase = name;
		phaseCpu = Process.getElapsedCpuTime();
		phaseMainCpu = SystemClock.currentThreadTimeMillis();
		phaseRenderCpu = renderThreadCpu();
	}

	private void type(Runnable then) {
		Text text = document.text();
		int middle = text.lineCount() / 2;
		int offset = text.lineStart(middle) + text.line(middle).length();
		document.select(offset, offset);
		view.bringCaretIntoView();
		EditCommands commands = new EditCommands(document);
		main.postDelayed(() -> start("type"), 400);
		for (int i = 0; i < 80; ++i) {
			int n = i;
			main.postDelayed(() -> edit(() -> {
				if (n >= 60) {
					commands.deleteBackward();
				} else if (n % 20 == 19) {
					commands.enter();
				} else {
					Document.Selection s = document.selection();
					document.type(s.start(), s.end(), Text.of(
							String.valueOf(TYPED.charAt(n % TYPED.length()))));
				}
			}), 500 + i * 150L);
		}
		main.postDelayed(then, 500 + 80 * 150L + 500);
	}

	private void fling(Runnable then) {
		int velocity = getIntent().getIntExtra("velocity", 24000);
		view.fling(0, velocity);
		main.postDelayed(() -> view.fling(0, -velocity), 3000);
		main.postDelayed(then, 6000);
	}

	private void paste(Runnable then) {
		new Thread(() -> {
			Text pasted = Text.of(repeat(document.text().toString(), 5000));
			main.post(() -> {
				edit(() -> {
					Document.Selection s = document.selection();
					document.replace(s.start(), s.end(), pasted);
				});
				main.postDelayed(() -> edit(document::undo), 1500);
				main.postDelayed(then, 3000);
			});
		}, "paste").start();
	}

	private void select(Runnable then) {
		int caret = document.selection().head();
		edit(() -> document.select(0, document.text().length()));
		main.postDelayed(() -> edit(() -> document.select(caret, caret)),
				1500);
		main.postDelayed(then, 3000);
	}

	private void jump(Runnable then) {
		edit(() -> {
			int end = document.text().length();
			document.select(end, end);
		});
		main.postDelayed(() -> edit(() -> document.select(0, 0)), 1500);
		main.postDelayed(then, 3000);
	}

	private void big(Runnable then) {
		new Thread(() -> {
			String source = document.text().toString();
			StringBuilder sb = new StringBuilder(1 << 20);
			while (sb.length() < 1 << 20) {
				sb.append(source).append('\n');
			}
			sb.setLength(1 << 20);
			Text pasted = Text.of(sb);
			main.post(() -> {
				edit(() -> {
					Document.Selection s = document.selection();
					document.replace(s.start(), s.end(), pasted);
				});
				main.postDelayed(() -> edit(document::undo), 2000);
				main.postDelayed(then, 4000);
			});
		}, "big").start();
	}

	private void edit(Runnable change) {
		long start = System.nanoTime();
		change.run();
		view.bringCaretIntoView();
		pendingEdit = new long[]{System.nanoTime() - start, 0, 0};
		edits.add(pendingEdit);
	}

	private void drawn(long start, long end) {
		if (firstDrawEnd == 0 && document != null) {
			firstDrawEnd = end;
			firstDraw = end - start;
		}
		draws.add(new long[]{start, end - start});
		if (pendingEdit != null) {
			pendingEdit[1] = end - start;
			pendingEdit[2] = end;
			pendingEdit = null;
		}
	}

	@RequiresApi(Build.VERSION_CODES.O)
	private void frame(Window window, FrameMetrics metrics, int dropped) {
		long main = 0;
		for (int id : new int[]{FrameMetrics.INPUT_HANDLING_DURATION,
				FrameMetrics.ANIMATION_DURATION,
				FrameMetrics.LAYOUT_MEASURE_DURATION,
				FrameMetrics.DRAW_DURATION, FrameMetrics.SYNC_DURATION}) {
			main += metrics.getMetric(id);
		}
		synchronized (frames) {
			frames.add(new long[]{
					metrics.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP),
					metrics.getMetric(FrameMetrics.TOTAL_DURATION), main});
		}
	}

	private void summary() {
		List<long[]> copy;
		synchronized (frames) {
			copy = new ArrayList<>(frames);
		}
		long[] change = new long[edits.size()];
		long[] onDraw = new long[edits.size()];
		long[] frame = new long[edits.size()];
		long[] sum = new long[edits.size()];
		for (int i = 0; i < edits.size(); ++i) {
			long[] edit = edits.get(i);
			change[i] = edit[0];
			onDraw[i] = edit[1];
			for (long[] f : copy) {
				if (edit[2] >= f[0] && edit[2] <= f[0] + f[1]) {
					frame[i] = f[2];
				}
			}
			sum[i] = change[i] + frame[i];
			Log.i(TAG, "edit phase=" + phase + " i=" + i + " change=" +
					change[i] / 1000 + "us onDraw=" + onDraw[i] / 1000 +
					"us frame=" + frame[i] / 1000 + "us");
		}
		long[] drawTimes = new long[draws.size()];
		for (int i = 0; i < drawTimes.length; ++i) {
			drawTimes[i] = draws.get(i)[1];
		}
		long[] frameMain = new long[copy.size()];
		long[] frameTotal = new long[copy.size()];
		for (int i = 0; i < frameMain.length; ++i) {
			frameMain[i] = copy.get(i)[2];
			frameTotal[i] = copy.get(i)[1];
		}
		if ("idle".equals(phase)) {
			StringBuilder at = new StringBuilder();
			for (long[] draw : draws) {
				at.append(' ').append((draw[0] - draws.get(0)[0]) / 1_000_000);
			}
			Log.i(TAG, "draws phase=idle at(ms)=" + at);
		}
		Log.i(TAG, "summary phase=" + phase + " frames=" + copy.size() +
				" onDraws=" + drawTimes.length + " onDraw " +
				stats(drawTimes) + " | frameMain " + stats(frameMain) +
				" | frameTotal " + stats(frameTotal));
		if (change.length > 0) {
			Log.i(TAG, "summary phase=" + phase + " edits=" + change.length +
					" change " + stats(change) + " | onDraw " +
					stats(onDraw) + " | frame " + stats(frame) +
					" | change+frame " + stats(sum));
		}
		float refresh = getWindowManager().getDefaultDisplay()
				.getRefreshRate();
		long frameTime = (long) (1e9 / refresh);
		int over = 0;
		for (long total : frameTotal) {
			if (total > frameTime) {
				++over;
			}
		}
		Log.i(TAG, "jank phase=" + phase + " refresh=" +
				String.format(Locale.ROOT, "%.1f", refresh) + "Hz frameTime=" +
				frameTime / 1000 + "us frames=" + frameTotal.length +
				" over=" + over);
		long mainCpu = SystemClock.currentThreadTimeMillis() - phaseMainCpu;
		long renderCpu = renderThreadCpu() - phaseRenderCpu;
		Log.i(TAG, "cpu phase=" + phase + " main=" + mainCpu + "ms render=" +
				renderCpu + "ms other=" + (Process.getElapsedCpuTime() -
				phaseCpu - mainCpu - renderCpu) + "ms");
	}

	// From the user and system time in /proc, in ticks of 10 ms.
	private static long renderThreadCpu() {
		File[] tasks = new File("/proc/self/task").listFiles();
		if (tasks == null) {
			return 0;
		}
		for (File task : tasks) {
			try {
				if (!"RenderThread".equals(read(new File(task, "comm"))
						.trim())) {
					continue;
				}
				String stat = read(new File(task, "stat"));
				String[] fields = stat.substring(stat.lastIndexOf(')') + 2)
						.split(" ");
				return (Long.parseLong(fields[11]) +
						Long.parseLong(fields[12])) * 10;
			} catch (IOException | RuntimeException e) {
				return 0;
			}
		}
		return 0;
	}

	private static String read(File file) throws IOException {
		try (InputStream in = new FileInputStream(file)) {
			return readAll(in);
		}
	}

	private static String readAll(InputStream in) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[] buffer = new byte[65536];
		for (int n; (n = in.read(buffer)) > 0; ) {
			out.write(buffer, 0, n);
		}
		return out.toString("UTF-8");
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
			return readAll(in);
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

	private static String stats(long[] values) {
		if (values.length == 0) {
			return "n/a";
		}
		long[] sorted = values.clone();
		Arrays.sort(sorted);
		int n = sorted.length;
		return "p50=" + sorted[n / 2] / 1000 + "us p90=" +
				sorted[Math.min(n - 1, n * 9 / 10)] / 1000 + "us p99=" +
				sorted[Math.min(n - 1, n * 99 / 100)] / 1000 + "us max=" +
				sorted[n - 1] / 1000 + "us";
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

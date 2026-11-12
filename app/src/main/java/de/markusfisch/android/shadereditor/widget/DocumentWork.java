package de.markusfisch.android.shadereditor.widget;

import android.os.Handler;
import android.os.Looper;
import android.os.Process;

import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import de.markusfisch.android.shadereditor.editor.AnalysisScheduler;
import de.markusfisch.android.shadereditor.editor.Document;
import de.markusfisch.android.shadereditor.editor.LineStyles;
import de.markusfisch.android.shadereditor.editor.LineStylesScheduler;

/**
 * The background work for the document a CodeView shows, on one thread
 * that ends when it has nothing to do: the continuations of its line
 * styles, and its analysis, whose type names colour the lines.
 */
final class DocumentWork {
	private static final long ANALYSIS_DELAY = 50;

	private final Handler handler = new Handler(Looper.getMainLooper());
	private final AnalysisScheduler.MainThread main =
			new AnalysisScheduler.MainThread() {
				@Override
				public void post(Runnable task, long delayMillis) {
					handler.postDelayed(task, delayMillis);
				}

				@Override
				public void cancel(Runnable task) {
					handler.removeCallbacks(task);
				}
			};
	private final Executor thread = new ThreadPoolExecutor(0, 1, 1,
			TimeUnit.SECONDS, new LinkedBlockingQueue<>(), task ->
			new Thread(() -> {
				Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
				task.run();
			}, "DocumentWork"));
	private final Executor background = this::execute;
	private LineStylesScheduler stylesScheduler;
	private AnalysisScheduler analysisScheduler;
	private int generation;

	/**
	 * Start the work for document and its styles, in place of the work
	 * before; restyled runs after a line may have new colours.
	 */
	void start(Document document, LineStyles styles, Runnable restyled) {
		stop();
		++generation;
		stylesScheduler = new LineStylesScheduler(document, styles,
				background, main, restyled::run);
		analysisScheduler = new AnalysisScheduler(document, ANALYSIS_DELAY,
				background, main, analysis -> {
			if (styles.setTypeNames(analysis.typeNames())) {
				restyled.run();
			}
		});
	}

	// The analysis of a huge text may not fit in memory; the lines then
	// keep their colours without it.
	private void execute(Runnable task) {
		int started = generation;
		thread.execute(() -> {
			try {
				task.run();
			} catch (OutOfMemoryError e) {
				handler.post(() -> {
					if (started == generation && analysisScheduler != null) {
						analysisScheduler.close();
						analysisScheduler = null;
					}
				});
			}
		});
	}

	/** Stop the work; results still on their way are dropped. */
	void stop() {
		if (stylesScheduler != null) {
			stylesScheduler.close();
			stylesScheduler = null;
		}
		if (analysisScheduler != null) {
			analysisScheduler.close();
			analysisScheduler = null;
		}
	}
}

package de.markusfisch.android.shadereditor.editor;

import java.util.concurrent.Executor;

/**
 * Keeps the {@link Analysis} of a document: a delay after the last
 * change it analyses the current text on a background executor and
 * hands the result to the main thread, where it is delivered only if the
 * document is still at that revision. Everything but the analysis
 * itself runs on the main thread.
 */
public final class AnalysisScheduler {
	/** The main thread: a Handler on Android. */
	public interface MainThread {
		/** Run task on the main thread; may be called on any thread. */
		void post(Runnable task, long delayMillis);

		void cancel(Runnable task);
	}

	public interface Callback {
		void onAnalysis(Analysis analysis);
	}

	private final Document document;
	private final long delayMillis;
	private final Executor background;
	private final MainThread main;
	private final Callback callback;
	private final Runnable start = this::start;
	private final Document.Listener listener = new Document.Listener() {
		@Override
		public void onUpdate() {
			if (document.revision() != scheduled) {
				schedule(delayMillis);
			}
		}
	};
	// The revision a background task should still analyse; a task
	// queued behind a newer one skips its work.
	private volatile int wanted = -1;
	private int scheduled;
	private boolean closed;
	private Analysis latest = Analysis.NONE;

	/**
	 * Start analysing document now and again delayMillis after each
	 * change, until {@link #close()}.
	 */
	public AnalysisScheduler(Document document, long delayMillis,
			Executor background, MainThread main, Callback callback) {
		this.document = document;
		this.delayMillis = delayMillis;
		this.background = background;
		this.main = main;
		this.callback = callback;
		document.addListener(listener);
		schedule(0);
	}

	/**
	 * Return the last analysis delivered; its revision may be older than
	 * the document's.
	 */
	public Analysis latest() {
		return latest;
	}

	/** Stop analysing; results still on their way are dropped. */
	public void close() {
		closed = true;
		wanted = -1;
		main.cancel(start);
		document.removeListener(listener);
	}

	private void schedule(long delay) {
		scheduled = document.revision();
		main.cancel(start);
		main.post(start, delay);
	}

	private void start() {
		Text text = document.text();
		int revision = document.revision();
		wanted = revision;
		background.execute(() -> {
			if (revision != wanted) {
				return;
			}
			Analysis analysis = Analysis.of(text, revision);
			main.post(() -> deliver(analysis), 0);
		});
	}

	private void deliver(Analysis analysis) {
		if (!closed && analysis.revision() == document.revision()) {
			latest = analysis;
			callback.onAnalysis(analysis);
		}
	}
}

package de.markusfisch.android.shadereditor.editor;

import java.util.concurrent.Executor;

import de.markusfisch.android.shadereditor.syntax.Syntax;

// Analyses the current text on a background executor a delay after the last
// change, and delivers the result on the main thread if the document is still
// at that revision. After a pass of d ms the next starts no sooner than SPACING
// d after it, so while the user types analysis keeps the background thread busy
// at most a ninth of the time, however long the text. A pass gives up once the
// document changes; when changes stop, one more runs to the end.
public final class AnalysisScheduler {
	// A Handler on Android.
	public interface MainThread {
		// May be called on any thread.
		void post(Runnable task, long delayMillis);

		void cancel(Runnable task);
	}

	public interface Callback {
		void onAnalysis(Analysis analysis);
	}

	// Analysis.of but in tests.
	interface Pass {
		Analysis run(Text text, int revision, Analysis previous,
				Analysis.Cancel cancel);
	}

	static final int SPACING = 8;

	private final Document document;
	private final long delayMillis;
	private final Executor background;
	private final MainThread main;
	private final Callback callback;
	private final Document.Clock clock;
	private final Pass pass;
	private final Runnable start = this::start;
	private final Document.Listener listener = new Document.Listener() {
		@Override
		public void onUpdate() {
			if (document.revision() != scheduled) {
				wanted = -1;
				changedAt = clock.millis();
				schedule();
			}
		}
	};
	// The revision the running pass should still analyse.
	private volatile int wanted = -1;
	private int scheduled;
	private long changedAt;
	private long idleUntil;
	private boolean running;
	private boolean closed;
	private Analysis latest = Analysis.NONE;

	// Now, and delayMillis after each change, until close().
	public AnalysisScheduler(Document document, Syntax syntax,
			long delayMillis, Executor background, MainThread main,
			Callback callback) {
		this(document, delayMillis, background, main, callback,
				() -> System.nanoTime() / 1_000_000,
				(text, revision, previous, cancel) -> Analysis.of(syntax,
						text, revision, previous, cancel));
	}

	AnalysisScheduler(Document document, long delayMillis,
			Executor background, MainThread main, Callback callback,
			Document.Clock clock, Pass pass) {
		this.document = document;
		this.delayMillis = delayMillis;
		this.background = background;
		this.main = main;
		this.callback = callback;
		this.clock = clock;
		this.pass = pass;
		document.addListener(listener);
		changedAt = clock.millis() - delayMillis;
		schedule();
	}

	// Its revision may be older than the document's.
	public Analysis latest() {
		return latest;
	}

	// Results still on their way are dropped.
	public void close() {
		closed = true;
		wanted = -1;
		main.cancel(start);
		document.removeListener(listener);
	}

	// Starts the next pass when the rule allows, or, while one runs,
	// leaves that to finish.
	private void schedule() {
		scheduled = document.revision();
		main.cancel(start);
		if (!running) {
			main.post(start, Math.max(0, Math.max(changedAt + delayMillis,
					idleUntil) - clock.millis()));
		}
	}

	private void start() {
		Text text = document.text();
		int revision = document.revision();
		Analysis previous = latest;
		wanted = revision;
		running = true;
		background.execute(() -> {
			long begin = clock.millis();
			Analysis analysis = pass.run(text, revision, previous,
					() -> revision != wanted);
			long took = clock.millis() - begin;
			main.post(() -> finish(analysis, took), 0);
		});
	}

	private void finish(Analysis analysis, long took) {
		running = false;
		if (closed) {
			return;
		}
		idleUntil = clock.millis() + SPACING * took;
		if (analysis != null && analysis.revision() == document.revision()) {
			latest = analysis;
			callback.onAnalysis(analysis);
		} else {
			schedule();
		}
	}
}

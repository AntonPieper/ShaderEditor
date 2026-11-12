package de.markusfisch.android.shadereditor.editor;

import java.util.concurrent.Executor;

/**
 * Makes the stale lines of a {@link LineStyles} current: whenever the
 * document updates and lines are stale, it runs a continuation on a
 * background executor and hands its result to apply on the main thread,
 * one call per message, so the main thread never stores more than one
 * call's states at a time. It goes on until no line is stale. Everything
 * but the continuation itself runs on the main thread.
 */
public final class LineStylesScheduler {
	public interface Callback {
		/** Called after each apply; lines may need drawing again. */
		void onApplied();
	}

	// The most lines one continuation tokenizes before its result is
	// stored, so the lines near the edit get their colours early.
	private static final int CHUNK = 16384;

	private final LineStyles styles;
	private final Document document;
	private final Executor background;
	private final AnalysisScheduler.MainThread main;
	private final Callback callback;
	private final Document.Listener listener = new Document.Listener() {
		@Override
		public void onUpdate() {
			schedule();
		}
	};
	private boolean running;
	private boolean closed;

	/** Keep the lines of styles, which belongs to document, current. */
	public LineStylesScheduler(Document document, LineStyles styles,
			Executor background, AnalysisScheduler.MainThread main,
			Callback callback) {
		this.document = document;
		this.styles = styles;
		this.background = background;
		this.main = main;
		this.callback = callback;
		document.addListener(listener);
		schedule();
	}

	/** Stop; results still on their way are dropped. */
	public void close() {
		closed = true;
		document.removeListener(listener);
	}

	private void schedule() {
		if (closed || running) {
			return;
		}
		LineStyles.Continuation continuation = styles.continuation();
		if (continuation == null) {
			return;
		}
		running = true;
		background.execute(() -> {
			LineStyles.Result result = continuation.run(CHUNK);
			main.post(() -> deliver(result), 0);
		});
	}

	private void deliver(LineStyles.Result result) {
		if (closed) {
			return;
		}
		boolean more = styles.apply(result);
		callback.onApplied();
		if (more) {
			main.post(() -> deliver(result), 0);
			return;
		}
		running = false;
		schedule();
	}
}

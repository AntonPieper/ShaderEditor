package de.markusfisch.android.shadereditor.project;

import java.util.ArrayDeque;
import java.util.concurrent.Executor;

/**
 * A worker, a main thread and the timers of the main thread as queues
 * a test steps by hand, each by itself, to deliver a result of the
 * worker, act, and only then run what it posted to main.
 */
final class Steps {
	final Queue worker = new Queue();
	final Queue main = new Queue();
	// Timers run when the test says, whatever their delay.
	final Queue later = new Queue();
	final OpenProject.Later timers = (task, millis) -> later.execute(task);

	// Run what the worker and the main thread were given until both are
	// idle.
	void settle() {
		while (worker.run() | main.run()) {
			// Again: a task may have posted another.
		}
	}

	static final class Queue implements Executor {
		private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

		@Override
		public void execute(Runnable task) {
			tasks.add(task);
		}

		// Run every task given so far and those they give; return
		// whether there was any.
		boolean run() {
			boolean ran = false;
			while (step()) {
				ran = true;
			}
			return ran;
		}

		int size() {
			return tasks.size();
		}

		// Forget what was given, as when the process dies.
		void clear() {
			tasks.clear();
		}

		// Run the task given first; return whether there was one.
		boolean step() {
			Runnable task = tasks.poll();
			if (task == null) {
				return false;
			}
			task.run();
			return true;
		}
	}
}

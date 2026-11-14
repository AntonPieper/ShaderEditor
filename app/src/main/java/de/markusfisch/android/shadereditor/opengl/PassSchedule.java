package de.markusfisch.android.shadereditor.opengl;

import androidx.annotation.NonNull;

import java.util.Arrays;
import java.util.List;

import de.markusfisch.android.shadereditor.project.HostDirective.Frame;
import de.markusfisch.android.shadereditor.project.RenderPlan;
import de.markusfisch.android.shadereditor.project.RenderPlan.Source;

// What the frames of a plan draw, decided once after linking. A reset,
// on the first frame after the targets were made, clears every history
// to opaque black and draws the initial content of the passes in reset
// over it, in that order: an image, or a pass drawn with time and frame
// at zero that reads the other passes as they start, drawn once for all
// passes that start as it. Every frame then
// draws the passes in frame, presents the running one, and only then
// makes what each pass drew its previous frame, so all reads of a
// previous frame in one frame see the same frame.
final class PassSchedule {
	// Drawn every frame, each after the passes whose current frame it
	// reads.
	@NonNull
	final int[] frame;
	// Whose history a reset initializes, in this order.
	@NonNull
	final int[] reset;
	// By entry of reset: whether its initial shader is drawn there, the
	// first time a reset needs it.
	@NonNull
	private final boolean[] draws;
	// By pass.
	@NonNull
	private final boolean[] target;
	@NonNull
	private final boolean[] history;
	@NonNull
	private final boolean[] initialized;

	private PassSchedule(
			@NonNull int[] frame,
			@NonNull int[] reset,
			@NonNull boolean[] draws,
			@NonNull boolean[] target,
			@NonNull boolean[] history,
			@NonNull boolean[] initialized) {
		this.frame = frame;
		this.reset = reset;
		this.draws = draws;
		this.target = target;
		this.history = history;
		this.initialized = initialized;
	}

	// backbuffer tells for every pass whether its program reads its
	// previous frame through an unannotated backbuffer, which only
	// linking knows. A plan that lists a pass before one whose current
	// frame it reads, as a cycle would need, is a mistake of its maker.
	@NonNull
	static PassSchedule of(@NonNull RenderPlan plan, @NonNull boolean[] backbuffer) {
		var passes = plan.passes();
		int count = passes.size();
		// Whose previous frame a pass drawn every frame reads.
		var read = new boolean[count];
		for (int i = 0; i < count; ++i) {
			var pass = passes.get(i);
			if (!pass.perFrame()) {
				continue;
			}
			read[i] |= backbuffer[i];
			for (var input : pass.inputs()) {
				int producer = producer(input);
				if (producer > -1 && input.frame() == Frame.PREVIOUS) {
					read[producer] = true;
				}
			}
		}
		// From the last initialized to the first, so a pass is known to
		// be read on reset before its own turn: an initial shader that is
		// drawn reads what other passes start as.
		var initialized = new boolean[count];
		var drawnOnReset = new boolean[count];
		var readOnReset = new boolean[count];
		var resets = plan.reset();
		int initializedCount = 0;
		for (int r = resets.size() - 1; r >= 0; --r) {
			int pass = resets.get(r);
			initialized[pass] = read[pass] || readOnReset[pass];
			if (!initialized[pass]) {
				continue;
			}
			++initializedCount;
			var initial = passes.get(pass).initial();
			if (initial.kind() == Source.Kind.PASS) {
				int shader = initial.pass();
				drawnOnReset[shader] = true;
				readOnReset[shader] |= backbuffer[shader];
				for (var input : passes.get(shader).inputs()) {
					int producer = producer(input);
					if (producer > -1) {
						readOnReset[producer] = true;
					}
				}
			}
		}
		var reset = new int[initializedCount];
		var draws = new boolean[initializedCount];
		var drawn = new boolean[count];
		for (int i = 0, r = 0; i < resets.size(); ++i) {
			int pass = resets.get(i);
			if (!initialized[pass]) {
				continue;
			}
			var initial = passes.get(pass).initial();
			if (initial.kind() == Source.Kind.PASS && !drawn[initial.pass()]) {
				drawn[initial.pass()] = true;
				draws[r] = true;
			}
			reset[r++] = pass;
		}
		var target = new boolean[count];
		var history = new boolean[count];
		for (int i = 0; i < count; ++i) {
			target[i] = passes.get(i).perFrame() || drawnOnReset[i] ||
					initialized[i];
			// A pass drawn on reset must not draw over what it starts as
			// while another reads that.
			history[i] = read[i] || (drawnOnReset[i] && readOnReset[i]);
		}
		return new PassSchedule(order(passes), reset, draws, target, history,
				initialized);
	}

	// Whether entry i of reset draws its initial shader; a later entry
	// with the same shader stretches what that one drew.
	boolean drawsInitial(int i) {
		return draws[i];
	}

	// Whether the pass draws, or holds what it starts as.
	boolean hasTarget(int pass) {
		return target[pass];
	}

	// Whether its target keeps the previous frame beside the one it
	// draws.
	boolean keepsHistory(int pass) {
		return history[pass];
	}

	// Whether its initial content is drawn on reset; initial content
	// that nothing reads has no effect.
	boolean isInitialized(int pass) {
		return initialized[pass];
	}

	// The plan lists every pass after the passes whose current frame it
	// reads, ties in the order of their paths.
	@NonNull
	private static int[] order(@NonNull List<RenderPlan.Pass> passes) {
		var order = new int[passes.size()];
		var placed = new boolean[passes.size()];
		int count = 0;
		for (int i = 0; i < passes.size(); ++i) {
			var pass = passes.get(i);
			if (!pass.perFrame()) {
				continue;
			}
			for (var input : pass.inputs()) {
				int producer = producer(input);
				if (producer > -1 && input.frame() == Frame.CURRENT &&
						!placed[producer]) {
					throw new IllegalArgumentException("Pass " + i +
							" reads the current frame of pass " + producer +
							" before it is drawn");
				}
			}
			placed[i] = true;
			order[count++] = i;
		}
		return Arrays.copyOf(order, count);
	}

	// The pass whose output the input reads, or -1.
	private static int producer(@NonNull RenderPlan.Input input) {
		var source = input.source();
		return source.kind() == Source.Kind.PASS ? source.pass() : -1;
	}
}

package de.markusfisch.android.shadereditor.opengl;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

import de.markusfisch.android.shadereditor.project.ProjectDiagnostic;
import de.markusfisch.android.shadereditor.project.ProjectPath;
import de.markusfisch.android.shadereditor.project.RenderPlan;
import de.markusfisch.android.shadereditor.project.HostDirective.Frame;
import de.markusfisch.android.shadereditor.project.RenderPlan.Source;

/**
 * The passes of the build that draws, as a host shows them: rows in
 * drawing order, each pass after the passes whose current frame it
 * reads, and every first-frame shader in a row of its own directly
 * above the first pass it seeds. A pass is named by its file, which
 * stays its name from one build to the next.
 *
 * @param rows the rows, top to bottom
 */
public record RunningPasses(@NonNull List<Row> rows) {
	public static final RunningPasses NONE = new RunningPasses(List.of());

	public enum Kind {
		/** Drawn every frame. */
		PASS,
		/** Drawn once, at the first frame and on Restart, for others. */
		FIRST_FRAME
	}

	public enum ReadKind {
		/** The frame the other pass drew just before, in this frame. */
		THIS_FRAME,
		/** The frame before; from its own row, a pass reads itself. */
		LAST_FRAME,
		/** What a first-frame shader drew for this pass to start as. */
		FIRST_FRAME
	}

	/** A read of the row at index row, by the row that lists it. */
	public record Read(int row, @NonNull ReadKind kind) {
	}

	/**
	 * One pass.
	 *
	 * @param file       its file
	 * @param kind       drawn every frame, or once as a first frame
	 * @param output     whether it is what the screen shows
	 * @param drawn      whether it draws at all: a first frame nothing
	 *                   reads is not drawn
	 * @param reads      what it reads, in the order of its inputs, then
	 *                   what it starts as
	 * @param pictures   the picture files it reads
	 * @param startsFrom the picture its first frame is, or null
	 * @param problems   the build's warnings in its file
	 */
	public record Row(
			@NonNull ProjectPath file,
			@NonNull Kind kind,
			boolean output,
			boolean drawn,
			@NonNull List<Read> reads,
			@NonNull List<ProjectPath> pictures,
			@Nullable ProjectPath startsFrom,
			@NonNull List<ProjectDiagnostic> problems) {
		public Row {
			reads = List.copyOf(reads);
			pictures = List.copyOf(pictures);
			problems = List.copyOf(problems);
		}
	}

	public RunningPasses {
		rows = List.copyOf(rows);
	}

	/** The index of the row of file, or -1. */
	public int indexOf(@NonNull ProjectPath file) {
		for (int i = 0; i < rows.size(); ++i) {
			if (rows.get(i).file.equals(file)) {
				return i;
			}
		}
		return -1;
	}

	// The rows of a linked plan: schedule says what draws, backbuffer
	// which passes read their own last frame without an input.
	@NonNull
	static RunningPasses of(
			@NonNull RenderPlan plan,
			@NonNull PassSchedule schedule,
			@NonNull boolean[] backbuffer,
			@NonNull List<ProjectDiagnostic> warnings) {
		var passes = plan.passes();
		int count = passes.size();
		// The plan's index of every row, in order.
		var order = new ArrayList<Integer>();
		var placed = new boolean[count];
		for (int pass : schedule.frame) {
			// A first-frame shader goes above the first pass it seeds.
			var initial = passes.get(pass).initial();
			if (initial != null && initial.kind() == Source.Kind.PASS) {
				int seed = initial.pass();
				if (!passes.get(seed).perFrame() && !placed[seed]) {
					placed[seed] = true;
					order.add(seed);
				}
			}
			placed[pass] = true;
			order.add(pass);
		}
		// Whatever else the plan holds: a first frame of a pass that is
		// not drawn.
		for (int i = 0; i < count; ++i) {
			if (!placed[i]) {
				order.add(i);
			}
		}
		var rowOf = new int[count];
		for (int r = 0; r < order.size(); ++r) {
			rowOf[order.get(r)] = r;
		}
		var drawnOnce = new boolean[count];
		for (int i = 0; i < schedule.reset.length; ++i) {
			if (schedule.drawsInitial(i)) {
				var initial = passes.get(schedule.reset[i]).initial();
				drawnOnce[initial.pass()] = true;
			}
		}
		var rows = new ArrayList<Row>();
		for (int index : order) {
			var pass = passes.get(index);
			var file = plan.programs().get(pass.program()).file();
			var reads = new ArrayList<Read>();
			var pictures = new ArrayList<ProjectPath>();
			for (var input : pass.inputs()) {
				var source = input.source();
				if (source.kind() == Source.Kind.PASS) {
					reads.add(new Read(rowOf[source.pass()],
							input.frame() == Frame.PREVIOUS
									? ReadKind.LAST_FRAME
									: ReadKind.THIS_FRAME));
				} else if (source.kind() == Source.Kind.IMAGE &&
						!pictures.contains(source.image())) {
					pictures.add(source.image());
				}
			}
			if (backbuffer[index]) {
				reads.add(new Read(rowOf[index], ReadKind.LAST_FRAME));
			}
			var initial = pass.initial();
			ProjectPath startsFrom = null;
			if (initial != null && schedule.isInitialized(index)) {
				if (initial.kind() == Source.Kind.PASS) {
					reads.add(new Read(rowOf[initial.pass()],
							ReadKind.FIRST_FRAME));
				} else if (initial.kind() == Source.Kind.IMAGE) {
					startsFrom = initial.image();
				}
			}
			var problems = new ArrayList<ProjectDiagnostic>();
			for (var warning : warnings) {
				if (file.equals(warning.file())) {
					problems.add(warning);
				}
			}
			rows.add(new Row(file,
					pass.perFrame() ? Kind.PASS : Kind.FIRST_FRAME,
					index == plan.output(),
					pass.perFrame() || drawnOnce[index],
					reads, pictures, startsFrom, problems));
		}
		return new RunningPasses(rows);
	}
}

package de.markusfisch.android.shadereditor.project;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import de.markusfisch.android.shadereditor.project.HostDirective.Filter;
import de.markusfisch.android.shadereditor.project.HostDirective.Format;
import de.markusfisch.android.shadereditor.project.HostDirective.Frame;
import de.markusfisch.android.shadereditor.project.HostDirective.Mipmap;
import de.markusfisch.android.shadereditor.project.HostDirective.Wrap;

/**
 * What to render, with every default applied: programs, passes in
 * execution order, and what each pass reads. Passes refer to programs
 * and to other passes by index.
 * <p>
 * Whether a pass reads the implicit, unannotated {@code backbuffer} is
 * known only after linking, so it is not part of the plan. After
 * linking, the renderer gives every pass whose program has an active
 * {@code backbuffer} uniform without an input a history texture too,
 * and samples it with {@link Sampling#PASS}.
 *
 * @param programs programs to compile
 * @param passes   passes in execution order: a pass comes after every
 *                 pass it reads in the current frame, ties broken by
 *                 the order of the pass files' paths
 * @param output   index of the running shader's pass
 * @param reset    indices of passes with an initial source, in the
 *                 order their history is initialized on reset
 */
public record RenderPlan(
		List<Program> programs,
		List<Pass> passes,
		int output,
		List<Integer> reset) {
	public RenderPlan {
		programs = List.copyOf(programs);
		passes = List.copyOf(passes);
		reset = List.copyOf(reset);
	}

	/**
	 * One pass file with its includes expanded and host directives
	 * blanked.
	 *
	 * @param file        the pass file
	 * @param lines       the source the compiler gets, without the
	 *                    lines the engine generates
	 * @param sourceMap   the origin of every line
	 * @param versionLine 1-based line of {@code #version}, or 0; the
	 *                    engine's generated lines go after it
	 */
	public record Program(
			ProjectPath file,
			List<String> lines,
			SourceMap sourceMap,
			int versionLine) {
		public Program {
			lines = List.copyOf(lines);
		}

		public String text() {
			return lines.isEmpty() ? "" : String.join("\n", lines) + "\n";
		}
	}

	/**
	 * One draw per project frame into its own target.
	 *
	 * @param program  index of its program
	 * @param inputs   the inputs its files declare
	 * @param target   its output
	 * @param initial  what its history starts as on reset: an image, a
	 *                 pass drawn once with time and frame at zero, or
	 *                 null for opaque black
	 * @param history  whether a declared input reads its previous frame;
	 *                 see the class comment for the implicit backbuffer
	 * @param perFrame false for a pass that is only drawn on reset as an
	 *                 initial source
	 */
	public record Pass(
			int program,
			List<Input> inputs,
			Target target,
			Source initial,
			boolean history,
			boolean perFrame) {
		public Pass {
			inputs = List.copyOf(inputs);
		}
	}

	/**
	 * A sampler uniform and what it reads. An annotated
	 * {@code backbuffer} reads the previous frame of its own pass.
	 *
	 * @param frame which frame of a pass source is read; images and
	 *              engine samplers are always {@link Frame#CURRENT}
	 * @param file  the file with the input directive
	 * @param line  the 1-based line of the input directive
	 */
	public record Input(
			String uniform,
			String samplerType,
			Source source,
			Frame frame,
			Sampling sampling,
			ProjectPath file,
			int line) {
	}

	/**
	 * An image, the output of a pass, or the engine sampler named by
	 * the input's uniform.
	 *
	 * @param image the image file of {@link Kind#IMAGE}, else null
	 * @param pass  the pass index of {@link Kind#PASS}, else -1
	 */
	public record Source(Kind kind, ProjectPath image, int pass) {
		public enum Kind {IMAGE, PASS, ENGINE}

		public static final Source ENGINE = new Source(Kind.ENGINE, null, -1);

		public static Source image(ProjectPath image) {
			return new Source(Kind.IMAGE, image, -1);
		}

		public static Source pass(int pass) {
			return new Source(Kind.PASS, null, pass);
		}
	}

	/**
	 * How an input samples its source.
	 */
	public record Sampling(
			Filter min,
			Filter mag,
			Mipmap mipmap,
			Wrap wrapS,
			Wrap wrapT) {
		public static final Sampling IMAGE = new Sampling(Filter.LINEAR,
				Filter.LINEAR, Mipmap.NONE, Wrap.CLAMP, Wrap.CLAMP);
		/** Pass outputs, including the backbuffer. */
		public static final Sampling PASS = new Sampling(Filter.NEAREST,
				Filter.NEAREST, Mipmap.NONE, Wrap.CLAMP, Wrap.CLAMP);
		/** The camera samplers' existing default. */
		public static final Sampling CAMERA = new Sampling(Filter.NEAREST,
				Filter.LINEAR, Mipmap.NONE, Wrap.CLAMP, Wrap.CLAMP);

		/**
		 * Apply the options an input wrote to the defaults of its kind of
		 * source.
		 */
		public static ProjectParseResult<Sampling> of(HostDirective.Input input) {
			Sampling defaults;
			if (input.source() != null) {
				defaults = input.source().getKind() == ProjectPath.Kind.IMAGE
						? IMAGE
						: PASS;
			} else if ("backbuffer".equals(input.uniform())) {
				defaults = PASS;
			} else {
				defaults = CAMERA;
				if ((input.wrapS() != null && input.wrapS() != Wrap.CLAMP) ||
						(input.wrapT() != null && input.wrapT() != Wrap.CLAMP)) {
					return ProjectParseResult.error("Camera images can only " +
									"be read with wrap=clamp, remove wrap=",
							input.file(), input.line());
				}
			}
			return ProjectParseResult.success(new Sampling(
					or(input.min(), defaults.min),
					or(input.mag(), defaults.mag),
					or(input.mipmap(), defaults.mipmap),
					or(input.wrapS(), defaults.wrapS),
					or(input.wrapT(), defaults.wrapT)));
		}

		private static <T> T or(T value, T fallback) {
			return value != null ? value : fallback;
		}
	}

	/**
	 * The output of a pass: scaled from the project render size when
	 * width and height are 0, else that fixed size.
	 *
	 * @param present how the running shader's output is scaled to the
	 *                surface; no effect on other passes
	 * @param file    the file with the target directive, or the pass
	 *                file when there is none
	 * @param line    the 1-based line of the directive, or 0
	 */
	public record Target(
			double scale,
			int width,
			int height,
			Format format,
			Filter present,
			ProjectPath file,
			int line) {
		/**
		 * Apply the defaults to what a pass file's target directive left
		 * unset; target may be null.
		 */
		public static Target of(HostDirective.Target target, ProjectPath file) {
			if (target == null) {
				return new Target(1, 0, 0, Format.RGBA8, Filter.NEAREST,
						file, 0);
			}
			boolean fixed = target.width() != null;
			return new Target(
					target.scale() != null ? target.scale() : 1,
					fixed ? target.width() : 0,
					fixed ? target.height() : 0,
					target.format() != null ? target.format() : Format.RGBA8,
					target.present() != null ? target.present() : Filter.NEAREST,
					target.file(), target.line());
		}

		public boolean isFixed() {
			return width > 0;
		}

		/**
		 * Return the size of this target for a project render size, or
		 * why it cannot be allocated with textures of at most maxSize.
		 */
		public ProjectParseResult<Size> size(Size project, int maxSize) {
			if (!isFixed()) {
				return ProjectParseResult.success(new Size(
						scaled(project.width), scaled(project.height)));
			} else if (width > maxSize || height > maxSize) {
				return ProjectParseResult.error("Target size " + width + "x" +
						height + " is larger than " + maxSize + ", the " +
						"largest texture this device supports", file, line);
			}
			return ProjectParseResult.success(new Size(width, height));
		}

		// The scale is exactly the decimal that was written.
		private int scaled(int dimension) {
			return Math.max(1, BigDecimal.valueOf(scale)
					.multiply(BigDecimal.valueOf(dimension))
					.setScale(0, RoundingMode.FLOOR)
					.intValue());
		}
	}

	public record Size(int width, int height) {
		/**
		 * Return the project render size of a surface at a quality, as
		 * the engine has always computed it, or why it is invalid.
		 */
		public static ProjectParseResult<Size> project(
				int surfaceWidth,
				int surfaceHeight,
				float quality,
				int maxSize) {
			if (surfaceWidth < 1 || surfaceHeight < 1) {
				return ProjectParseResult.error("Invalid surface size " +
						surfaceWidth + "x" + surfaceHeight, null, 0);
			} else if (!(quality > 0) || Float.isInfinite(quality)) {
				return ProjectParseResult.error("Invalid quality " + quality,
						null, 0);
			}
			int width = Math.max(1, Math.round(surfaceWidth * quality));
			int height = Math.max(1, Math.round(surfaceHeight * quality));
			if (width > maxSize || height > maxSize) {
				return ProjectParseResult.error("Render size " + width + "x" +
						height + " is larger than " + maxSize + ", the " +
						"largest texture this device supports, lower the " +
						"quality", null, 0);
			}
			return ProjectParseResult.success(new Size(width, height));
		}
	}
}

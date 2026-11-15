package de.markusfisch.android.shadereditor.tuning;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.markusfisch.android.shadereditor.editor.Document;
import de.markusfisch.android.shadereditor.opengl.Tuning;
import de.markusfisch.android.shadereditor.project.Builtins;
import de.markusfisch.android.shadereditor.project.FileKind;
import de.markusfisch.android.shadereditor.project.FileTexts.Callback;
import de.markusfisch.android.shadereditor.project.OpenProject;
import de.markusfisch.android.shadereditor.project.Preparation;
import de.markusfisch.android.shadereditor.project.PreprocessorScan;
import de.markusfisch.android.shadereditor.project.ProjectDiagnostic;
import de.markusfisch.android.shadereditor.project.ProjectFiles;
import de.markusfisch.android.shadereditor.project.ProjectPath;
import de.markusfisch.android.shadereditor.project.RenderPlan;
import de.markusfisch.android.shadereditor.project.SourceLocation;

/**
 * The running shader prepared with one number of one file replaced by
 * the name of a uniform the host sets, for the engine to build while
 * the number is tuned. The file may be the one that runs, a pass or an
 * include; only the number's characters change, so every line keeps
 * its number and errors stay on the user's lines. The engine declares
 * the uniform in each program that uses it ({@link Tuning}).
 *
 * Where GLSL needs a constant and the tokens say so
 * ({@link ConstantContext}), nothing is built: the number can only be
 * tuned by building its new text each time.
 */
public final class TunedPreparation {
	/** What the uniform's names start with; a number follows. */
	static final String PREFIX = "se_tune";

	private static final Pattern SPLICE = Pattern.compile(
			"\\\\(?:\r\n|\n|\r)");
	private static final Pattern NAMES = Pattern.compile(
			"(?<![A-Za-z0-9_])" + PREFIX + "(\\d+)(?![A-Za-z0-9_])");

	/** The project as the editor has it, see {@link #of}. */
	public interface Project {
		/** Return the text of path as the editor has it, or null. */
		@Nullable
		String text(@NonNull ProjectPath path);

		/**
		 * Prepare the running shader with replacement in the text, as
		 * {@link OpenProject#prepare(Builtins, OpenProject.Replacement,
		 * Callback)} does; null when nothing runs. done is called on
		 * the main thread, or not at all when the text changed
		 * meanwhile.
		 */
		void prepare(@NonNull OpenProject.Replacement replacement,
				@NonNull Callback<Preparation> done);
	}

	/** What became of the number. */
	public interface Result {
	}

	/**
	 * Ready to build: preparation has the number replaced by name;
	 * number is the number as the text has it.
	 */
	public record Ready(@NonNull Preparation preparation, @NonNull String name,
			@NonNull NumberLiteral number) implements Result {
	}

	/** The number is where GLSL needs a constant: kind says where. */
	public record Constant(@NonNull ConstantContext.Kind kind,
			@NonNull NumberLiteral number) implements Result {
	}

	/** It cannot be tuned: errors say why. */
	public record Failed(@NonNull List<ProjectDiagnostic> errors)
			implements Result {
	}

	private TunedPreparation() {
	}

	/** The project of the editor, its builds with builtins. */
	@NonNull
	public static Project of(@NonNull OpenProject project,
			@NonNull Builtins builtins) {
		return new Project() {
			@Nullable
			@Override
			public String text(@NonNull ProjectPath path) {
				Document document = project.document(path);
				return document != null ? document.text().toString() : null;
			}

			@Override
			public void prepare(@NonNull OpenProject.Replacement replacement,
					@NonNull Callback<Preparation> done) {
				project.prepare(builtins, replacement,
						build -> done.done(build.preparation()));
			}
		};
	}

	/**
	 * Prepare the running shader with the number from start to end of
	 * path replaced, when it is one of type; done gets what became of
	 * it on the main thread.
	 */
	public static void prepare(@NonNull Project project,
			@NonNull ProjectPath path, int start, int end,
			@NonNull Tuning.Type type, @NonNull Callback<Result> done) {
		String text = project.text(path);
		NumberLiteral number = text != null
				? NumberLiteral.at(text, start, end)
				: null;
		if (number == null || number.type() != type) {
			done.done(failed(path, text, start, number == null
					? "not a number of the code"
					: "a " + number.type().name().toLowerCase(
							java.util.Locale.ROOT) + ", not a " +
					type.name().toLowerCase(java.util.Locale.ROOT)));
			return;
		}
		prepare(project, path, start, end, text, number, PREFIX + 0, done);
	}

	private static void prepare(@NonNull Project project,
			@NonNull ProjectPath path, int start, int end,
			@NonNull String text, @NonNull NumberLiteral number,
			@NonNull String name, @NonNull Callback<Result> done) {
		project.prepare(new OpenProject.Replacement(path, start, end, name),
				preparation -> {
					if (preparation == null) {
						done.done(failed(path, text, start, "nothing runs"));
						return;
					}
					RenderPlan plan = preparation.plan();
					if (plan == null) {
						done.done(new Failed(errors(preparation)));
						return;
					}
					// A name a file of the build has is not taken.
					String free = free(preparation, name);
					if (!free.equals(name)) {
						prepare(project, path, start, end, text, number,
								free, done);
						return;
					}
					Boolean es100 = readsAsEs100(plan, path);
					if (es100 == null) {
						done.done(failed(path, text, start,
								"not part of what runs"));
						return;
					}
					ConstantContext.Kind kind = ConstantContext.of(text, start,
							es100);
					done.done(kind != null
							? new Constant(kind, number)
							: new Ready(preparation, name, number));
				});
	}

	// name when it is in the files of preparation once, as it replaced
	// the number, else a name none of them has.
	@NonNull
	private static String free(@NonNull Preparation preparation,
			@NonNull String name) {
		int found = 0;
		long largest = -1;
		for (Map.Entry<ProjectPath, ProjectFiles.Content> file :
				preparation.files().entrySet()) {
			byte[] bytes = file.getValue().bytes();
			if (FileKind.picture(bytes) != null) {
				continue;
			}
			// After splicing, as GLSL reads names (GLSL ES 3.00, 3.3): a
			// name split by a backslash at a line's end is one name. No
			// other way makes a name: GLSL ES has no token pasting.
			Matcher matcher = NAMES.matcher(SPLICE.matcher(
					new String(bytes, StandardCharsets.UTF_8)).replaceAll(""));
			while (matcher.find()) {
				found += matcher.group().equals(name) ? 1 : 0;
				try {
					largest = Math.max(largest,
							Long.parseLong(matcher.group(1)));
				} catch (NumberFormatException e) {
					// More digits than a long: no name of ours.
				}
			}
		}
		return found == 1 ? name : PREFIX + (largest + 1);
	}

	// Whether a program in GLSL ES 1.00 reads path; null when no
	// program does.
	@Nullable
	private static Boolean readsAsEs100(@NonNull RenderPlan plan,
			@NonNull ProjectPath path) {
		Boolean es100 = null;
		for (RenderPlan.Program program : plan.programs()) {
			if (reads(program, path)) {
				es100 = (es100 != null && es100) || version(program) < 300;
			}
		}
		return es100;
	}

	private static boolean reads(@NonNull RenderPlan.Program program,
			@NonNull ProjectPath path) {
		for (SourceLocation location : program.sourceMap().lines()) {
			if (location != null && path.equals(location.file())) {
				return true;
			}
		}
		return false;
	}

	private static int version(@NonNull RenderPlan.Program program) {
		for (PreprocessorScan.Directive directive : PreprocessorScan.scan(
				program.lines(), null).directives()) {
			if (directive.kind() == PreprocessorScan.Kind.VERSION) {
				String argument = directive.argument();
				int digits = 0;
				while (digits < argument.length() &&
						Character.isDigit(argument.charAt(digits))) {
					++digits;
				}
				return digits > 0
						? Integer.parseInt(argument.substring(0, digits))
						: 100;
			}
		}
		return 100;
	}

	@NonNull
	private static List<ProjectDiagnostic> errors(
			@NonNull Preparation preparation) {
		List<ProjectDiagnostic> errors = new ArrayList<>();
		for (ProjectDiagnostic diagnostic : preparation.diagnostics()) {
			if (diagnostic.severity() == ProjectDiagnostic.Severity.ERROR) {
				errors.add(diagnostic);
			}
		}
		return errors;
	}

	@NonNull
	private static Failed failed(@NonNull ProjectPath path,
			@Nullable String text, int start, @NonNull String message) {
		int line = 1;
		if (text != null) {
			for (int i = 0; i < Math.min(start, text.length()); ++i) {
				line += text.charAt(i) == '\n' ? 1 : 0;
			}
		}
		return new Failed(List.of(new ProjectDiagnostic(
				ProjectDiagnostic.Severity.ERROR, message, path, line)));
	}
}

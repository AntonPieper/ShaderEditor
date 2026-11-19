package de.markusfisch.android.shadereditor.tuning;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.markusfisch.android.shadereditor.editor.Text;
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
 * The running shader prepared with numbers of one file replaced, each by
 * the name of a {@link Tuning} or by another text. Only the numbers'
 * characters change, so errors stay on the user's lines.
 */
public final class TunedPreparation {
	/** What the uniforms' names start with; a number follows. */
	static final String PREFIX = "se_tune";

	private static final Pattern SPLICE = Pattern.compile(
			"\\\\(?:\r\n|\n|\r)");
	private static final Pattern NAMES = Pattern.compile(
			"(?<![A-Za-z0-9_])" + PREFIX + "(\\d+)(?![A-Za-z0-9_])");

	/** The project as the editor has it, see {@link #of}. */
	public interface Project {
		/** Return the text of path as the editor has it, or null. */
		@Nullable
		Text text(@NonNull ProjectPath path);

		/**
		 * As {@link OpenProject#prepare(Builtins, List, Callback)} does;
		 * done never hears of a text that changed meanwhile.
		 */
		void prepare(@NonNull List<OpenProject.Replacement> replacements,
				@NonNull Callback<Preparation> done);
	}

	/** What became of a build. */
	public interface Result {
	}

	/** constants are those of tried that GLSL needs as constants. */
	public record Ready(@NonNull Preparation preparation,
			@NonNull List<Integer> constants) implements Result {
	}

	/** It cannot be built: errors say why. */
	public record Failed(@NonNull List<ProjectDiagnostic> errors)
			implements Result {
	}

	/** A file has a name of ours; names are all the prefix's it has. */
	public record Taken(@NonNull Set<String> names) implements Result {
	}

	private TunedPreparation() {
	}

	/** The name of uniform n. */
	@NonNull
	public static String name(long n) {
		return PREFIX + n;
	}

	/**
	 * parts replace numbers of path, each by a {@link #name} in
	 * parentheses or a text; tried are the literals' offsets of uniforms
	 * new to the build. worker reads the whole text; done runs on main.
	 */
	public static void prepare(@NonNull Project project,
			@NonNull ProjectPath path,
			@NonNull List<OpenProject.Replacement> parts,
			@NonNull List<Integer> tried, @NonNull Executor worker,
			@NonNull Executor main, @NonNull Callback<Result> done) {
		project.prepare(parts, preparation -> {
			Text text = project.text(path);
			worker.execute(() -> {
				Result result = result(preparation, path, text, parts, tried);
				main.execute(() -> done.done(result));
			});
		});
	}

	@NonNull
	private static Result result(@Nullable Preparation preparation,
			@NonNull ProjectPath path, @Nullable Text file,
			@NonNull List<OpenProject.Replacement> parts,
			@NonNull List<Integer> tried) {
		String text = file != null ? file.toString() : null;
		int at = parts.isEmpty() ? 0 : parts.get(0).start();
		if (preparation == null) {
			return failed(path, text, at, "nothing runs");
		}
		RenderPlan plan = preparation.plan();
		if (plan == null) {
			return new Failed(errors(preparation));
		}
		Boolean es100 = readsAsEs100(plan, path);
		if (es100 == null || text == null) {
			return failed(path, text, at, "not part of what runs");
		}
		Set<String> ours = new HashSet<>();
		for (OpenProject.Replacement part : parts) {
			Matcher name = NAMES.matcher(part.text());
			if (name.find()) {
				ours.add(name.group());
			}
		}
		Set<String> taken = taken(preparation, ours);
		if (taken != null) {
			return new Taken(taken);
		}
		List<Integer> constants = new ArrayList<>();
		for (int offset : tried) {
			if (ConstantContext.of(text, offset, es100) != null) {
				constants.add(offset);
			}
		}
		return new Ready(preparation, constants);
	}

	// Null when the files of preparation have each of ours once, as the
	// parts put them there; else every name of the prefix they have.
	@Nullable
	private static Set<String> taken(@NonNull Preparation preparation,
			@NonNull Set<String> ours) {
		Map<String, Integer> found = new HashMap<>();
		for (Map.Entry<ProjectPath, ProjectFiles.Content> file :
				preparation.files().entrySet()) {
			byte[] bytes = file.getValue().bytes();
			if (FileKind.picture(bytes) != null) {
				continue;
			}
			// Spliced, as GLSL reads names (GLSL ES 3.00, 3.3); GLSL ES
			// has no token pasting.
			Matcher matcher = NAMES.matcher(SPLICE.matcher(
					new String(bytes, StandardCharsets.UTF_8)).replaceAll(""));
			while (matcher.find()) {
				Integer count = found.get(matcher.group());
				found.put(matcher.group(), count == null ? 1 : count + 1);
			}
		}
		for (String name : ours) {
			Integer count = found.get(name);
			if (count == null || count != 1) {
				return found.keySet();
			}
		}
		return null;
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

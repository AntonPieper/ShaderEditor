package de.markusfisch.android.shadereditor.opengl;

import android.opengl.GLES20;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.markusfisch.android.shadereditor.project.ProjectDiagnostic;
import de.markusfisch.android.shadereditor.project.SourceMap;

// What a linked program reads that the engine does not set: an active
// uniform that nothing provides, and a builtin declared with a type that
// does not fit. Both are warnings; the shader runs and reads zero there.
final class UniformWarnings {
	// The fix for a sampler without a provider in a project file.
	static final String ADD_INPUT =
			"attach an image to it with #pragma se input \"path\"";

	// An active uniform by the name it is declared with; builtin is the
	// table entry its type does not fit, or null when nothing provides it.
	record Finding(
			@NonNull String name,
			@NonNull GlUniform uniform,
			@Nullable UniformDef builtin) {
	}

	private static final Pattern COMMENT = Pattern.compile(
			"//[^\n]*|/\\*.*?(\\*/|$)", Pattern.DOTALL);

	private UniformWarnings() {
	}

	// GL thread, once per build. Locates each warning at its declaration
	// in text, the source the program was compiled from.
	@NonNull
	static List<ProjectDiagnostic> check(
			@NonNull Collection<GlUniform> reflected,
			@NonNull Set<String> provided,
			@NonNull String textureFix,
			@NonNull String text,
			@Nullable SourceMap sourceMap) {
		var warnings = new ArrayList<ProjectDiagnostic>();
		for (var finding : find(reflected, UniformTable.BUILTINS, provided)) {
			warnings.add(locate(
					message(finding, UniformTable.BUILTINS, textureFix),
					declarationLine(text, finding.name()),
					sourceMap));
		}
		return warnings;
	}

	// The active uniforms, once per declared name and in name order, that
	// neither a provided name nor a builtin of a fitting type feeds.
	@NonNull
	static List<Finding> find(
			@NonNull Collection<GlUniform> reflected,
			@NonNull List<UniformDef> table,
			@NonNull Set<String> provided) {
		var findings = new TreeMap<String, Finding>();
		for (var uniform : reflected) {
			String name = declaredName(uniform.name());
			// Members of uniform blocks have no location, and drivers
			// may list the uniforms of GLSL itself.
			if (uniform.location() < 0 ||
					name.startsWith("gl_") ||
					provided.contains(name) ||
					findings.containsKey(name)) {
				continue;
			}
			var builtin = builtin(table, name);
			if (builtin == null) {
				findings.put(name, new Finding(name, uniform, null));
			} else if (!builtin.fits(uniform) || !(name.equals(uniform.name()) ||
					uniform.name().equals(name + "[0]"))) {
				findings.put(name, new Finding(name, uniform, builtin));
			}
		}
		return new ArrayList<>(findings.values());
	}

	// The builtin of a type that fits uniform with the name nearest to
	// name, for a likely typo, or null: at most one edit for every three
	// characters of name, and never more than two.
	@Nullable
	static UniformDef nearestBuiltin(
			@NonNull String name,
			@NonNull GlUniform uniform,
			@NonNull List<UniformDef> table) {
		int limit = Math.min(2, name.length() / 3);
		UniformDef nearest = null;
		int nearestDistance = limit + 1;
		for (var builtin : table) {
			if (!builtin.getType().accepts(uniform.type())) {
				continue;
			}
			int distance = distance(name, builtin.getName());
			if (distance > 0 && distance < nearestDistance) {
				nearest = builtin;
				nearestDistance = distance;
			}
		}
		return nearest;
	}

	// Edits from a to b: insertions, deletions, substitutions and swaps
	// of neighbours, ignoring case, but names that differ in case only
	// are one edit apart.
	static int distance(@NonNull String a, @NonNull String b) {
		if (a.equals(b)) {
			return 0;
		}
		String x = a.toLowerCase(Locale.ROOT);
		String y = b.toLowerCase(Locale.ROOT);
		if (x.equals(y)) {
			return 1;
		}
		int[][] d = new int[x.length() + 1][y.length() + 1];
		for (int i = 0; i <= x.length(); ++i) {
			d[i][0] = i;
		}
		for (int j = 0; j <= y.length(); ++j) {
			d[0][j] = j;
		}
		for (int i = 1; i <= x.length(); ++i) {
			for (int j = 1; j <= y.length(); ++j) {
				int cost = x.charAt(i - 1) == y.charAt(j - 1) ? 0 : 1;
				d[i][j] = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1),
						d[i - 1][j - 1] + cost);
				if (i > 1 && j > 1 &&
						x.charAt(i - 1) == y.charAt(j - 2) &&
						x.charAt(i - 2) == y.charAt(j - 1)) {
					d[i][j] = Math.min(d[i][j], d[i - 2][j - 2] + 1);
				}
			}
		}
		return d[x.length()][y.length()];
	}

	// The 1-based line of the first uniform declaration of name outside
	// of comments, or 0. Preprocessor conditions are not evaluated.
	static int declarationLine(@NonNull String text, @NonNull String name) {
		var code = new StringBuilder(text);
		Matcher comment = COMMENT.matcher(text);
		while (comment.find()) {
			for (int i = comment.start(); i < comment.end(); ++i) {
				if (code.charAt(i) != '\n') {
					code.setCharAt(i, ' ');
				}
			}
		}
		Matcher declaration = Pattern.compile("\\buniform\\b[^;{}]*?\\b(" +
				Pattern.quote(name) + ")\\b").matcher(code);
		if (!declaration.find()) {
			return 0;
		}
		int line = 1;
		for (int i = declaration.start(1); i-- > 0; ) {
			if (text.charAt(i) == '\n') {
				++line;
			}
		}
		return line;
	}

	@NonNull
	static String message(
			@NonNull Finding finding,
			@NonNull List<UniformDef> table,
			@NonNull String textureFix) {
		String name = finding.name();
		var builtin = finding.builtin();
		if (builtin != null) {
			return name + " is not set because its type does not fit; " +
					"the builtin is " + builtin.getType().getKeyword() + " " +
					builtin.getDeclarator();
		}
		var uniform = finding.uniform();
		var nearest = nearestBuiltin(name, uniform, table);
		if (nearest != null) {
			return "Nothing provides " + name + ", so it reads " +
					(uniform.isSampler() ? "black" : "zero") +
					"; did you mean " + nearest.getName() + "?";
		}
		if (!uniform.isSampler()) {
			return "Nothing provides " + name + ", so it reads zero";
		}
		int target = uniform.textureTarget();
		return "Nothing provides " + name + ", so it reads black" +
				(target == GLES20.GL_TEXTURE_2D ||
						target == GLES20.GL_TEXTURE_CUBE_MAP
						? "; " + String.format(Locale.ROOT, textureFix, name)
						: "");
	}

	// Without the index of an array or the member of a struct.
	@NonNull
	private static String declaredName(@NonNull String name) {
		int end = name.length();
		int bracket = name.indexOf('[');
		int dot = name.indexOf('.');
		if (bracket > -1) {
			end = bracket;
		}
		if (dot > -1 && dot < end) {
			end = dot;
		}
		return name.substring(0, end);
	}

	@Nullable
	private static UniformDef builtin(
			@NonNull List<UniformDef> table,
			@NonNull String name) {
		for (var builtin : table) {
			if (builtin.getName().equals(name)) {
				return builtin;
			}
		}
		return null;
	}

	@NonNull
	private static ProjectDiagnostic locate(
			@NonNull String message,
			int preparedLine,
			@Nullable SourceMap sourceMap) {
		if (sourceMap == null || preparedLine < 1) {
			return new ProjectDiagnostic(ProjectDiagnostic.Severity.WARNING,
					message, null, Math.max(preparedLine, 0));
		}
		var at = sourceMap.locate(preparedLine);
		return at == null || at.isGenerated()
				? new ProjectDiagnostic(ProjectDiagnostic.Severity.WARNING,
				message, null, 0)
				: new ProjectDiagnostic(ProjectDiagnostic.Severity.WARNING,
				message, at.file(), at.line());
	}
}

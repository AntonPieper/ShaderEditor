package de.markusfisch.android.shadereditor.project;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The lines of the shader files of a build that none of its programs
 * compiles. It speaks only of a pass's own file and never of a file one
 * of them includes, which other shaders may include with other macros,
 * as a guarded library is off the second time it is included.
 */
public final class BuiltLines {
	/** What finds the off lines of one file for another text of it. */
	public interface Again {
		List<Integer> off(String text);

		/** Whether the other files still have the texts it reads. */
		default boolean holds() {
			return true;
		}
	}

	// The lines a program skips of a file, and its #version.
	private record Program(int version, Set<Integer> off) {
	}

	private final Map<ProjectPath, List<Program>> files = new HashMap<>();

	/** Run the directives of every program of plan. */
	public static BuiltLines of(RenderPlan plan) {
		BuiltLines built = new BuiltLines();
		Set<ProjectPath> included = new HashSet<>();
		for (RenderPlan.Program program : plan.programs()) {
			CompiledSource compiled = CompiledSource.of(program.lines(),
					program.head(), program.sourceMap());
			DirectiveRun run = DirectiveRun.of(compiled.text(),
					compiled.version());
			List<SourceLocation> lines = compiled.sourceMap().lines();
			Map<ProjectPath, Set<Integer>> off = new HashMap<>();
			for (int n = 0; n < lines.size(); ++n) {
				SourceLocation at = lines.get(n);
				if (at.includedFrom() != null) {
					included.add(at.file());
				} else if (!at.isGenerated()) {
					Set<Integer> file = off.get(at.file());
					if (file == null) {
						file = new HashSet<>();
						off.put(at.file(), file);
					}
					if (run.state(n) == DirectiveRun.State.OFF) {
						file.add(at.line() - 1);
					}
				}
			}
			for (Map.Entry<ProjectPath, Set<Integer>> file : off.entrySet()) {
				List<Program> programs = built.files.get(file.getKey());
				if (programs == null) {
					programs = new ArrayList<>();
					built.files.put(file.getKey(), programs);
				}
				programs.add(new Program(compiled.version(), file.getValue()));
			}
		}
		built.files.keySet().removeAll(included);
		return built;
	}

	/**
	 * Return what prepares again what preparation, the build of entry,
	 * prepared, from the files it read and the text it is given for path,
	 * and returns the lines of path that no program compiles on OpenGL
	 * ES glesVersion. It does a build's work but compiling, so it runs
	 * off the main thread.
	 */
	public static Again again(Preparation preparation, ProjectPath entry,
			Builtins builtins, ProjectPath path, int glesVersion) {
		Map<ProjectPath, ProjectFiles.Content> read = preparation.files();
		return text -> {
			ProjectFiles files = p -> p.equals(path)
					? new ProjectFiles.Content(
							text.getBytes(StandardCharsets.UTF_8), "edited")
					: read.get(p);
			RenderPlan plan = Preparation.prepare(files, entry,
					ProjectLimits.DEFAULT, builtins).plan();
			return plan != null ? of(plan).off(path, glesVersion) : List.of();
		};
	}

	/**
	 * Return the 0-based lines of file that no program compiles on OpenGL
	 * ES glesVersion, in order; none for a file that is included or not
	 * compiled. A program the engine refuses before compiling it, as one
	 * of GLSL ES 3.00 on OpenGL ES 2.0, was not compiled.
	 */
	public List<Integer> off(ProjectPath file, int glesVersion) {
		List<Program> programs = files.get(file);
		Set<Integer> off = null;
		for (int i = 0; programs != null && i < programs.size(); ++i) {
			Program program = programs.get(i);
			if (!CompiledSource.compiles(program.version, glesVersion)) {
				continue;
			} else if (off == null) {
				off = new TreeSet<>(program.off);
			} else {
				off.retainAll(program.off);
			}
		}
		return off != null ? new ArrayList<>(off) : List.of();
	}
}

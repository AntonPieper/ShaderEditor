package de.markusfisch.android.shadereditor.project;

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
	// Per file, the lines each program that compiles it skips.
	private final Map<ProjectPath, List<Set<Integer>>> files =
			new HashMap<>();

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
				List<Set<Integer>> programs = built.files.get(file.getKey());
				if (programs == null) {
					programs = new ArrayList<>();
					built.files.put(file.getKey(), programs);
				}
				programs.add(file.getValue());
			}
		}
		built.files.keySet().removeAll(included);
		return built;
	}

	/**
	 * Return the 0-based lines of file that no program compiles, in
	 * order; none for a file that is included or not compiled.
	 */
	public List<Integer> off(ProjectPath file) {
		List<Set<Integer>> programs = files.get(file);
		if (programs == null) {
			return List.of();
		}
		Set<Integer> off = new TreeSet<>(programs.get(0));
		for (Set<Integer> program : programs) {
			off.retainAll(program);
		}
		return new ArrayList<>(off);
	}
}

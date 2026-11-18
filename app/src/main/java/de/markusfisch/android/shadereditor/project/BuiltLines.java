package de.markusfisch.android.shadereditor.project;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * What the programs of a build say about the lines of the shader files
 * they compile: which lines none of them compiles, and what a macro use
 * expands to. It speaks only of a pass's own file and never of a file
 * one of them includes, which other shaders may include with other
 * macros, as a guarded library is off the second time it is included.
 */
public final class BuiltLines {
	private final Map<ProjectPath, List<Integer>> off = new HashMap<>();
	// Every compiled place of a line of a pass's own file: its program's
	// run and the line there.
	private final Map<ProjectPath, Map<Integer, List<Place>>> places =
			new HashMap<>();

	private record Place(Directives run, int line) {
	}

	/** What runs the directives of one compiled text. */
	public interface Directives {
		DirectiveRun.State state(int line);

		String expansion(int line, int column, String name);
	}

	/** Makes what runs the directives of a text of a version. */
	public interface Evaluator {
		Directives run(String text, int version);
	}

	/** Run the directives of every program of plan. */
	public static BuiltLines of(RenderPlan plan) {
		return of(plan, DirectiveRun::of);
	}

	/** Run the directives of every program of plan with evaluator. */
	public static BuiltLines of(RenderPlan plan, Evaluator evaluator) {
		BuiltLines built = new BuiltLines();
		Set<ProjectPath> included = new HashSet<>();
		for (RenderPlan.Program program : plan.programs()) {
			CompiledSource compiled = CompiledSource.of(program.lines(),
					program.head(), program.sourceMap());
			Directives run = evaluator.run(compiled.text(),
					compiled.version());
			List<SourceLocation> lines = compiled.sourceMap().lines();
			for (int n = 0; n < lines.size(); ++n) {
				SourceLocation at = lines.get(n);
				if (at.isGenerated()) {
					continue;
				} else if (at.includedFrom() != null) {
					included.add(at.file());
					continue;
				}
				Map<Integer, List<Place>> file = built.places.get(at.file());
				if (file == null) {
					file = new HashMap<>();
					built.places.put(at.file(), file);
				}
				List<Place> line = file.get(at.line() - 1);
				if (line == null) {
					line = new ArrayList<>();
					file.put(at.line() - 1, line);
				}
				line.add(new Place(run, n));
			}
		}
		built.places.keySet().removeAll(included);
		for (Map.Entry<ProjectPath, Map<Integer, List<Place>>> file :
				built.places.entrySet()) {
			List<Integer> lines = new ArrayList<>();
			for (Map.Entry<Integer, List<Place>> line :
					file.getValue().entrySet()) {
				boolean off = true;
				for (Place place : line.getValue()) {
					off &= place.run.state(place.line) ==
							DirectiveRun.State.OFF;
				}
				if (off) {
					lines.add(line.getKey());
				}
			}
			Collections.sort(lines);
			built.off.put(file.getKey(), lines);
		}
		return built;
	}

	/**
	 * Return the 0-based lines of file that no program compiles, in
	 * order; none for a file that is included or not compiled.
	 */
	public List<Integer> off(ProjectPath file) {
		List<Integer> lines = off.get(file);
		return lines != null ? lines : List.of();
	}

	/**
	 * Return what the use of the macro name at the 0-based line and
	 * column of file becomes in every program that compiles it, or null
	 * when they differ or one cannot tell.
	 */
	public String expansion(ProjectPath file, int line, int column,
			String name) {
		Map<Integer, List<Place>> lines = places.get(file);
		List<Place> at = lines != null ? lines.get(line) : null;
		String expansion = null;
		for (int i = 0; at != null && i < at.size(); ++i) {
			Place place = at.get(i);
			String here = place.run.expansion(place.line, column, name);
			if (here == null || i > 0 && !Objects.equals(here, expansion)) {
				return null;
			}
			expansion = here;
		}
		return expansion;
	}
}

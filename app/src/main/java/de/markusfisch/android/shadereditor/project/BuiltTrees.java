package de.markusfisch.android.shadereditor.project;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import de.markusfisch.android.shadereditor.tree.ShaderTree;
import de.markusfisch.android.shadereditor.tree.ShaderTree.Meaning;

/**
 * The syntax trees of the programs of a build, and what a name in one
 * of their files means in them. A tree is read of the text the engine
 * compiles ({@link CompiledSource}), with the includes in it as the
 * build puts them in, so a name means what it means to the compiler;
 * places go back to the files by the origin of each compiled line.
 *
 * A file that several programs compile, or that is included more than
 * once, is read in each place; the answers are put together.
 */
public final class BuiltTrees {
	/** A name in a file: its 0-based line, and where it starts and ends in it. */
	public record Place(ProjectPath file, int line, int start, int end) {
	}

	/** Why a name has no place it is declared at. */
	public enum Why {
		/** No name is written there. */
		NO_NAME,
		/** The language or the app itself has the name. */
		LANGUAGE,
		/** No program compiles the place. */
		NOT_COMPILED,
		/** Nothing declares it. */
		NOT_DECLARED,
		/** The text has errors: its declaration may not have been read. */
		BROKEN,
		/** It is a member of a value whose type the text does not say. */
		UNKNOWN_TYPE
	}

	/**
	 * What a name means.
	 *
	 * @param places where it is declared: one place, or several for a
	 *               name in a macro's text that means one thing here
	 *               and another there, or in programs that differ
	 * @param why    with no place: why
	 * @param guess  whether a conditional that could not be decided
	 *               has a part in the answer, so that a driver may
	 *               compile something else
	 */
	public record Definition(List<Place> places, Why why, boolean guess) {
	}

	private record Program(RenderPlan.Program plan, CompiledSource compiled,
			ShaderTree tree, int[] lineStarts) {
	}

	private final List<Program> programs = new ArrayList<>();

	private BuiltTrees() {
	}

	/**
	 * Read the programs of plan. before, if not null, are the trees of
	 * an earlier build: what an edit did not reach is taken from them.
	 * Trees that are read again from others are used by one thread at
	 * a time.
	 */
	public static BuiltTrees of(RenderPlan plan, BuiltTrees before) {
		BuiltTrees built = new BuiltTrees();
		for (RenderPlan.Program program : plan.programs()) {
			CompiledSource compiled = CompiledSource.of(program.lines(),
					program.head(), program.sourceMap());
			String text = compiled.text();
			ShaderTree old = null;
			for (int i = 0; before != null && i < before.programs.size(); ++i) {
				Program was = before.programs.get(i);
				if (was.plan.file().equals(program.file())) {
					old = was.tree;
					text = was.compiled.text().equals(text)
							? was.compiled.text() : text;
				}
			}
			built.programs.add(new Program(program, compiled,
					old == null ? ShaderTree.read(text) : old.edit(text),
					lineStarts(text)));
		}
		return built;
	}

	/**
	 * Return what the name means that is written in a 0-based line of
	 * file at column.
	 */
	public Definition definition(ProjectPath file, int line, int column) {
		List<Place> places = new ArrayList<>();
		Why why = Why.NOT_COMPILED;
		boolean guess = false;
		for (Program program : programs) {
			List<SourceLocation> origins = program.compiled.sourceMap().lines();
			for (int n = 0; n < origins.size(); ++n) {
				SourceLocation origin = origins.get(n);
				// A line that ends in what a compiler cannot read is
				// compiled without it.
				if (origin.isGenerated() || !origin.file().equals(file) ||
						origin.line() != line + 1 ||
						column > program.lineStarts[n + 1] - 1 -
								program.lineStarts[n]) {
					continue;
				}
				List<Meaning> meanings = program.tree.meanings(
						program.lineStarts[n] + column);
				if (meanings.isEmpty()) {
					why = worse(why, Why.NO_NAME);
				}
				for (Meaning meaning : meanings) {
					guess |= meaning.guess;
					Why reason = switch (meaning.kind) {
						case DECLARED, MACRO -> place(program, meaning, places);
						case LANGUAGE -> Why.LANGUAGE;
						case NOT_DECLARED -> program.tree.isBroken()
								? Why.BROKEN : Why.NOT_DECLARED;
						case NOT_READ -> Why.BROKEN;
						case UNKNOWN_TYPE -> Why.UNKNOWN_TYPE;
						default -> Why.NOT_COMPILED;
					};
					why = worse(why, reason);
				}
			}
		}
		return new Definition(places, places.isEmpty() ? why : null, guess);
	}

	// Of two reasons, the one to tell: what cannot be told comes before
	// what is surely so.
	private static Why worse(Why a, Why b) {
		List<Why> order = List.of(Why.NOT_COMPILED, Why.NO_NAME, Why.LANGUAGE,
				Why.NOT_DECLARED, Why.UNKNOWN_TYPE, Why.BROKEN);
		return a == null ? b : b == null ? a
				: order.indexOf(a) >= order.indexOf(b) ? a : b;
	}

	// Adds the place of a declaration, unless it is in a line the
	// engine wrote: then the app has the name.
	private static Why place(Program program, Meaning meaning,
			List<Place> places) {
		int n = Arrays.binarySearch(program.lineStarts, meaning.start);
		n = n >= 0 ? n : -n - 2;
		SourceLocation origin = program.compiled.sourceMap().lines().get(n);
		if (origin.isGenerated()) {
			return Why.LANGUAGE;
		}
		Place place = new Place(origin.file(), origin.line() - 1,
				meaning.start - program.lineStarts[n],
				meaning.end - program.lineStarts[n]);
		if (!places.contains(place)) {
			places.add(place);
		}
		return null;
	}

	// Where each line of a text starts, and the text's length last.
	private static int[] lineStarts(String text) {
		int[] starts = new int[16];
		int lines = 1;
		for (int i = text.indexOf('\n'); i >= 0; i = text.indexOf('\n', i + 1)) {
			if (lines + 1 >= starts.length) {
				starts = Arrays.copyOf(starts, lines * 2);
			}
			starts[lines++] = i + 1;
		}
		starts[lines] = text.length() + 1;
		return Arrays.copyOf(starts, lines + 1);
	}
}

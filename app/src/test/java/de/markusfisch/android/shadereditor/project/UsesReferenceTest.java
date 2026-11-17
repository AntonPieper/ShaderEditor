package de.markusfisch.android.shadereditor.project;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

import de.markusfisch.android.shadereditor.syntax.GlslKnowledge;
import de.markusfisch.android.shadereditor.syntax.Knowledge.Kind;
import de.markusfisch.android.shadereditor.syntax.Knowledge.Symbol;
import de.markusfisch.android.shadereditor.syntax.Knowledge.Use.Why;

/**
 * The uses of every declaration of small generated programs, compared
 * with what a reference says that does what a compiler does. The
 * reference reads the text the driver gets, the one the build makes,
 * not the files; it executes the preprocessor, expands every macro,
 * with and without parameters, parses what is left and binds every
 * name by the scopes of GLSL. What a compiler may or may not define
 * it runs both ways: every outcome is a program of its own.
 *
 * Asserted, for every declaration that is written in a file: the
 * declaration itself, where it is called certain, is one in every
 * outcome that compiles its place; a place that is certain means the
 * declaration in every outcome of every program in which it is
 * compiled, and is compiled in one; every place that means it in any
 * outcome is certain or listed; a place that is said not to be
 * compiled is compiled in no outcome; and the rename itself: with a
 * fresh name at the declaration, at the certain places and at the
 * listed places that are uses in every outcome, every outcome binds
 * every name as before. And for what only the editor takes for a
 * declaration: nothing is certain. Every question has an answer,
 * which is required, so that saying nothing does not pass.
 *
 * Generated besides macros and conditionals: functions with a form
 * for one parameter and for two, and for an int and for a float;
 * heads without a body; structs, their fields and members; a block
 * without a name and one with an instance; a word before a name that
 * is a macro; a library that is included and is a program of its own.
 * What is not generated is listed in docs/project-mode/LANGUAGE.md,
 * "Uses, third shape".
 */
public class UsesReferenceTest {
	private static final int PROGRAMS = 600;
	private static final String FRESH = "zz9";

	@Test
	public void generatedProgramsAgreeWithAReference() {
		int asked = 0;
		int unanswered = 0;
		int exact = 0;
		int certainPlaces = 0;
		int referencePlaces = 0;
		int renamed = 0;
		int unstable = 0;
		int inMacros = 0;
		int inPrograms = 0;
		int agreed = 0;
		int misread = 0;
		// How many places were told for each reason.
		Map<String, Integer> whys = new java.util.TreeMap<>();
		int taken = 0;
		for (int seed = 0; seed < PROGRAMS; ++seed) {
			Generator generator = new Generator(seed);
			Map<String, String> files = generator.files();
			String about = "seed " + seed + ":\n" + files + "\n";
			World world;
			try {
				world = new World(files);
			} catch (RuntimeException e) {
				throw new AssertionError(about + e, e);
			}
			for (Map.Entry<String, Kind> declaration :
					world.declarations().entrySet()) {
				String key = declaration.getKey();
				String file = key.substring(0, key.indexOf(':'));
				int offset = Integer.parseInt(key.substring(
						key.indexOf(':') + 1));
				String text = files.get(file);
				Symbol symbol = symbol(text, offset, declaration.getValue());
				String which = about + symbol.name() + " at " + key;
				++asked;
				ProjectKnowledge.Uses uses = ProjectKnowledge.read(
						UsesHarness.files(files)).uses(ProjectPath.parse(
						file).value(), UsesHarness.REVISION, symbol, null,
						UsesHarness.BUILTINS);
				if (uses == null) {
					++unanswered;
					continue;
				}
				Set<String> certain = new TreeSet<>();
				Set<String> listed = new TreeSet<>();
				boolean declared = false;
				for (ProjectKnowledge.Place place : uses.places()) {
					String at = place.file() + ":" + place.offset();
					if (place.why() == Why.PASTES) {
						continue;
					}
					assertEquals(which, symbol.name(), files.get(
							place.file().toString()).substring(place.offset(),
							place.offset() + symbol.name().length()));
					if (at.equals(key)) {
						// Where it is declared: a place like any other.
						// Certain only if the name is declared there in
						// every outcome that compiles it, and one does;
						// else listed, for whatever reason.
						declared = place.why() == Why.DECLARATION;
						assertTrue(which + " " + place.why(), declared ||
								!place.certain());
						boolean compiled = false;
						for (Outcome outcome : world.outcomes) {
							if (declared && outcome.compiled.contains(key)) {
								compiled = true;
								assertTrue(which + "\nsaid to be declared " +
										"for sure, and is not with " +
										outcome.defined + " in " +
										outcome.program,
										outcome.declarations.containsKey(key));
							}
						}
						assertTrue(which + "\nsaid to be declared for " +
								"sure, and is compiled nowhere", !declared ||
								compiled);
						continue;
					}
					listed.add(at);
					whys.merge(place.why().toString(), 1, Integer::sum);
					if (place.certain()) {
						certain.add(at);
						if (files.size() == 1 && (place.offset() == 0 ||
								text.charAt(place.offset() - 1) != '.')) {
							// (Behind a "." Look up names nothing: it
							// does not look for fields.)
							// A file that includes nothing and that
							// nothing includes: the editor names the
							// same declaration there.
							List<Symbol> looked = GlslKnowledge.of(text)
									.at(place.offset(), symbol.name());
							boolean same = false;
							boolean itself = false;
							for (Symbol found : looked) {
								same |= found.start() == offset;
								itself |= found.type() != null &&
										text.contains("#define " +
												found.type());
							}
							if (itself && !same) {
								// The editor's reader executes no macro,
								// also none that is a number: it takes
								// "N b;" for a declaration of b, and
								// names that where b is used behind it.
								// Known, counted, and no rename starts
								// from what Look up names.
								++misread;
							} else {
								assertTrue(which + "\nLook up at " + at +
										" names " + looked, same);
								++agreed;
							}
						}
					}
					if (place.why() == Why.INACTIVE) {
						// Said not to be compiled: in no outcome.
						for (Outcome outcome : world.outcomes) {
							assertTrue(which + "\ninactive " + at +
											" is compiled with " + outcome.defined,
									!outcome.compiled.contains(at));
						}
					}
				}
				// Of a declaration that is not compiled for sure,
				// nothing is certain.
				assertTrue(which + "\ncertain " + certain +
						" of what is not declared for sure", declared ||
						certain.isEmpty());
				// Certain: the declaration in every outcome in which
				// the place is compiled, and compiled in one.
				Set<String> inAll = null;
				Set<String> inAny = new TreeSet<>();
				for (String place : certain) {
					boolean compiled = false;
					for (Outcome outcome : world.outcomes) {
						if (outcome.compiled.contains(place)) {
							compiled = true;
							assertTrue(which + "\ncertain " + place +
											" is no use with " + outcome.defined +
											" in " + outcome.program + ": " +
											outcome.usesOf(key),
									outcome.means(place, key));
						}
					}
					assertTrue(which + "\ncertain " + place +
							" is compiled nowhere", compiled);
				}
				// Listed: whatever means it in any outcome.
				for (Outcome outcome : world.outcomes) {
					Set<String> means = outcome.usesOf(key);
					inAny.addAll(means);
					if (inAll == null) {
						inAll = new TreeSet<>(means);
					} else {
						inAll.retainAll(means);
					}
				}
				assertTrue(which + "\nlisted " + listed +
								" but the reference has " + inAny,
						listed.containsAll(inAny));
				if (world.outcomes.size() == 1 && !world.macros) {
					// Nothing to list: exactly the reference's, but for
					// calls that fit no form, which are listed if the
					// name has several.
					Set<String> sound = new TreeSet<>(inAny);
					Set<String> said = new TreeSet<>(certain);
					sound.removeAll(world.outcomes.get(0).brokenAt);
					said.removeAll(world.outcomes.get(0).brokenAt);
					assertEquals(which, sound, said);
					++exact;
				}
				certainPlaces += certain.size();
				referencePlaces += inAll.size();
				// A place that means the declaration with one outcome
				// and something else with another: no rename is right
				// for both, and the answer has to say on what it
				// depends.
				boolean stable = true;
				boolean mixed = false;
				boolean programs = false;
				String where = "";
				for (String place : inAny) {
					for (Outcome outcome : world.outcomes) {
						if (outcome.compiled.contains(place) &&
								!outcome.means(place, key)) {
							if (outcome.usesOf(key).contains(place)) {
								// In the text of a macro, a name can
								// mean the declaration where the macro
								// is used once and something else where
								// it is used again: that is no matter
								// of an outcome, and the place is
								// listed for its macro.
								mixed = true;
								continue;
							}
							// In another program only: no matter of an
							// outcome either, and the place is listed
							// for that program.
							boolean here = false;
							for (Outcome other : world.outcomes) {
								here |= other.program.equals(
										outcome.program) &&
										other.compiled.contains(place) &&
										other.means(place, key);
							}
							if (!here) {
								programs = true;
								continue;
							}
							stable = false;
							where = place + " with " + outcome.defined;
						}
					}
				}
				if (mixed) {
					++inMacros;
					continue;
				}
				if (stable && programs) {
					++inPrograms;
					continue;
				}
				if (!stable) {
					assertTrue(which + "\ndepends on an outcome at " +
							where + ", which the answer does not say",
							!uses.depends().isEmpty());
					++unstable;
					continue;
				}
				// The rename itself, by a user who ticks every listed
				// place that is a use and nothing else.
				Set<String> change = new TreeSet<>(certain);
				change.addAll(inAny);
				change.add(key);
				World after;
				try {
					after = new World(rename(files, change,
							symbol.name().length()));
				} catch (RuntimeException e) {
					throw new AssertionError(which + "\nrenamed at " +
							change + ": " + e, e);
				}
				for (int o = 0; o < world.outcomes.size(); ++o) {
					assertTrue(which + "\nrenamed at " + change +
									" binds otherwise with " +
									world.outcomes.get(o).defined + " in " +
									world.outcomes.get(o).program + ":\n" +
									world.outcomes.get(o).bindings + "\n" +
									after.outcomes.get(o).bindings,
							world.outcomes.get(o).bindsAs(
									after.outcomes.get(o)));
				}
				++renamed;
			}
			// What the editor takes for a declaration and no outcome
			// declares: a question a rename may be started with. No
			// answer, or one in which nothing is certain.
			for (Map.Entry<String, String> file : files.entrySet()) {
				if (file.getKey().equals(Generator.READER)) {
					continue;
				}
				String text = file.getValue();
				GlslKnowledge known = GlslKnowledge.of(text);
				for (int i = 0; i < text.length(); ) {
					char c = text.charAt(i);
					if (c == '#' || (c == '/' && text.startsWith("//",
							i))) {
						// A directive's names are a macro's own, and
						// a comment has none.
						int end = text.indexOf('\n', i);
						i = end < 0 ? text.length() : end + 1;
						continue;
					} else if (!Character.isLetter(c) && c != '_') {
						++i;
						while (i < text.length() && Character.isDigit(c) &&
								Character.isLetterOrDigit(text.charAt(i))) {
							++i;
						}
						continue;
					}
					int end = i;
					while (end < text.length() && (Character
							.isLetterOrDigit(text.charAt(end)) ||
							text.charAt(end) == '_')) {
						++end;
					}
					int offset = i;
					String name = text.substring(i, end);
					i = end;
					String key = file.getKey() + ":" + offset;
					Symbol symbol = null;
					for (Symbol found : known.at(offset, name)) {
						if (found.start() == offset &&
								found.kind() != Kind.MACRO) {
							symbol = found;
						}
					}
					if (symbol == null ||
							world.declarations().containsKey(key)) {
						continue;
					}
					++taken;
					ProjectKnowledge.Uses uses = ProjectKnowledge.read(
							UsesHarness.files(files)).uses(ProjectPath
							.parse(file.getKey()).value(),
							UsesHarness.REVISION, symbol, null,
							UsesHarness.BUILTINS);
					if (uses == null) {
						continue;
					}
					for (ProjectKnowledge.Place place : uses.places()) {
						assertTrue(about + name + " at " + key +
								" is declared in no outcome, but " +
								place.file() + ":" + place.offset() +
								" is certain: " + place.why(),
								!place.certain());
					}
				}
			}
		}
		// The programs ask something, and all of it is answered: each
		// of them builds and none makes the reader guess, so no answer
		// would be the easy way out. Of what the reference has, three
		// quarters at least are certain.
		String counts = "asked " + asked + ", unanswered " + unanswered +
				", exact " + exact + ", certain " + certainPlaces + " of " +
				referencePlaces + ", renamed " + renamed +
				", depending on an outcome " + unstable +
				", meaning two things in the text of a macro " + inMacros +
				", meaning two things in two programs " + inPrograms +
				", Look up agreed at " + agreed + " and took " + misread +
				" uses for declarations, taken for a " +
				"declaration by the editor only " + taken + ", places " +
				whys;
		System.out.println("uses against the reference: " + counts);
		assertTrue(counts, asked > PROGRAMS * 4 && unanswered == 0 &&
				exact > 0 && certainPlaces * 4 > referencePlaces * 3);
	}

	// The declaration as the editor knows it, which is what a rename
	// is started with; if the editor does not know it there, as made
	// by hand.
	private static Symbol symbol(String text, int offset, Kind kind) {
		GlslKnowledge known = GlslKnowledge.of(text);
		for (Symbol symbol : known.symbols()) {
			if (symbol.start() == offset) {
				return symbol;
			}
			for (Symbol part : symbol.parts()) {
				if (part.start() == offset) {
					return part;
				}
			}
		}
		int end = offset;
		while (end < text.length() && Character.isLetterOrDigit(
				text.charAt(end))) {
			++end;
		}
		String name = text.substring(offset, end);
		List<Symbol> found = known.at(offset, name);
		if (!found.isEmpty() && found.get(0).start() == offset) {
			return found.get(0);
		}
		return new Symbol(name, kind, name, null, null, offset, end, offset,
				end, List.of(), "int");
	}

	// The files with a fresh name at every place given.
	private static Map<String, String> rename(Map<String, String> files,
			Set<String> places, int length) {
		Map<String, String> renamed = new LinkedHashMap<>();
		for (Map.Entry<String, String> file : files.entrySet()) {
			TreeSet<Integer> offsets = new TreeSet<>();
			for (String place : places) {
				if (place.startsWith(file.getKey() + ":")) {
					offsets.add(Integer.parseInt(place.substring(
							place.indexOf(':') + 1)));
				}
			}
			StringBuilder text = new StringBuilder(file.getValue());
			for (Integer offset : offsets.descendingSet()) {
				text.replace(offset, offset + length, FRESH);
			}
			renamed.put(file.getKey(), text.toString());
		}
		return renamed;
	}

	// ---- The programs ---------------------------------------------------

	private static final class Generator {
		private static final String[] VARIABLES = {"a", "b", "c", "d"};
		private static final String[] FUNCTIONS = {"f", "g", "h"};
		private static final String[] MACROS = {"M", "N", "a", "b"};
		private static final String[] CALLED = {"F", "G"};
		private static final String[] DRIVER = {"GL_A", "GL_B"};
		// A library that is also read as a pass, and the file that
		// reads it so: the library is a program of its own then.
		static final String PASS = "lib.frag";
		static final String READER = "post.frag";
		private final Random random;
		// What an expression may name besides variables: members of
		// the structs and blocks that were declared.
		private final List<String> members = new ArrayList<>();
		private boolean typed;
		private StringBuilder out = new StringBuilder();
		// The macros that are defined where the text is, as far as
		// that is told, and the functions: a function has one body.
		private final Set<String> defined = new HashSet<>();
		private final Set<String> called = new HashSet<>();
		private final Set<String> functions = new HashSet<>();
		private final int level;

		Generator(int seed) {
			random = new Random(seed);
			// 0: no macro but numbers, every condition decided;
			// 1: also conditions that are not; 2: every kind of macro.
			level = seed % 3;
		}

		Map<String, String> files() {
			Map<String, String> files = new LinkedHashMap<>();
			boolean library = random.nextInt(3) == 0;
			// Blocks are of version 300, which the first line says.
			boolean version = random.nextBoolean();
			String name = "lib.glsl";
			if (library) {
				if (random.nextInt(3) == 0) {
					// Included by the main file and read as a pass by
					// another: a program of its own as well, in which
					// nothing stands before it.
					name = PASS;
					files.put(READER, "#pragma se input \"" + PASS +
							"\"\nuniform sampler2D tex;\nvoid main() {}\n");
				}
				out.append("#ifndef LIB_H\n#define LIB_H\n");
				for (int i = 0, n = 1 + random.nextInt(3); i < n; ++i) {
					top(true, 1);
				}
				out.append("#endif\n");
				files.put(name, out.toString());
				out = new StringBuilder();
			}
			if (version) {
				out.append("#version 300 es\n");
			}
			if (library) {
				out.append("#include \"").append(name).append("\"\n");
			}
			if (random.nextInt(3) == 0) {
				line("struct S { int m; int n; };");
				line("S s;");
				members.add("s.m");
				members.add("s.n");
				if (random.nextBoolean()) {
					// A second struct with a field of the same name.
					line("struct U { int m; int k; };");
					line("U t;");
					members.add("t.m");
					members.add("t.k");
					members.add("s.k");
				}
			}
			if (version && random.nextInt(3) == 0) {
				// A block without a name: its fields are variables.
				line("uniform B { int w; int d; };");
				members.add("w");
			}
			if (version && random.nextInt(3) == 0) {
				line("uniform C { int m; int k; } q;");
				members.add("q.m");
				members.add("q.k");
			}
			typed = random.nextInt(3) == 0;
			for (int i = 0, n = 3 + random.nextInt(5); i < n; ++i) {
				top(true, 0);
				if (library && random.nextInt(6) == 0) {
					// Once more: its guard keeps it out.
					out.append("#include \"").append(name).append("\"\n");
				}
			}
			files.put(UsesHarness.MAIN, out.toString());
			return files;
		}

		private String pick(String[] of) {
			return of[random.nextInt(of.length)];
		}

		// A name to declare: none that is a macro where it is
		// compiled, which would be replaced.
		private String fresh(String[] of, boolean active) {
			for (int i = 0; i < 8; ++i) {
				String name = pick(of);
				if (!active || !defined.contains(name)) {
					return name;
				}
			}
			return "e";
		}

		private void line(String text) {
			if (random.nextInt(12) == 0) {
				text += " // " + pick(VARIABLES) + " " + pick(MACROS);
			}
			out.append(text).append('\n');
		}

		private String expression(int depth) {
			if (!members.isEmpty() && random.nextInt(6) == 0) {
				return pick(members.toArray(new String[0]));
			} else if (typed && random.nextInt(8) == 0) {
				// A function with a form for each of two types: which
				// one is meant, its one argument says.
				switch (random.nextInt(4)) {
					case 0:
						return "p(" + random.nextInt(9) + ")";
					case 1:
						return "p(1.5)";
					case 2:
						return "p(u)";
					default:
						return "p(" + pick(VARIABLES) + ")";
				}
			}
			switch (random.nextInt(depth > 1 ? 3 : 7)) {
				case 0:
					return String.valueOf(random.nextInt(9));
				case 1:
				case 2:
					return random.nextInt(4) == 0 ? pick(MACROS)
							: pick(VARIABLES);
				case 3:
					return expression(depth + 1) + " + " +
							expression(depth + 1);
				case 4:
					return "(" + expression(depth + 1) + ")";
				case 5:
					if (level == 2 && !called.isEmpty()) {
						// A macro with parameters, also given a macro.
						String macro = called.iterator().next();
						return macro + "(" + (random.nextInt(4) == 0
								? pick(MACROS) : expression(depth + 1)) + ")";
					}
					return pick(VARIABLES);
				default:
					// With one argument or with two: a function may
					// have a form for each.
					return pick(FUNCTIONS) + "(" + expression(depth + 1) +
							(random.nextInt(4) == 0 ? "" : ", " +
									expression(depth + 1)) + ")";
			}
		}

		// The type of a declaration: at times written with a macro.
		private String type() {
			return level == 2 && defined.contains("T") &&
					random.nextInt(3) == 0 ? "T" : "int";
		}

		// Something outside of functions.
		private void top(boolean active, int depth) {
			switch (random.nextInt(depth > 1 ? 6 : 8)) {
				case 0:
				case 1:
					if (random.nextInt(5) == 0) {
						// An array: of the size a macro has, at times.
						line("int " + fresh(VARIABLES, active) + "[" +
								(random.nextBoolean() ? pick(MACROS)
										: String.valueOf(1 + random.nextInt(
										8))) + "];");
						break;
					}
					line(type() + " " + fresh(VARIABLES, active) +
							(random.nextBoolean() ? ""
									: " = " + expression(0)) + ";");
					break;
				case 2:
				case 3:
				case 4:
					if (typed && random.nextInt(4) == 0) {
						if (random.nextBoolean()) {
							line("float u = 1.5;");
							break;
						}
						// A form of "p" for an int or for a float.
						String of = random.nextBoolean() ? "int" : "float";
						String head = "int p(" + of + " " +
								fresh(VARIABLES, active) + ")";
						if (!functions.add("p " + of)) {
							line(head + ";");
							break;
						}
						line(head + " {");
						for (int i = 0, n = 1 + random.nextInt(3); i < n;
								++i) {
							statement(active, 1, true);
						}
						line("}");
						break;
					}
					String function = fresh(FUNCTIONS, active);
					// A form with one parameter or with two; a head
					// without a body any number of times, before its
					// body or behind it or without one.
					boolean one = random.nextInt(4) == 0;
					boolean prototype = random.nextInt(4) == 0;
					if (!prototype && !functions.add(function +
							(one ? " 1" : " 2"))) {
						function = "k" + functions.size();
						functions.add(function + (one ? " 1" : " 2"));
					}
					String head = "int " + function + "(int " +
							fresh(VARIABLES, active) + (one ? "" : ", int " +
							fresh(VARIABLES, active)) + ")";
					if (prototype) {
						line(head + ";");
						break;
					}
					line(head + " {");
					for (int i = 0, n = 1 + random.nextInt(5); i < n; ++i) {
						statement(active, 1, true);
					}
					line("}");
					break;
				case 5:
					directive(active);
					break;
				default:
					conditional(active, depth, true);
					break;
			}
		}

		// A statement of a body; directives stand between whole
		// statements only.
		private void statement(boolean active, int depth, boolean block) {
			String indent = "\t".repeat(depth);
			if (level == 2 && active && random.nextInt(12) == 0) {
				// What reads as a declaration and is none, or is one
				// of another type: the word before the name is a macro.
				for (String macro : MACROS) {
					if (defined.contains(macro)) {
						line(indent + macro + " " + pick(VARIABLES) + ";");
						return;
					}
				}
			}
			if (members.contains("t.m") && random.nextInt(12) == 0) {
				// A local of the other struct under the name of the
				// global: its members are that struct's.
				line(indent + "U s;");
				return;
			}
			switch (random.nextInt(depth > 3 || !block ? 5 : 11)) {
				case 0:
				case 1:
					line(indent + type() + " " + fresh(VARIABLES, active) +
							" = " + expression(0) + ";");
					break;
				case 2:
				case 3:
				case 4:
					line(indent + pick(VARIABLES) + " = " + expression(0) +
							";");
					break;
				case 5:
					line(indent + "{");
					for (int i = 0, n = 1 + random.nextInt(3); i < n; ++i) {
						statement(active, depth + 1, true);
					}
					line(indent + "}");
					break;
				case 6:
					line(indent + "if (" + expression(1) + ")");
					substatement(active, depth + 1);
					if (random.nextBoolean()) {
						line(indent + "else");
						substatement(active, depth + 1);
					}
					break;
				case 7:
					line(indent + "for (int " + fresh(VARIABLES, active) +
							" = " + expression(1) + "; " + expression(1) +
							"; " + pick(VARIABLES) + " = " + expression(1) +
							")");
					substatement(active, depth + 1);
					break;
				case 8:
					line(indent + "while (" + expression(1) + ")");
					substatement(active, depth + 1);
					break;
				case 9:
					directive(active);
					break;
				default:
					conditional(active, depth, false);
					break;
			}
		}

		private void substatement(boolean active, int depth) {
			if (random.nextInt(3) == 0) {
				line("\t".repeat(depth - 1) + "{");
				for (int i = 0, n = 1 + random.nextInt(2); i < n; ++i) {
					statement(active, depth, true);
				}
				line("\t".repeat(depth - 1) + "}");
			} else {
				statement(active, depth, false);
			}
		}

		// A "#define" or an "#undef"; it takes effect if it is
		// compiled. A macro is defined again only after an "#undef".
		private void directive(boolean active) {
			int kind = random.nextInt(level == 2 ? 8 : 4);
			if (kind == 0) {
				String name = pick(MACROS);
				line("#undef " + name);
				if (active) {
					defined.remove(name);
				}
				return;
			} else if (kind >= 6) {
				// A macro with a parameter: an expression of it, or
				// the parameter alone, which makes its argument a
				// call where that is a macro too.
				String name = pick(CALLED);
				if (active && defined.contains(name)) {
					line("#undef " + name);
				}
				line("#define " + name + (random.nextBoolean()
						? "(p) (p + " + pick(VARIABLES) + ")" : "(p) p"));
				if (active) {
					defined.add(name);
					called.add(name);
				}
				return;
			} else if (kind == 5) {
				// A type under another name.
				if (active && defined.contains("T")) {
					line("#undef T");
				}
				line("#define T int");
				if (active) {
					defined.add("T");
				}
				return;
			}
			String name = pick(MACROS);
			String text;
			switch (level == 2 ? random.nextInt(5) : random.nextInt(2)) {
				case 0:
					text = String.valueOf(random.nextInt(9));
					break;
				case 1:
					text = "(-" + random.nextInt(9) + ")";
					break;
				case 2:
					text = random.nextBoolean() ? pick(VARIABLES)
							: pick(MACROS);
					break;
				case 3:
					text = "";
					break;
				default:
					text = "(" + pick(VARIABLES) + " + " +
							random.nextInt(9) + ")";
					break;
			}
			if (active && defined.contains(name)) {
				line("#undef " + name);
			}
			line("#define " + name + (text.isEmpty() ? "" : " " + text));
			if (active) {
				defined.add(name);
			}
		}

		// A conditional around whole declarations or statements: one
		// the text decides, or, from level 1 on, one it does not.
		private void conditional(boolean active, int depth, boolean top) {
			String name = random.nextInt(4) == 0 ? "Z" : pick(MACROS);
			Boolean on;
			switch (random.nextInt(level == 0 ? 6 : 9)) {
				case 0:
					line("#ifdef " + name);
					on = defined.contains(name);
					break;
				case 1:
					line("#ifndef " + name);
					on = !defined.contains(name);
					break;
				case 2:
					line("#if 0");
					on = false;
					break;
				case 3:
					line("#if 1");
					on = true;
					break;
				case 4:
					line("#if defined(" + name + ") && 1");
					on = defined.contains(name);
					break;
				case 5:
					line("#if !defined " + name + " || 0");
					on = !defined.contains(name);
					break;
				case 6:
					line("#ifdef " + pick(DRIVER));
					on = null;
					break;
				case 7:
					line("#if defined(" + pick(DRIVER) + ") || 0");
					on = null;
					break;
				default:
					line("#ifndef " + pick(DRIVER));
					on = null;
					break;
			}
			// What an arm that may be compiled defines is not known
			// behind it: such arms define nothing here but code.
			branch(active && !Boolean.FALSE.equals(on), on == null, depth,
					top);
			boolean taken = Boolean.TRUE.equals(on);
			if (random.nextBoolean()) {
				line("#else");
				branch(active && !taken, on == null, depth, top);
			}
			line("#endif");
		}

		private void branch(boolean active, boolean undecided, int depth,
				boolean top) {
			for (int i = 0, n = random.nextInt(3); i < n; ++i) {
				if (top && !undecided) {
					top(active, depth + 1);
				} else if (top) {
					line("int " + fresh(VARIABLES, active) + " = " +
							expression(1) + ";");
				} else {
					statement(active, depth + 1, !undecided);
				}
			}
		}
	}

	// ---- The reference --------------------------------------------------

	private static final Set<String> KEYWORDS = Set.of("int", "float", "if",
			"else", "for", "while", "struct", "uniform");

	// A token with the place it is written at in a file, as
	// "file:offset", or null for one the build wrote.
	private static final class Token {
		final String text;
		final String at;
		final Set<String> hidden;

		Token(String text, String at, Set<String> hidden) {
			this.text = text;
			this.at = at;
			this.hidden = hidden;
		}

		boolean isName() {
			return Character.isLetter(text.charAt(0)) &&
					!KEYWORDS.contains(text);
		}
	}

	private static final class Macro {
		final String at;
		final List<String> parameters;
		final List<Token> text;

		Macro(String at, List<String> parameters, List<Token> text) {
			this.at = at;
			this.parameters = parameters;
			this.text = text;
		}
	}

	/**
	 * The programs of a folder, each as the driver gets it, and each
	 * outcome of what its compiler may define.
	 */
	private static final class World {
		final List<Outcome> outcomes = new ArrayList<>();
		// Whether a macro that is more than a number is defined
		// anywhere.
		boolean macros;

		World(Map<String, String> files) {
			// The programs: the file nothing includes, and the library
			// if another file reads it as a pass.
			read(files, UsesHarness.MAIN);
			if (files.containsKey(Generator.READER)) {
				read(files, Generator.PASS);
			}
		}

		private void read(Map<String, String> files, String root) {
			ProjectFiles store = UsesHarness.files(files);
			PassSource source = ShaderLanguage.of(root).pass(
					store, ProjectPath.parse(root).value(),
					ProjectLimits.DEFAULT, UsesHarness.BUILTINS);
			CompiledSource compiled = CompiledSource.of(source.lines(),
					source.head(), source.sourceMap());
			List<String> lines = SourceText.lines(compiled.text());
			// Where each line of the driver's text is from.
			String[] at = new String[lines.size()];
			int[] start = new int[lines.size()];
			Map<String, int[]> starts = new HashMap<>();
			for (int n = 0; n < at.length; ++n) {
				SourceLocation from = compiled.sourceMap().locate(n + 1);
				if (from == null || from.isGenerated()) {
					continue;
				}
				at[n] = from.file().toString();
				int[] of = starts.get(at[n]);
				if (of == null) {
					String text = files.get(at[n]);
					List<Integer> found = new ArrayList<>();
					found.add(0);
					for (int i = text.indexOf('\n'); i >= 0;
							i = text.indexOf('\n', i + 1)) {
						found.add(i + 1);
					}
					of = new int[found.size()];
					for (int i = 0; i < of.length; ++i) {
						of[i] = found.get(i);
					}
					starts.put(at[n], of);
				}
				start[n] = of[from.line() - 1];
			}
			for (int outcome = 0; outcome < 4; ++outcome) {
				Set<String> defined = new TreeSet<>();
				if ((outcome & 1) != 0) {
					defined.add("GL_A");
				}
				if ((outcome & 2) != 0) {
					defined.add("GL_B");
				}
				Outcome one = new Outcome(lines, at, start, defined, root);
				macros |= one.macros;
				if (outcome == 0 || one.asked) {
					outcomes.add(one);
				} else if (outcome == 3) {
					break;
				}
				if (!one.asked) {
					// Nothing asks what the compiler defines: one
					// outcome is all there is.
					break;
				}
			}
		}

		// Every declaration that is written in a file and compiled in
		// an outcome, by its place.
		Map<String, Kind> declarations() {
			Map<String, Kind> all = new LinkedHashMap<>();
			for (Outcome outcome : outcomes) {
				all.putAll(outcome.declarations);
			}
			return all;
		}
	}

	/**
	 * One program as a compiler has it: preprocessed, parsed, and
	 * every name bound. It knows nothing of the code it is the
	 * reference for.
	 */
	private static final class Outcome {
		final Set<String> defined;
		// The file its program starts with.
		final String program;
		final Map<String, Kind> declarations = new LinkedHashMap<>();
		// For a declaration, by its place, the places that mean it;
		// the places of every name that is compiled; and for each name
		// of the program in its order what it means, as the number of
		// the name that declares it.
		final Map<String, Set<String>> uses = new HashMap<>();
		// For a place, everything it means where it is compiled: the
		// places of declarations, "" for nothing that has a place.
		final Map<String, Set<String>> bound = new HashMap<>();
		final Set<String> compiled = new HashSet<>();
		final List<Integer> bindings = new ArrayList<>();
		boolean asked;
		boolean macros;
		private final List<Token> tokens = new ArrayList<>();
		private final Map<String, Macro> env = new HashMap<>();
		private final List<Map<String, Integer>> scopes = new ArrayList<>();
		private final List<String> declarers = new ArrayList<>();
		private int at;

		Outcome(List<String> lines, String[] file, int[] start,
				Set<String> defined, String program) {
			this.defined = defined;
			this.program = program;
			for (String name : defined) {
				env.put(name, new Macro(null, null, List.of(new Token("1",
						null, Set.of()))));
			}
			preprocess(lines, file, start);
			scopes.add(new HashMap<>());
			while (at < tokens.size()) {
				top();
			}
		}

		// Whether place means the declaration at key every time it is
		// compiled, and nothing else.
		boolean means(String place, String key) {
			return Set.of(first.getOrDefault(key, key)).equals(
					bound.get(place));
		}

		// The places that mean the declaration at key, which for a
		// function are the other heads of its form and its calls.
		Set<String> usesOf(String key) {
			String head = first.getOrDefault(key, key);
			Set<String> places = new TreeSet<>(uses.getOrDefault(head,
					Set.of()));
			if (!head.equals(key)) {
				places.add(head);
				places.remove(key);
			}
			return places;
		}

		// ---- The preprocessor -------------------------------------------

		private void preprocess(List<String> lines, String[] file,
				int[] start) {
			List<boolean[]> open = new ArrayList<>();
			boolean active = true;
			for (int n = 0; n < lines.size(); ++n) {
				String line = lines.get(n);
				int comment = line.indexOf("//");
				if (comment >= 0) {
					line = line.substring(0, comment);
				}
				List<Token> words = words(line, file[n], start[n]);
				if (!words.isEmpty() && words.get(0).text.equals("#")) {
					String command = words.get(1).text;
					if (command.startsWith("if")) {
						boolean on = active && condition(command, words);
						open.add(new boolean[]{on, on, active});
						active = on;
					} else if (command.equals("else")) {
						boolean[] c = open.get(open.size() - 1);
						active = c[2] && !c[1];
					} else if (command.equals("endif")) {
						active = open.remove(open.size() - 1)[2];
					} else if (!active) {
						// Not executed.
					} else if (command.equals("define")) {
						define(words);
					} else if (command.equals("undef")) {
						test(words.get(2));
						env.remove(words.get(2).text);
					}
				} else if (active) {
					List<Token> expanded = new ArrayList<>();
					expand(words, expanded);
					tokens.addAll(expanded);
				}
			}
		}

		private void define(List<Token> words) {
			Token name = words.get(2);
			List<String> parameters = null;
			int from = 3;
			// Parameters stand in a "(" right behind the name.
			if (words.size() > 3 && words.get(3).text.equals("(") &&
					words.get(3).at != null && name.at != null &&
					offset(words.get(3).at) == offset(name.at) +
							name.text.length()) {
				parameters = new ArrayList<>();
				for (from = 4; !words.get(from).text.equals(")"); ++from) {
					if (!words.get(from).text.equals(",")) {
						parameters.add(words.get(from).text);
					}
				}
				++from;
			}
			List<Token> text = new ArrayList<>(words.subList(from,
					words.size()));
			macros |= parameters != null || text.size() != 1 ||
					!Character.isDigit(text.get(0).text.charAt(0));
			if (text.size() == 3 && text.get(0).text.equals("(") &&
					text.get(1).text.equals("-")) {
				// "(-1)" has more than a number and is one.
				macros = macros && false;
			}
			env.put(name.text, new Macro(name.at, parameters, text));
			if (name.at != null) {
				compiled.add(name.at);
				declarations.put(name.at, Kind.MACRO);
			}
		}

		private static int offset(String at) {
			return Integer.parseInt(at.substring(at.indexOf(':') + 1));
		}

		private boolean condition(String command, List<Token> words) {
			if (!command.equals("if")) {
				test(words.get(2));
				return env.containsKey(words.get(2).text) ==
						command.equals("ifdef");
			}
			// The few forms the programs write.
			String first = words.get(2).text;
			if (first.equals("0") || first.equals("1")) {
				return first.equals("1");
			} else if (first.equals("defined")) {
				// "defined(X) && 1", "defined(X) || 0"
				test(words.get(4));
				return env.containsKey(words.get(4).text);
			}
			// "!defined X || 0"
			test(words.get(4));
			return !env.containsKey(words.get(4).text);
		}

		// A name a directive asks about means the macro of that name.
		private void test(Token name) {
			asked |= name.text.startsWith("GL_");
			Macro macro = env.get(name.text);
			if (name.at != null) {
				compiled.add(name.at);
			}
			if (macro != null && macro.at != null && name.at != null) {
				use(macro.at, name.at);
			}
		}

		// The tokens of a line of code with every macro replaced by
		// its text, in which macros are replaced again, but not one
		// inside its own text.
		private void expand(List<Token> in, List<Token> out) {
			List<Token> rest = new ArrayList<>(in);
			while (!rest.isEmpty()) {
				Token token = rest.remove(0);
				Macro macro = token.isName() &&
						!token.hidden.contains(token.text)
						? env.get(token.text) : null;
				if (macro == null || (macro.parameters != null &&
						(rest.isEmpty() || !rest.get(0).text.equals("(")))) {
					out.add(token);
					continue;
				}
				if (token.at != null) {
					compiled.add(token.at);
					if (macro.at != null) {
						use(macro.at, token.at);
					}
				}
				Set<String> hidden = new HashSet<>(token.hidden);
				hidden.add(token.text);
				Map<String, List<Token>> arguments = new HashMap<>();
				if (macro.parameters != null) {
					// Its arguments, each replaced for itself first.
					rest.remove(0);
					List<Token> argument = new ArrayList<>();
					int index = 0;
					for (int depth = 0; ; ) {
						Token next = rest.remove(0);
						if (next.text.equals(")") && depth == 0) {
							break;
						} else if (next.text.equals(",") && depth == 0) {
							arguments.put(macro.parameters.get(index++),
									expanded(argument));
							argument = new ArrayList<>();
							continue;
						}
						depth += next.text.equals("(") ? 1
								: next.text.equals(")") ? -1 : 0;
						argument.add(next);
					}
					if (index < macro.parameters.size()) {
						arguments.put(macro.parameters.get(index),
								expanded(argument));
					}
				}
				List<Token> replaced = new ArrayList<>();
				for (Token part : macro.text) {
					List<Token> argument = arguments.get(part.text);
					if (argument != null) {
						replaced.addAll(argument);
					} else {
						Set<String> all = new HashSet<>(hidden);
						all.addAll(part.hidden);
						replaced.add(new Token(part.text, part.at, all));
					}
				}
				rest.addAll(0, replaced);
			}
		}

		private List<Token> expanded(List<Token> argument) {
			List<Token> out = new ArrayList<>();
			expand(argument, out);
			return out;
		}

		private static List<Token> words(String line, String file,
				int start) {
			List<Token> words = new ArrayList<>();
			for (int i = 0; i < line.length(); ) {
				char c = line.charAt(i);
				int end = i + 1;
				if (Character.isWhitespace(c)) {
					i = end;
					continue;
				} else if (Character.isLetterOrDigit(c) || c == '_') {
					while (end < line.length() && (Character.isLetterOrDigit(
							line.charAt(end)) || line.charAt(end) == '_')) {
						++end;
					}
					if (Character.isDigit(c) && end + 1 < line.length() &&
							line.charAt(end) == '.' &&
							Character.isDigit(line.charAt(end + 1))) {
						// A number with a point is one token.
						++end;
						while (end < line.length() && Character.isDigit(
								line.charAt(end))) {
							++end;
						}
					}
				}
				words.add(new Token(line.substring(i, end), file == null
						? null : file + ":" + (start + i), Set.of()));
				i = end;
			}
			return words;
		}

		// ---- The parser: declarations, statements, scopes ---------------

		private String peek() {
			return at < tokens.size() ? tokens.get(at).text : "";
		}

		private Token next(String expected) {
			Token token = tokens.get(at++);
			if (expected != null && !token.text.equals(expected)) {
				throw new IllegalStateException("expected " + expected +
						" but found " + token.text + " at token " + at +
						" with " + defined);
			}
			if (token.isName()) {
				// Not bound by an expression: bound here.
				throw new IllegalStateException("a name where " + expected +
						" stands");
			}
			return token;
		}

		private void use(String declaration, String place) {
			Set<String> places = uses.get(declaration);
			if (places == null) {
				places = new TreeSet<>();
				uses.put(declaration, places);
			}
			places.add(place);
			bind(place, declaration);
		}

		private void bind(String place, String declaration) {
			Set<String> all = bound.get(place);
			if (all == null) {
				all = new HashSet<>();
				bound.put(place, all);
			}
			all.add(declaration == null ? "" : declaration);
		}

		// A form of a function: the types of its parameters, the number
		// of the name of its first head, and where that stands.
		private static final class Form {
			final List<String> parameters;
			final int number;
			final String at;

			Form(List<String> parameters, int number, String at) {
				this.parameters = parameters;
				this.number = number;
				this.at = at;
			}
		}

		// The forms of each function that were seen so far.
		private final Map<String, List<Form>> forms = new HashMap<>();
		// For each name that declares something, what it is the type
		// of: "int", "float", the name of a struct, or of a block for
		// its instance; null for anything else.
		private final List<String> types = new ArrayList<>();
		// The structs by their names, and the fields of each struct
		// and block: the number of the name of each.
		private final Map<String, Integer> structs = new HashMap<>();
		private final Map<String, Map<String, Integer>> fields =
				new HashMap<>();
		// For a head of a function that is not the first of its form,
		// where the first stands.
		final Map<String, String> first = new HashMap<>();
		// Which of the names are calls that fit no form.
		final Set<Integer> broken = new HashSet<>();
		final Set<String> brokenAt = new HashSet<>();

		// Whether every name means here what it means in after, as
		// far as a compiler says what it means.
		boolean bindsAs(Outcome after) {
			if (bindings.size() != after.bindings.size()) {
				return false;
			}
			for (int i = 0; i < bindings.size(); ++i) {
				if (!broken.contains(i) && !bindings.get(i).equals(
						after.bindings.get(i))) {
					return false;
				}
			}
			return true;
		}
		// The type of the value read last, for a "." behind it.
		private String lastType;

		// The name being declared: it means itself.
		private Token declare(Kind kind) {
			Token name = tokens.get(at++);
			if (!name.isName()) {
				throw new IllegalStateException("no name: " + name.text +
						" with " + defined);
			}
			int number = declarers.size();
			declarers.add(name.at);
			types.add(null);
			bindings.add(number);
			if (name.at != null) {
				compiled.add(name.at);
				declarations.put(name.at, kind);
			}
			pending = name;
			pendingNumber = number;
			return name;
		}

		private Token pending;
		private int pendingNumber;

		private void seen() {
			scopes.get(scopes.size() - 1).put(pending.text, pendingNumber);
		}

		// The number of the name that declares what a variable of that
		// name is where the parser stands, or null.
		private Integer lookup(String name) {
			for (int i = scopes.size(); i-- > 0; ) {
				Integer number = scopes.get(i).get(name);
				if (number != null) {
					return number;
				}
			}
			return null;
		}

		// A name in an expression means the declaration of the
		// innermost scope that has one.
		private void bind(Token name) {
			if (name.at != null) {
				compiled.add(name.at);
			}
			Integer number = lookup(name.text);
			lastType = number == null ? null : types.get(number);
			bindings.add(number == null ? -1 : number);
			if (name.at != null) {
				String declaration = number == null ? null
						: declarers.get(number);
				if (declaration != null) {
					use(declaration, name.at);
				} else {
					bind(name.at, null);
				}
			}
		}

		// The type before a name that is declared: a word of the
		// language, or the name of a struct, which is a use of it.
		private boolean isType(int i) {
			if (i >= tokens.size()) {
				return false;
			}
			String text = tokens.get(i).text;
			return text.equals("int") || text.equals("float") ||
					(structs.containsKey(text) && i + 1 < tokens.size() &&
							tokens.get(i + 1).isName());
		}

		private String type() {
			Token token = tokens.get(at++);
			Integer struct = structs.get(token.text);
			if (struct != null) {
				bindings.add(struct);
				if (token.at != null) {
					compiled.add(token.at);
					if (declarers.get(struct) != null) {
						use(declarers.get(struct), token.at);
					}
				}
			} else if (!token.text.equals("int") &&
					!token.text.equals("float")) {
				throw new IllegalStateException("no type: " + token.text +
						" with " + defined);
			}
			return token.text;
		}

		private void top() {
			if (peek().equals("struct")) {
				struct();
				return;
			} else if (peek().equals("uniform")) {
				block();
				return;
			}
			String type = type();
			int index = bindings.size();
			Token name = declare(Kind.VARIABLE);
			if (!peek().equals("(")) {
				types.set(pendingNumber, type);
				variable();
				return;
			}
			// A function is seen from its head on; its parameters and
			// its body are one scope. Heads with the same parameters
			// are one function, which the first of them stands for.
			int number = pendingNumber;
			if (name.at != null) {
				declarations.put(name.at, Kind.FUNCTION);
			}
			next("(");
			scopes.add(new HashMap<>());
			List<String> parameters = new ArrayList<>();
			while (!peek().equals(")")) {
				String parameter = type();
				declare(Kind.PARAMETER);
				types.set(pendingNumber, parameter);
				seen();
				parameters.add(parameter);
				if (peek().equals(",")) {
					next(",");
				}
			}
			next(")");
			List<Form> all = forms.get(name.text);
			if (all == null) {
				all = new ArrayList<>();
				forms.put(name.text, all);
			}
			Form form = null;
			for (Form known : all) {
				if (known.parameters.equals(parameters)) {
					form = known;
				}
			}
			if (form == null) {
				form = new Form(parameters, number, name.at);
				all.add(form);
			} else {
				bindings.set(index, form.number);
				if (name.at != null && form.at != null) {
					first.put(name.at, form.at);
				}
			}
			if (name.at != null && form.at != null) {
				if (name.at.equals(form.at)) {
					bind(name.at, form.at);
				} else {
					use(form.at, name.at);
				}
			}
			if (peek().equals(";")) {
				next(";");
			} else {
				next("{");
				while (!peek().equals("}")) {
					statement();
				}
				next("}");
			}
			scopes.remove(scopes.size() - 1);
		}

		// A struct: its name is a type from here on, its fields are
		// its own.
		private void struct() {
			next("struct");
			Token name = declare(Kind.TYPE);
			int number = pendingNumber;
			structs.put(name.text, number);
			fields.put(name.text, fields(Kind.FIELD));
			next(";");
		}

		// The fields between braces, by their names.
		private Map<String, Integer> fields(Kind kind) {
			Map<String, Integer> of = new HashMap<>();
			next("{");
			while (!peek().equals("}")) {
				String type = type();
				Token field = declare(kind);
				types.set(pendingNumber, type);
				of.put(field.text, pendingNumber);
				if (kind != Kind.FIELD) {
					seen();
				}
				next(";");
			}
			next("}");
			return of;
		}

		// A block: with a name behind it, its fields are that
		// instance's; without, each is a variable.
		private void block() {
			next("uniform");
			Token block = tokens.get(at++);
			bindings.add(-1);
			int open = at;
			int close = open;
			while (!tokens.get(close).text.equals("}")) {
				++close;
			}
			boolean named = tokens.get(close + 1).isName();
			Map<String, Integer> of = fields(named ? Kind.FIELD
					: Kind.VARIABLE);
			if (named) {
				declare(Kind.VARIABLE);
				types.set(pendingNumber, "block " + block.text);
				fields.put("block " + block.text, of);
				seen();
			}
			next(";");
		}

		// What is declared is seen behind its initializer.
		private void variable() {
			Token name = pending;
			int number = pendingNumber;
			if (peek().equals("[")) {
				// Its size, in which it is not seen yet.
				next("[");
				while (!peek().equals("]")) {
					Token token = tokens.get(at++);
					if (token.isName()) {
						bind(token);
					}
				}
				next("]");
			}
			if (peek().equals("=")) {
				next("=");
				expression();
			}
			next(";");
			scopes.get(scopes.size() - 1).put(name.text, number);
		}

		private void statement() {
			if (isType(at)) {
				String type = type();
				declare(Kind.VARIABLE);
				types.set(pendingNumber, type);
				variable();
				return;
			}
			switch (peek()) {
				case "{":
					next("{");
					scopes.add(new HashMap<>());
					while (!peek().equals("}")) {
						statement();
					}
					next("}");
					scopes.remove(scopes.size() - 1);
					break;
				case "if":
					next("if");
					next("(");
					expression();
					next(")");
					substatement();
					if (peek().equals("else")) {
						next("else");
						substatement();
					}
					break;
				case "for":
					// What its head declares is seen in all of it.
					next("for");
					next("(");
					scopes.add(new HashMap<>());
					next("int");
					declare(Kind.VARIABLE);
					types.set(pendingNumber, "int");
					variable();
					expression();
					next(";");
					expression();
					next(")");
					substatement();
					scopes.remove(scopes.size() - 1);
					break;
				case "while":
					next("while");
					next("(");
					expression();
					next(")");
					substatement();
					break;
				default:
					expression();
					next(";");
					break;
			}
		}

		// The statement of an "if", a "for" or a "while" is a scope.
		private void substatement() {
			scopes.add(new HashMap<>());
			statement();
			scopes.remove(scopes.size() - 1);
		}

		// Up to what ends it: a ";", or a ")" it did not open.
		private void expression() {
			lastType = null;
			for (int depth = 0; at < tokens.size(); ++at) {
				Token token = tokens.get(at);
				if (token.text.equals(".") && at + 1 < tokens.size() &&
						tokens.get(at + 1).isName()) {
					member(tokens.get(++at));
					continue;
				} else if (token.isName()) {
					if (at + 1 < tokens.size() && tokens.get(at + 1).text
							.equals("(") && forms.containsKey(token.text)) {
						call(token);
					} else {
						bind(token);
					}
					continue;
				}
				lastType = null;
				if (token.text.equals("(")) {
					++depth;
				} else if (token.text.equals(")")) {
					if (--depth < 0) {
						return;
					}
				} else if (token.text.equals(";")) {
					return;
				} else if (KEYWORDS.contains(token.text)) {
					throw new IllegalStateException("a word of the " +
							"language in an expression: " + token.text +
							" with " + defined);
				}
			}
		}

		// A name behind a ".": the field of that name of what the
		// value before it is.
		private void member(Token name) {
			Map<String, Integer> of = lastType == null ? null
					: fields.get(lastType);
			Integer number = of == null ? null : of.get(name.text);
			lastType = number == null ? null : types.get(number);
			bindings.add(number == null ? -1 : number);
			if (name.at != null) {
				compiled.add(name.at);
				if (number != null && declarers.get(number) != null) {
					use(declarers.get(number), name.at);
				} else {
					bind(name.at, null);
				}
			}
		}

		// A call of a function that was declared: the form its
		// arguments choose. One form alone is meant whatever is given.
		private void call(Token name) {
			lastType = null;
			int close = at + 2;
			List<int[]> arguments = new ArrayList<>();
			int from = close;
			for (int depth = 0; close < tokens.size(); ++close) {
				String text = tokens.get(close).text;
				if (text.equals("(")) {
					++depth;
				} else if (text.equals(")") && --depth < 0) {
					break;
				} else if (text.equals(",") && depth == 0) {
					arguments.add(new int[]{from, close});
					from = close + 1;
				}
			}
			if (close > from || !arguments.isEmpty()) {
				arguments.add(new int[]{from, close});
			}
			// The form with as many parameters of just these types. A
			// call that has none is an error to a compiler; here it is
			// what else it can be, the only form there is or the only
			// one with as many parameters, and marked as broken: what
			// such a call means is not compared.
			List<Form> all = forms.get(name.text);
			List<String> given = new ArrayList<>();
			for (int[] argument : arguments) {
				given.add(typeOf(argument[0], argument[1]));
			}
			Form chosen = null;
			List<Form> fits = new ArrayList<>();
			for (Form form : all) {
				if (form.parameters.size() == given.size()) {
					fits.add(form);
					if (form.parameters.equals(given)) {
						chosen = form;
					}
				}
			}
			if (chosen == null) {
				broken.add(bindings.size());
				if (name.at != null) {
					brokenAt.add(name.at);
				}
				chosen = all.size() == 1 ? all.get(0) : fits.size() == 1
						? fits.get(0) : null;
			}
			if (name.at != null) {
				compiled.add(name.at);
			}
			if (chosen == null) {
				bindings.add(-2);
				if (name.at != null) {
					bind(name.at, "?");
				}
				return;
			}
			bindings.add(chosen.number);
			if (name.at != null && chosen.at != null) {
				use(chosen.at, name.at);
			}
		}

		// The type of the tokens from from to to as a value, or null
		// if it has none: every name and number in it is of one type.
		private String typeOf(int from, int to) {
			String type = null;
			for (int i = from; i < to; ++i) {
				Token token = tokens.get(i);
				String one;
				if (Character.isDigit(token.text.charAt(0))) {
					one = token.text.indexOf('.') < 0 ? "int" : "float";
				} else if (!token.isName()) {
					continue;
				} else if (i + 1 < to && tokens.get(i + 1).text.equals("(")) {
					// Every function gives an int.
					one = forms.containsKey(token.text) ? "int" : null;
					int depth = 0;
					for (++i; i < to; ++i) {
						String text = tokens.get(i).text;
						depth += text.equals("(") ? 1 : text.equals(")")
								? -1 : 0;
						if (depth == 0) {
							break;
						}
					}
				} else {
					Integer number = lookup(token.text);
					one = number == null ? null : types.get(number);
					while (one != null && i + 2 < to &&
							tokens.get(i + 1).text.equals(".")) {
						Map<String, Integer> of = fields.get(one);
						Integer field = of == null ? null
								: of.get(tokens.get(i + 2).text);
						one = field == null ? null : types.get(field);
						i += 2;
					}
				}
				if (one == null || (type != null && !type.equals(one))) {
					return null;
				}
				type = one;
			}
			return type;
		}
}
}

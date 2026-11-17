package de.markusfisch.android.shadereditor.project;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import de.markusfisch.android.shadereditor.syntax.GlslKnowledge;
import de.markusfisch.android.shadereditor.syntax.Knowledge.Answer.Bindings;
import de.markusfisch.android.shadereditor.syntax.Knowledge.Use.Why;

/**
 * What a rename is given besides the places: where a new name is
 * taken, the files and revisions that were read, whether a reading
 * after the edit binds every name as before, where a rename may
 * start, and on which conditions a meaning depends.
 */
public class UsesRenameTest {
	private static final String MAIN = UsesHarness.MAIN;

	private static List<String> taken(String text, String name,
			String candidate) {
		ProjectKnowledge.Uses uses = UsesHarness.uses(UsesHarness.one(text),
				MAIN, text.indexOf(name), candidate);
		List<String> taken = new ArrayList<>();
		for (ProjectKnowledge.Place place : uses.taken()) {
			assertEquals(Why.DECLARATION, place.why());
			taken.add(place.file() + ":" + (place.line() + 1) + " " +
					place.text());
		}
		return taken;
	}

	@Test
	public void whereANewNameIsTaken() {
		String text = "float x;\nfloat y;\n#define N 3\n" +
				"void f(float z) {\n\tfloat w = x;\n}\n" +
				"#if 0\nfloat off;\n#endif\n";
		assertEquals(List.of(), taken(text, "x;", "fresh"));
		assertEquals(List.of("main.frag:2 float y;"), taken(text, "x;", "y"));
		// A parameter at one of the places, and a macro anywhere.
		assertEquals(List.of("main.frag:4 void f(float z) {"),
				taken(text, "x;", "z"));
		assertEquals(List.of("main.frag:3 #define N 3"),
				taken(text, "x;", "N"));
		// Not seen at any place: the initializer stands before its
		// local, and what is not compiled declares nothing. What such
		// a name would hide is what the reading after the edit finds.
		assertEquals(List.of(), taken(text, "x;", "w"));
		assertEquals(List.of(), taken(text, "x;", "off"));
	}

	// The text with a new name at every place that is certain, also at
	// the listed ones if asked for.
	private static Map<String, String> renamed(Map<String, String> files,
			ProjectKnowledge.Uses uses, String name, String to,
			boolean listed) {
		Map<String, String> after = new LinkedHashMap<>(files);
		Map<String, TreeSet<Integer>> offsets = new LinkedHashMap<>();
		for (ProjectKnowledge.Place place : uses.places()) {
			if (place.certain() || (listed && place.why() != Why.PASTES)) {
				offsets.computeIfAbsent(place.file().toString(),
						k -> new TreeSet<>()).add(place.offset());
			}
		}
		for (Map.Entry<String, TreeSet<Integer>> of : offsets.entrySet()) {
			StringBuilder text = new StringBuilder(files.get(of.getKey()));
			for (Integer offset : of.getValue().descendingSet()) {
				text.replace(offset, offset + name.length(), to);
			}
			after.put(of.getKey(), text.toString());
		}
		return after;
	}

	// Whether a rename of what is declared at the first name of file
	// binds every name as before, when the programs are read again.
	private static boolean sameAfter(Map<String, String> files, String file,
			String name, String to, boolean listed) {
		int at = files.get(file).indexOf(name);
		ProjectKnowledge.Uses before = UsesHarness.uses(files, file, at, to);
		assertNotNull(before);
		Map<String, String> after = renamed(files, before, name, to, listed);
		ProjectKnowledge.Uses later = ProjectKnowledge.read(
				UsesHarness.files(after)).uses(ProjectPath.parse(file)
				.value(), UsesHarness.REVISION, UsesHarness.declared(
				after.get(file), at), null, UsesHarness.BUILTINS);
		return before.sameBindings(later);
	}

	@Test
	public void aReadingAfterTheEditBindsAsBeforeOrNot() {
		// A name that is free: every name means what it meant.
		String text = "float x;\nvoid f() { x; }\nfloat y;\n" +
				"void g() { x; y; }\n";
		assertTrue(sameAfter(UsesHarness.one(text), MAIN, "x", "fresh",
				false));
		// A name that is taken further down: a use comes to mean that.
		assertFalse(sameAfter(UsesHarness.one(text), MAIN, "x", "y", false));
		// A name that the local behind an initializer has: the
		// initializer stands before its local, so it still means the
		// global, and nothing changed.
		text = "float x;\nvoid f() { float w = x; }\n";
		assertTrue(sameAfter(UsesHarness.one(text), MAIN, "x", "w", false));
		// With a use behind that local it has changed.
		text = "float x;\nvoid f() { float w = x; w = x; }\n";
		assertFalse(sameAfter(UsesHarness.one(text), MAIN, "x", "w", false));
		// A name that is a macro: the uses become that.
		text = "float x;\n#define N 3.\nvoid f() { x; }\n";
		assertFalse(sameAfter(UsesHarness.one(text), MAIN, "x", "N", false));
		// A listed place left alone that comes to mean something else:
		// the text of a macro names a local, which is renamed, and
		// then means the global.
		text = "float x;\n#define INC x++\nvoid f() { float x; INC; }\n";
		Map<String, String> files = UsesHarness.one(text);
		int local = text.lastIndexOf("float x") + 6;
		ProjectKnowledge.Uses before = UsesHarness.uses(files, MAIN, local,
				null);
		assertEquals(List.of("main.frag:21 IN_MACRO (INC)",
				"main.frag:42 DECLARATION"), UsesHarness.told(before));
		String after = text.substring(0, local) + "fresh" +
				text.substring(local + 1);
		assertEquals(Bindings.CHANGED, before.compare(UsesHarness.uses(after,
				local)));
		// With the listed place ticked as well, nothing is seen to
		// have changed; but nothing vouches for what a macro that was
		// not executed writes, so it is not said to be the same.
		after = after.replace("INC x++", "INC fresh++");
		assertEquals(Bindings.UNPROVEN, before.compare(UsesHarness.uses(
				after, after.indexOf("float fresh") + 6)));
		// What the reader takes for a local behind a macro that
		// returns: changing it changes what is returned, and nothing
		// says it is the same.
		text = "#define RET return\nfloat x; float y; " +
				"float f(){ RET x; }";
		after = text.substring(0, text.lastIndexOf("x;")) + "y" +
				text.substring(text.lastIndexOf("x;") + 1);
		assertEquals(Bindings.UNPROVEN, UsesHarness.uses(text,
				text.lastIndexOf("x;")).compare(UsesHarness.uses(after,
				after.lastIndexOf("y;"))));
		// Under a condition that is not decided, each arm declares the
		// name: renaming one and the use behind them leaves the other
		// outcome without it.
		text = "#if GL_A\nint x;\n#else\nint x;\n#endif\n" +
				"void f() { x; }\n";
		after = "#if GL_A\nint x;\n#else\nint z;\n#endif\n" +
				"void f() { z; }\n";
		assertFalse(UsesHarness.uses(text, text.lastIndexOf("int x") + 4)
				.sameBindings(UsesHarness.uses(after, after.indexOf("z;"))));
		// A condition that asks about a macro that was renamed and is
		// not renamed itself: other lines are compiled.
		text = "#define SOFT 1\n#ifdef SOFT\nfloat a;\n#endif\nfloat b;\n";
		before = UsesHarness.uses(text, text.indexOf("SOFT"));
		after = text.replaceFirst("SOFT", "HARD");
		assertFalse(before.sameBindings(UsesHarness.uses(after,
				after.indexOf("HARD"))));
	}

	@Test
	public void theFilesThatWereReadAndWhereARenameMayStart() {
		Map<String, String> files = new LinkedHashMap<>();
		files.put("lib/common.glsl", "float x;\n");
		// A file without a place can still change what places mean.
		files.put("lib/flags.glsl", "#define SOFT\n");
		files.put(MAIN, "#include \"lib/flags.glsl\"\n" +
				"#include \"lib/common.glsl\"\n#ifdef SOFT\nfloat a = x;\n" +
				"#else\nfloat a = -x;\n#endif\n#define HALF (x * 0.5)\n" +
				"float b = HALF + x;\n");
		files.put("notes.md", "x\n");
		files.put("alone.frag", "float x;\n");
		ProjectKnowledge.Uses uses = UsesHarness.uses(files,
				"lib/common.glsl", 6, null);
		assertEquals(List.of("lib/common.glsl:6 DECLARATION",
				"main.frag:75 USE", "main.frag:95 INACTIVE (#else of " +
						"#ifdef SOFT)", "main.frag:119 IN_MACRO (HALF)",
				"main.frag:145 IN_MACRO (HALF)"), UsesHarness.told(uses));
		assertEquals("[main.frag, lib/flags.glsl, lib/common.glsl]",
				uses.read().keySet().toString());
		for (String revision : uses.read().values()) {
			assertEquals(UsesHarness.REVISION, revision);
		}
		for (ProjectKnowledge.Place place : uses.places()) {
			assertEquals(UsesHarness.REVISION, place.revision());
		}
		// A reason has the line of what stands between.
		ProjectKnowledge.Place behind = uses.places().get(4);
		assertEquals("HALF", behind.reasons().get(0).name());
		assertEquals(7, behind.reasons().get(0).line());
		assertEquals(MAIN, behind.reasons().get(0).file().toString());
		// From a place that is certain, from anywhere in its name; not
		// from one that is listed, which says why; not from a word
		// that is no place.
		ProjectPath main = ProjectPath.parse(MAIN).value();
		assertTrue(uses.at(main, 75).certain());
		assertTrue(uses.at(main, 76).certain());
		assertEquals(Why.IN_MACRO, uses.at(main, 145).why());
		assertNull(uses.at(main, 60));
		assertTrue(uses.depends().isEmpty());
	}

	@Test
	public void aFileSomeOtherProgramCompilesIsNotCertain() {
		Map<String, String> files = new LinkedHashMap<>();
		files.put("common.glsl", "float x;\n");
		files.put("helpers.glsl", "float twice() { return x * 2.0; }\n");
		files.put("other.glsl", "float x;\n");
		files.put(MAIN, "#include \"common.glsl\"\n" +
				"#include \"helpers.glsl\"\nfloat a = x;\n");
		files.put("p3.frag", "#include \"other.glsl\"\n" +
				"#include \"helpers.glsl\"\n");
		// helpers.glsl is also compiled in a program that does not
		// have the declaration and was not read: its use is listed.
		assertEquals(List.of("common.glsl:6 DECLARATION",
				"helpers.glsl:23 NOT_INCLUDED", "main.frag:57 USE"),
				UsesHarness.told(UsesHarness.uses(files, "common.glsl", 6,
						null)));
		// Without that program it is certain: every program that
		// compiles the file was read.
		files.remove("p3.frag");
		assertEquals(List.of("common.glsl:6 DECLARATION",
				"helpers.glsl:23 USE", "main.frag:57 USE"),
				UsesHarness.told(UsesHarness.uses(files, "common.glsl", 6,
						null)));
	}

	@Test
	public void onWhichConditionsAMeaningDepends() {
		String text = "float x;\nvoid f() {\n#ifdef GL_OES_thing\n" +
				"\tfloat x = 1.0;\n#endif\n\tx++;\n}\n";
		ProjectKnowledge.Uses uses = UsesHarness.uses(text, 6);
		assertEquals(Why.CONDITIONAL, uses.places().get(2).why());
		assertEquals(1, uses.depends().size());
		assertEquals("#ifdef GL_OES_thing", uses.depends().get(0).name());
		assertEquals(2, uses.depends().get(0).line());
		// Also where the place is listed for another reason.
		text = "float x;\n#define M (1.0 + 2.0)\nvoid f() {\n\tM;\n" +
				"#ifdef GL_OES_thing\n\tfloat x = 1.0;\n#endif\n\tx++;\n}\n";
		uses = UsesHarness.uses(text, 6);
		assertEquals(Why.IN_MACRO, uses.places().get(2).why());
		assertEquals(1, uses.depends().size());
	}

	// The second review's rows where the editor's reader and the uses
	// read a directive each their own way: both ask one preprocessor
	// now. Where uses says certain, Look up names the same
	// declaration.
	@Test
	public void lookUpAndUsesAgree() {
		String[][] rows = {
				// A local in an arm that is not compiled: the global.
				{"float x;\nvoid f() {\n#ifdef MISSING\n\tfloat x = 1.;\n" +
						"#endif\n\tx++;\n}\n", "x;", "x++", "x;"},
				// A macro that was undefined: the variable.
				{"#define y 2.\n#undef y\nfloat y;\nvoid f() { y++; }\n",
						"y;", "y++", "y;"},
				// A macro defined behind a variable: the macro.
				{"float x;\n#define x 1.0\nvoid f() { float y = x; }\n",
						"x 1.0", "x; }", "x 1.0"},
		};
		for (String[] row : rows) {
			String text = row[0];
			int declared = text.indexOf(row[1]);
			int place = text.indexOf(row[2]);
			ProjectKnowledge.Uses uses = UsesHarness.uses(text, declared);
			assertTrue(text, uses.at(ProjectPath.parse(MAIN).value(), place)
					.certain());
			String name = row[2].substring(0, 1);
			assertEquals(text, text.indexOf(row[3]), GlslKnowledge.of(text)
					.at(place, name).get(0).start());
		}
	}

	@Test
	public void aFolderThatIsNotAllKnownHasNoAnswer() {
		ProjectFiles files = UsesHarness.files(UsesHarness.one(
				"float x;\n"));
		assertNotNull(ProjectKnowledge.read(files));
		// A folder that cannot be listed, and a file that cannot be
		// read.
		assertNull(ProjectKnowledge.read(new ProjectFiles() {
			@Override
			public Read read(ProjectPath path) {
				return files.read(path);
			}

			@Override
			public Listing list(ProjectPath folder) {
				return new Failed("permission was lost");
			}
		}));
		assertNull(ProjectKnowledge.read(new ProjectFiles() {
			@Override
			public Read read(ProjectPath path) {
				return new Failed("permission was lost");
			}

			@Override
			public Listing list(ProjectPath folder) {
				return files.list(folder);
			}
		}));
		// A knowledge of the files of a build is none of a folder.
		Map<ProjectPath, byte[]> some = new LinkedHashMap<>();
		some.put(ProjectPath.parse(MAIN).value(), "float x;\n".getBytes());
		assertNull(ProjectKnowledge.of(some, ProjectKnowledge.NONE).uses(
				ProjectPath.parse(MAIN).value(), UsesHarness.REVISION,
				GlslKnowledge.of("float x;\n").symbols().get(0), null,
				UsesHarness.BUILTINS));
	}
}

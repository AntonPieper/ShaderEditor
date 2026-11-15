package de.markusfisch.android.shadereditor.project;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.markusfisch.android.shadereditor.syntax.Knowledge.Symbol;

public class ProjectKnowledgeTest {
	private static final String NOISE =
			"// A number in 0..1 for each p.\n" +
					"float hash(vec2 p) { return fract(p.x); }\n" +
					"#define OCTAVES 4\n";
	private static final String SHAPES =
			"#include \"noise.glsl\"\n" +
					"float disc(vec2 p) { return hash(p); }\n" +
					"float disc(vec2 p, float r) { return time; }\n";
	private static final String MAIN =
			"#include \"lib/shapes.glsl\"\n" +
					"#pragma se input \"blur.frag\"\n" +
					"uniform sampler2D blur;\n" +
					"uniform float time; // seconds since load\n" +
					"void main() { disc(vec2(0.0)); }\n";
	private static final String BLUR =
			"#include \"lib/noise.glsl\"\n" +
					"float disc(vec3 own) { return 1.0; }\n" +
					"void main() {}\n";

	private static ProjectKnowledge project() {
		Map<ProjectPath, byte[]> files = new LinkedHashMap<>();
		put(files, "main.frag", MAIN);
		put(files, "lib/shapes.glsl", SHAPES);
		put(files, "lib/noise.glsl", NOISE);
		put(files, "blur.frag", BLUR);
		put(files, "notes.md", "float notGlsl;\n");
		return ProjectKnowledge.of(files, ProjectKnowledge.NONE);
	}

	@Test
	public void aFileSeesWhatItsIncludesDeclare() {
		ProjectKnowledge project = project();
		// Directly and through other includes, each name once, sorted.
		assertEquals(List.of("OCTAVES", "disc", "hash"),
				project.names(path("main.frag")));
		assertEquals(List.of("OCTAVES", "hash"),
				project.names(path("lib/shapes.glsl")));
		assertEquals(List.of(), project.names(path("lib/noise.glsl")));
		// An input is another program: nothing of it is seen.
		assertEquals(List.of("OCTAVES", "hash"),
				project.names(path("blur.frag")));
		assertEquals(List.of(), project.names(path("absent.frag")));
		// The same list every time, so a keypress makes none.
		assertSame(project.names(path("main.frag")),
				project.names(path("main.frag")));
	}

	@Test
	public void aWordIsFoundWithItsFileItsTextAndItsComment() {
		ProjectKnowledge project = project();
		List<Symbol> hash = project.find(path("main.frag"), "hash");
		assertEquals(1, hash.size());
		assertEquals("lib/noise.glsl", hash.get(0).file());
		assertEquals("float hash(vec2 p)", hash.get(0).detail());
		assertEquals("A number in 0..1 for each p.",
				hash.get(0).documentation());
		assertEquals(NOISE.indexOf("hash"), hash.get(0).start());
		// A function with every form.
		assertEquals(List.of("float disc(vec2 p)",
				"float disc(vec2 p, float r)"), details(project.find(
				path("main.frag"), "disc")));
		assertEquals(List.of("#define OCTAVES 4"), details(project.find(
				path("main.frag"), "OCTAVES")));
		assertEquals(List.of(), project.find(path("main.frag"), "main"));
		assertEquals(List.of(), project.find(path("main.frag"), "own"));
	}

	// A library that uses what its includer declares.
	@Test
	public void anIncludedFileFindsWhatAFileThatIncludesItSees() {
		ProjectKnowledge project = project();
		List<Symbol> time = project.find(path("lib/shapes.glsl"), "time");
		assertEquals("main.frag", time.get(0).file());
		assertEquals("uniform float time", time.get(0).detail());
		assertEquals("seconds since load", time.get(0).documentation());
		// What its own includes declare comes first.
		assertEquals("lib/noise.glsl", project.find(
				path("lib/shapes.glsl"), "hash").get(0).file());
		// Completion offers only what is certain: the includes.
		assertEquals(List.of("OCTAVES", "hash"),
				project.names(path("lib/shapes.glsl")));
	}

	// The edges in both directions: where a declaration reaches.
	@Test
	public void theFilesADeclarationReaches() {
		ProjectKnowledge project = project();
		assertEquals(Set.of(path("lib/shapes.glsl"), path("main.frag"),
				path("blur.frag")), project.includers(path("lib/noise.glsl")));
		assertEquals(Set.of(path("main.frag")),
				project.includers(path("lib/shapes.glsl")));
		assertEquals(Set.of(), project.includers(path("main.frag")));
		assertEquals(new ArrayList<>(List.of(path("lib/shapes.glsl"),
				path("lib/noise.glsl"))), new ArrayList<>(
				project.includes(path("main.frag"))));
	}

	@Test
	public void filesThatIncludeEachOtherEnd() {
		Map<ProjectPath, byte[]> files = new LinkedHashMap<>();
		put(files, "a.glsl", "#include \"b.glsl\"\nfloat a;\n");
		put(files, "b.glsl", "#include \"a.glsl\"\n#include \"b.glsl\"\n" +
				"#include \"../outside.glsl\"\nfloat b;\n");
		ProjectKnowledge project = ProjectKnowledge.of(files,
				ProjectKnowledge.NONE);
		assertEquals(List.of("b"), project.names(path("a.glsl")));
		assertEquals(List.of("a"), project.names(path("b.glsl")));
		assertEquals(Set.of(path("b.glsl")),
				project.includers(path("a.glsl")));
	}

	// Only a file that changed is read again.
	@Test
	public void keepsWhatItKnowsOfAFileThatDidNotChange() {
		ProjectKnowledge before = project();
		Map<ProjectPath, byte[]> files = new LinkedHashMap<>();
		put(files, "main.frag", MAIN);
		put(files, "lib/shapes.glsl", SHAPES + "float added;\n");
		put(files, "lib/noise.glsl", NOISE);
		ProjectKnowledge after = ProjectKnowledge.of(files, before);
		assertSame(before.of(path("main.frag")), after.of(path("main.frag")));
		assertSame(before.of(path("lib/noise.glsl")),
				after.of(path("lib/noise.glsl")));
		assertNotSame(before.of(path("lib/shapes.glsl")),
				after.of(path("lib/shapes.glsl")));
		assertEquals(List.of("OCTAVES", "added", "disc", "hash"),
				after.names(path("main.frag")));
		assertNull(after.of(path("blur.frag")));
		assertSame(ProjectKnowledge.NONE, ProjectKnowledge.of(
				new LinkedHashMap<>(), before));
	}

	// A shader of one file is not read: nobody asks about it.
	@Test
	public void aFileThatStandsAloneIsNotRead() {
		Map<ProjectPath, byte[]> files = new LinkedHashMap<>();
		put(files, "alone.frag", "float alone;\nvoid main() {}\n");
		put(files, "lib.glsl", "float shared;\n");
		ProjectKnowledge before = ProjectKnowledge.of(files,
				ProjectKnowledge.NONE);
		assertNull(before.of(path("alone.frag")));
		assertNull(before.of(path("lib.glsl")));
		assertEquals(List.of(), before.names(path("alone.frag")));

		// Once a file includes it, it is read, changed or not.
		put(files, "alone.frag", "#include \"lib.glsl\"\nvoid main() {}\n");
		ProjectKnowledge after = ProjectKnowledge.of(files, before);
		assertEquals(List.of("shared"), after.names(path("alone.frag")));
		assertEquals("lib.glsl", after.find(path("alone.frag"), "shared")
				.get(0).file());
	}

	private static List<String> details(List<Symbol> symbols) {
		List<String> details = new ArrayList<>();
		for (Symbol symbol : symbols) {
			details.add(symbol.detail());
		}
		return details;
	}

	private static void put(Map<ProjectPath, byte[]> files, String path,
			String text) {
		files.put(path(path), text.getBytes(StandardCharsets.UTF_8));
	}

	private static ProjectPath path(String path) {
		return ProjectPath.parse(path).value();
	}
}

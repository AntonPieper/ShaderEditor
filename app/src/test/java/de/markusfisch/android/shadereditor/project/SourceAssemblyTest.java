package de.markusfisch.android.shadereditor.project;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

public class SourceAssemblyTest {
	private static final ProjectPath MAIN = path("main.glsl");

	@Test
	public void expandsNestedAndRepeatedIncludesWithGuards() {
		MemoryProjectFiles files = new MemoryProjectFiles()
				.put("main.glsl", lines(
						"#version 300 es",
						"#include \"lib/a.glsl\"",
						"#include \"lib/a.glsl\"",
						"void main() {}"))
				.put("lib/a.glsl", lines(
						"#ifndef A",
						"#define A",
						"#include \"b.glsl\"",
						"#endif"))
				.put("lib/b.glsl", "float b;\n");
		SourceAssembly assembly = assemble(files);
		assertTrue(assembly.diagnostics().isEmpty());
		String guarded = "#ifndef A\n#define A\nfloat b;\n#endif\n";
		assertEquals("#version 300 es\n" + guarded + guarded +
				"void main() {}\n", assembly.text());
		assertLocation(assembly, 4, "lib/b.glsl", 1);
		assertEquals(List.of("main.glsl:2", "lib/a.glsl:3"), chain(assembly, 4));
		assertLocation(assembly, 8, "lib/b.glsl", 1);
		assertEquals(List.of("main.glsl:3", "lib/a.glsl:3"), chain(assembly, 8));
		assertLocation(assembly, 10, "main.glsl", 4);
		assertEquals(3, files.reads);
		assertEquals(List.of(
				new SourceAssembly.Dependency(MAIN, "r0"),
				new SourceAssembly.Dependency(path("lib/a.glsl"), "r1"),
				new SourceAssembly.Dependency(path("lib/b.glsl"), "r2")),
				assembly.dependencies());
	}

	@Test
	public void warnsAboutPragmaOnce() {
		SourceAssembly assembly = assemble(new MemoryProjectFiles()
				.put("main.glsl", "#include \"a.glsl\"\n")
				.put("a.glsl", "float a;\n  #  pragma once\n"));
		ProjectDiagnostic warning = single(assembly);
		assertEquals(ProjectDiagnostic.Severity.WARNING, warning.severity());
		assertEquals(path("a.glsl"), warning.file());
		assertEquals(2, warning.line());
		assertTrue(warning.message().contains("include guard"));
		assertFalse(assembly.hasErrors());
		assertEquals("float a;\n  #  pragma once\n", assembly.text());
	}

	@Test
	public void requiresMissingIncludeInsideIfZero() {
		SourceAssembly assembly = assemble(new MemoryProjectFiles()
				.put("main.glsl", lines(
						"#if 0",
						"#include \"missing.glsl\"",
						"#endif")));
		assertError(single(assembly), "main.glsl", 2, "missing.glsl");
		assertEquals(List.of(
				new SourceAssembly.Dependency(MAIN, "r0"),
				new SourceAssembly.Dependency(path("missing.glsl"), null)),
				assembly.dependencies());
	}

	@Test
	public void rejectsGuardedCycleWithChain() {
		SourceAssembly assembly = assemble(new MemoryProjectFiles()
				.put("main.glsl", "#include \"a.glsl\"\n")
				.put("a.glsl", lines(
						"#ifndef A",
						"#define A",
						"#include \"b.glsl\"",
						"#endif"))
				.put("b.glsl", "#include \"a.glsl\"\n"));
		ProjectDiagnostic error = single(assembly);
		assertError(error, "b.glsl", 1,
				"main.glsl -> a.glsl -> b.glsl -> a.glsl");
		assertTrue(error.message().contains(
				"included from a.glsl:3, main.glsl:1"));
	}

	@Test
	public void rejectsMacroAndAngleIncludes() {
		SourceAssembly assembly = assemble(new MemoryProjectFiles()
				.put("main.glsl", lines(
						"#define LIB \"a.glsl\"",
						"#include LIB",
						"#include <a.glsl>",
						"#include \"a.glsl\" extra")));
		assertEquals(3, assembly.diagnostics().size());
		assertError(assembly.diagnostics().get(0), "main.glsl", 2, "macros");
		assertError(assembly.diagnostics().get(1), "main.glsl", 3, "Angle");
		assertError(assembly.diagnostics().get(2), "main.glsl", 4, "extra");
	}

	@Test
	public void rejectsBlockCommentOnIncludeLine() {
		MemoryProjectFiles files = new MemoryProjectFiles()
				.put("lib.glsl", "float x;\n");
		assertError(single(assemble(files.put("main.glsl", lines(
						"/* hello",
						"*/ #include \"lib.glsl\"",
						"void main() {}")))),
				"main.glsl", 2, "Block comments");
		assertError(single(assemble(files.put("main.glsl", lines(
						"#include \"lib.glsl\" /* start",
						"end */",
						"void main() {}")))),
				"main.glsl", 1, "Block comments");
		assertTrue(assemble(files.put("main.glsl",
				"#include \"lib.glsl\" // a /* comment\n"))
				.diagnostics().isEmpty());
	}

	@Test
	public void rejectsInvalidIncludePathAtIncludeLine() {
		SourceAssembly assembly = assemble(new MemoryProjectFiles()
				.put("main.glsl", "\n#include \"../out.glsl\"\n"));
		assertError(single(assembly), "main.glsl", 2, "leaves the project");
	}

	@Test
	public void rejectsConditionalCrossingFileBoundary() {
		SourceAssembly assembly = assemble(new MemoryProjectFiles()
				.put("main.glsl", lines(
						"#include \"a.glsl\"",
						"float x;",
						"#endif"))
				.put("a.glsl", "#ifdef X\n"));
		assertEquals(2, assembly.diagnostics().size());
		assertError(assembly.diagnostics().get(0), "main.glsl", 3, "#endif");
		assertError(assembly.diagnostics().get(1), "a.glsl", 1, "not closed");
	}

	@Test
	public void rejectsVersionInInclude() {
		SourceAssembly assembly = assemble(new MemoryProjectFiles()
				.put("main.glsl", "#version 300 es\n#include \"a.glsl\"\n")
				.put("a.glsl", "// lib\n#version 300 es\n"));
		assertError(single(assembly), "a.glsl", 2, "#version");
		assertEquals(1, assembly.versionLine());
	}

	@Test
	public void insertsGeneratedLinesAfterVersion() {
		SourceAssembly assembly = assemble(new MemoryProjectFiles()
				.put("main.glsl", lines(
						"// header",
						"#version 300 es",
						"#extension GL_EXT_shader_texture_lod : enable",
						"void main() {}"))).withGeneratedLines(List.of(
				"#define SHADER_EDITOR 1",
				"#extension GL_OES_EGL_image_external_essl3 : require"));
		assertEquals(lines(
				"// header",
				"#version 300 es",
				"#define SHADER_EDITOR 1",
				"#extension GL_OES_EGL_image_external_essl3 : require",
				"#extension GL_EXT_shader_texture_lod : enable",
				"void main() {}"), assembly.text());
		assertLocation(assembly, 2, "main.glsl", 2);
		assertTrue(assembly.sourceMap().locate(3).isGenerated());
		assertTrue(assembly.sourceMap().locate(4).isGenerated());
		assertLocation(assembly, 5, "main.glsl", 3);
		assertLocation(assembly, 6, "main.glsl", 4);
		assertNull(assembly.sourceMap().locate(7));
	}

	@Test
	public void insertsGeneratedLinesAtTopWithoutVersion() {
		SourceAssembly assembly = assemble(new MemoryProjectFiles()
				.put("main.glsl", "void main() {}\n"))
				.withGeneratedLines(List.of("#define SHADER_EDITOR 1"));
		assertEquals("#define SHADER_EDITOR 1\nvoid main() {}\n",
				assembly.text());
		assertTrue(assembly.sourceMap().locate(1).isGenerated());
		assertLocation(assembly, 2, "main.glsl", 1);
	}

	@Test
	public void locatesErrorInsideNestedInclude() {
		SourceAssembly assembly = assemble(new MemoryProjectFiles()
				.put("main.glsl", "#include \"a/a.glsl\"\nvoid main() {}\n")
				.put("a/a.glsl", "float a;\n#include \"../b/b.glsl\"\n")
				.put("b/b.glsl", "float b;\n#include \"gone.glsl\"\n"));
		assertError(single(assembly), "b/b.glsl", 2,
				"included from a/a.glsl:2, main.glsl:1");
		assertLocation(assembly, 2, "b/b.glsl", 1);
		assertEquals(List.of("main.glsl:1", "a/a.glsl:2"), chain(assembly, 2));
	}

	@Test
	public void boundsIncludeDepth() {
		MemoryProjectFiles files = new MemoryProjectFiles()
				.put("main.glsl", "#include \"1.glsl\"\n")
				.put("1.glsl", "#include \"2.glsl\"\n")
				.put("2.glsl", "#include \"3.glsl\"\n")
				.put("3.glsl", "float x;\n");
		SourceAssembly assembly = SourceAssembly.assemble(files, MAIN,
				new ProjectLimits(2, 1000, 10, 16));
		assertError(single(assembly), "2.glsl", 1, "limit of 2");
	}

	@Test
	public void boundsExpandedSourceBytes() {
		SourceAssembly assembly = SourceAssembly.assemble(
				new MemoryProjectFiles()
						.put("main.glsl", lines(
								"#include \"a.glsl\"",
								"#include \"a.glsl\"",
								"#include \"a.glsl\""))
						.put("a.glsl", "float aaaa;\n"),
				MAIN, new ProjectLimits(4, 30, 10, 16));
		assertError(single(assembly), "a.glsl", 1, "limit of 30 bytes");
		assertTrue(assembly.hasErrors());
		assertEquals(2, assembly.lines().size());
	}

	@Test
	public void boundsFilesRead() {
		SourceAssembly assembly = SourceAssembly.assemble(
				new MemoryProjectFiles()
						.put("main.glsl", "#include \"a.glsl\"\n" +
								"#include \"b.glsl\"\n")
						.put("a.glsl", "float a;\n")
						.put("b.glsl", "float b;\n"),
				MAIN, new ProjectLimits(4, 1000, 2, 16));
		assertError(single(assembly), "main.glsl", 2, "limit of 2 files");
	}

	@Test
	public void normalizesBomAndLineEndingsPerFile() {
		SourceAssembly assembly = assemble(new MemoryProjectFiles()
				.put("main.glsl", "\uFEFF#version 300 es\r\n" +
						"#include \"a.glsl\"\r\nvoid main() {}\r\n")
				.put("a.glsl", "float a;\rfloat b;\r"));
		assertEquals(lines(
				"#version 300 es",
				"float a;",
				"float b;",
				"void main() {}"), assembly.text());
		assertEquals(1, assembly.versionLine());
		assertLocation(assembly, 3, "a.glsl", 2);
		assertLocation(assembly, 4, "main.glsl", 3);
	}

	@Test
	public void resolvesUtf8PathInNfc() {
		String decomposed = "lib/U\u0308bung.glsl";
		SourceAssembly assembly = assemble(new MemoryProjectFiles()
				.put("main.glsl", "#include \"" + decomposed + "\"\n")
				.put("lib/\u00DCbung.glsl", "float \u00FC;\n"));
		assertTrue(assembly.diagnostics().isEmpty());
		assertEquals("float \u00FC;\n", assembly.text());
		assertLocation(assembly, 1, "lib/\u00DCbung.glsl", 1);
	}

	@Test
	public void rejectsIncludeThatIsNotUtf8() {
		SourceAssembly assembly = assemble(new MemoryProjectFiles()
				.put("main.glsl", "#include \"a.glsl\"\n#include \"a.glsl\"\n")
				.put("a.glsl", new byte[]{(byte) 0xFF}));
		assertEquals(2, assembly.diagnostics().size());
		assertError(assembly.diagnostics().get(1), "main.glsl", 2, "UTF-8");
	}

	@Test
	public void reportsMissingEntry() {
		SourceAssembly assembly = assemble(new MemoryProjectFiles());
		assertError(single(assembly), "main.glsl", 0, "not found");
		assertEquals("", assembly.text());
	}

	private static SourceAssembly assemble(MemoryProjectFiles files) {
		return SourceAssembly.assemble(files, MAIN, ProjectLimits.DEFAULT);
	}

	private static ProjectDiagnostic single(SourceAssembly assembly) {
		assertEquals(assembly.diagnostics().toString(), 1,
				assembly.diagnostics().size());
		return assembly.diagnostics().get(0);
	}

	private static void assertError(
			ProjectDiagnostic diagnostic,
			String file,
			int line,
			String messagePart) {
		assertEquals(ProjectDiagnostic.Severity.ERROR, diagnostic.severity());
		assertEquals(path(file), diagnostic.file());
		assertEquals(line, diagnostic.line());
		assertTrue(diagnostic.message(),
				diagnostic.message().contains(messagePart));
	}

	private static void assertLocation(
			SourceAssembly assembly,
			int preparedLine,
			String file,
			int line) {
		SourceLocation location = assembly.sourceMap().locate(preparedLine);
		assertEquals(path(file), location.file());
		assertEquals(line, location.line());
	}

	private static List<String> chain(SourceAssembly assembly, int line) {
		return assembly.sourceMap().locate(line).includeChain().stream()
				.map(SourceLocation::toString)
				.toList();
	}

	private static String lines(String... lines) {
		return String.join("\n", lines) + "\n";
	}

	private static ProjectPath path(String path) {
		return ProjectPath.parse(path).value();
	}
}

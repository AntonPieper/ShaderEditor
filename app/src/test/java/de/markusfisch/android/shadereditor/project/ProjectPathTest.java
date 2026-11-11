package de.markusfisch.android.shadereditor.project;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ProjectPathTest {
	private static final ProjectPath MAIN = path("main.glsl");
	private static final ProjectPath NESTED = path("passes/blur/h.glsl");

	@Test
	public void resolvesRelativeToRootFile() {
		assertEquals("lib/noise.glsl", resolve(MAIN, "lib/noise.glsl"));
	}

	@Test
	public void resolvesRelativeToNestedFile() {
		assertEquals("passes/blur/common.glsl", resolve(NESTED, "common.glsl"));
		assertEquals("passes/shared/k.glsl", resolve(NESTED, "../shared/k.glsl"));
		assertEquals("assets/a.png", resolve(NESTED, "../../assets/a.png"));
	}

	@Test
	public void normalizesDotSegments() {
		assertEquals("a/c.glsl", resolve(MAIN, "./a/b/../c.glsl"));
		assertEquals("a/b.glsl", resolve(MAIN, "a//b.glsl"));
	}

	@Test
	public void aliasesIdentifyOnePath() {
		ProjectPath direct = ProjectPath.resolve(NESTED, "x.glsl").value();
		ProjectPath alias = ProjectPath.resolve(
				MAIN, "passes/./blur/../blur/x.glsl").value();
		assertEquals(direct, alias);
		assertEquals(direct.hashCode(), alias.hashCode());
	}

	@Test
	public void allowsSpacesAndUnicode() {
		assertEquals("my shaders/größe 波.glsl",
				resolve(MAIN, "my shaders/größe 波.glsl"));
		assertEquals(" lead.glsl", resolve(MAIN, " lead.glsl"));
	}

	@Test
	public void composedAndDecomposedSpellingsMatch() {
		ProjectPath composed = path("caf\u00e9.glsl");
		ProjectPath decomposed = path("cafe\u0301.glsl");
		assertEquals(composed, decomposed);
		assertEquals("caf\u00e9.glsl", decomposed.toString());
	}

	@Test
	public void identityIsCaseSensitive() {
		assertNotEquals(path("Main.glsl"), path("main.glsl"));
	}

	@Test
	public void rejectsEscapeAboveRoot() {
		assertError(MAIN, "../x.glsl", "Path leaves the project folder: ../x.glsl");
		assertError(NESTED, "../../../x.glsl",
				"Path leaves the project folder: ../../../x.glsl");
		assertError(MAIN, "a/../../x.glsl",
				"Path leaves the project folder: a/../../x.glsl");
	}

	@Test
	public void rejectsEmptyPath() {
		assertError(MAIN, "", "Path is empty");
	}

	@Test
	public void rejectsPathNamingTheRoot() {
		assertError(MAIN, ".", "Path names the project folder, not a file: .");
		assertError(MAIN, "a/..", "Path names the project folder, not a file: a/..");
	}

	@Test
	public void rejectsQuoteAndBackslash() {
		assertError(MAIN, "a\"b.glsl", "Path contains a double quote: a\"b.glsl");
		assertError(MAIN, "lib\\n.glsl",
				"Path contains a backslash, use / instead: lib\\n.glsl");
	}

	@Test
	public void rejectsControlCharacters() {
		assertError(MAIN, "a\u0000b.glsl", "Path contains a control character: a?b.glsl");
		assertError(MAIN, "a\nb.glsl", "Path contains a control character: a?b.glsl");
		assertError(MAIN, "a\u007fb.glsl", "Path contains a control character: a?b.glsl");
	}

	@Test
	public void rejectsAbsolutePaths() {
		assertError(MAIN, "/main.glsl", "Path is absolute: /main.glsl");
	}

	@Test
	public void rejectsDriveQualifiedPaths() {
		assertError(MAIN, "C:/x.glsl", "Path has a drive letter: C:/x.glsl");
		assertError(MAIN, "c:x.glsl", "Path has a drive letter: c:x.glsl");
	}

	@Test
	public void rejectsUriSchemes() {
		assertError(MAIN, "content://a/b.glsl",
				"Path is a URI, not a project path: content://a/b.glsl");
		assertError(MAIN, "file:x.glsl",
				"Path is a URI, not a project path: file:x.glsl");
	}

	@Test
	public void diagnosticNamesContainingFile() {
		ProjectDiagnostic diagnostic = ProjectPath.resolve(
				NESTED, "../../../x.glsl").diagnostic();
		assertEquals(ProjectDiagnostic.Severity.ERROR, diagnostic.severity());
		assertEquals(NESTED, diagnostic.file());
	}

	@Test
	public void parsesRootRelativePath() {
		ProjectParseResult<ProjectPath> result = ProjectPath.parse("./a/b.glsl");
		assertTrue(result.isSuccess());
		assertEquals("a/b.glsl", result.value().toString());
		assertNull(ProjectPath.parse("../b.glsl").value());
	}

	@Test
	public void classifiesExtensionsCaseInsensitively() {
		assertEquals(ProjectPath.Kind.SHADER, path("a/b.glsl").getKind());
		assertEquals(ProjectPath.Kind.SHADER, path("B.GLSL").getKind());
		assertEquals(ProjectPath.Kind.IMAGE, path("t.png").getKind());
		assertEquals(ProjectPath.Kind.IMAGE, path("t.JPG").getKind());
		assertEquals(ProjectPath.Kind.IMAGE, path("t.Jpeg").getKind());
		assertEquals(ProjectPath.Kind.OTHER, path("t.webp").getKind());
		assertEquals(ProjectPath.Kind.OTHER, path("glsl").getKind());
		assertEquals(ProjectPath.Kind.OTHER, path("a.glsl/readme").getKind());
	}

	@Test
	public void classificationKeepsLookupCaseSensitive() {
		assertFalse(path("B.GLSL").equals(path("B.glsl")));
	}

	private static ProjectPath path(String path) {
		ProjectParseResult<ProjectPath> result = ProjectPath.parse(path);
		assertTrue(path, result.isSuccess());
		return result.value();
	}

	private static String resolve(ProjectPath file, String reference) {
		ProjectParseResult<ProjectPath> result = ProjectPath.resolve(file, reference);
		assertTrue(reference, result.isSuccess());
		return result.value().toString();
	}

	private static void assertError(
			ProjectPath file,
			String reference,
			String message) {
		ProjectParseResult<ProjectPath> result = ProjectPath.resolve(file, reference);
		assertFalse(reference, result.isSuccess());
		assertNull(result.value());
		assertEquals(message, result.diagnostic().message());
	}
}

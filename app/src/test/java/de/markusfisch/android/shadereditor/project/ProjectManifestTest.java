package de.markusfisch.android.shadereditor.project;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ProjectManifestTest {
	@Test
	public void absentManifestIsVersionOneWithMainEntry() {
		assertEquals(1, ProjectManifest.DEFAULT.projectVersion());
		assertEquals("main.glsl", ProjectManifest.DEFAULT.entry().toString());
		assertEquals("shader-project.json", ProjectManifest.FILE.toString());
	}

	@Test
	public void emptyObjectUsesDefaults() {
		assertEquals(ProjectManifest.DEFAULT, parse("{}"));
		assertEquals(ProjectManifest.DEFAULT, parse(" \n{ \r\n}\t"));
	}

	@Test
	public void fullManifest() {
		ProjectManifest manifest = parse(
				"{\n  \"projectVersion\": 1,\n  \"entry\": \"passes/run.glsl\"\n}\n");
		assertEquals(1, manifest.projectVersion());
		assertEquals("passes/run.glsl", manifest.entry().toString());
	}

	@Test
	public void partialManifestOverridesOnlyGivenField() {
		assertEquals(ProjectManifest.DEFAULT, parse("{\"projectVersion\":1}"));
		assertEquals("demo.glsl", parse("{\"entry\":\"demo.glsl\"}").entry().toString());
	}

	@Test
	public void entryIsNormalized() {
		assertEquals("a/caf\u00e9.glsl",
				parse("{\"entry\":\"./a/cafe\\u0301.glsl\"}").entry().toString());
	}

	@Test
	public void acceptsLeadingBom() {
		assertEquals("x.glsl", parse("\uFEFF{\"entry\":\"x.glsl\"}").entry().toString());
	}

	@Test
	public void rejectsNonObject() {
		assertError("", "The manifest must be a JSON object", 1);
		assertError("[]", "The manifest must be a JSON object", 1);
		assertError("\"main.glsl\"", "The manifest must be a JSON object", 1);
	}

	@Test
	public void rejectsMalformedJson() {
		assertError("{\"entry\":\"a.glsl\"", "Expected ',' or '}'", 1);
		assertError("{\"entry\" \"a.glsl\"}", "Expected ':' after key", 1);
		assertError("{\"entry\":\"a.glsl\",}", "Expected a string in double quotes", 1);
		assertError("{} {}", "Unexpected text after the JSON object", 1);
		assertError("{\"entry\":\"a\nb\"}", "Control character in string", 1);
		assertError("{\"entry\":\"a\\x\"}", "Invalid escape sequence in string", 1);
		assertError("{\"entry\":\"a.glsl", "Unterminated string", 1);
		assertError("{'entry':1}", "Expected a string in double quotes", 1);
	}

	@Test
	public void rejectsNestedValues() {
		assertError("{\"entry\":[\"a.glsl\"]}", "Values must be strings or numbers", 1);
		assertError("{\"entry\":{}}", "Values must be strings or numbers", 1);
	}

	@Test
	public void rejectsDuplicateKeys() {
		assertError("{\"entry\":\"a.glsl\",\n\"entry\":\"b.glsl\"}",
				"Duplicate key \"entry\"", 2);
	}

	@Test
	public void rejectsUnknownKeys() {
		assertError("{\n\"name\":\"x\"}", "Unknown key \"name\"", 2);
		assertError("{\"Entry\":\"a.glsl\"}", "Unknown key \"Entry\"", 1);
	}

	@Test
	public void rejectsUnsupportedVersion() {
		assertError("{\"projectVersion\":2}",
				"Unsupported projectVersion 2, this app reads version 1", 1);
		assertError("{\"projectVersion\":1.0}",
				"Unsupported projectVersion 1.0, this app reads version 1", 1);
		assertError("{\"projectVersion\":\"1\"}",
				"Unsupported projectVersion \"1\", this app reads version 1", 1);
		assertError("{\"projectVersion\":null}",
				"Unsupported projectVersion null, this app reads version 1", 1);
	}

	@Test
	public void rejectsNonStringEntry() {
		assertError("{\"entry\":1}", "\"entry\" must be a string, found 1", 1);
		assertError("{\"entry\":true}", "\"entry\" must be a string, found true", 1);
	}

	@Test
	public void rejectsEntryOutsideRoot() {
		assertError("{\n\n\"entry\":\"../main.glsl\"}",
				"Invalid entry: Path leaves the project folder: ../main.glsl", 3);
		assertError("{\"entry\":\"/main.glsl\"}",
				"Invalid entry: Path is absolute: /main.glsl", 1);
		assertError("{\"entry\":\"a\\\\b.glsl\"}",
				"Invalid entry: Path contains a backslash, use / instead: a\\b.glsl", 1);
	}

	@Test
	public void rejectsNonShaderEntry() {
		assertError("{\"entry\":\"noise.png\"}", "Entry must be a .glsl file: noise.png", 1);
		assertError("{\"entry\":\"main\"}", "Entry must be a .glsl file: main", 1);
	}

	@Test
	public void diagnosticNamesManifestFile() {
		ProjectDiagnostic diagnostic = ProjectManifest.parse("{\"x\":1}").diagnostic();
		assertEquals(ProjectDiagnostic.Severity.ERROR, diagnostic.severity());
		assertEquals(ProjectManifest.FILE, diagnostic.file());
	}

	private static ProjectManifest parse(String text) {
		ProjectParseResult<ProjectManifest> result = ProjectManifest.parse(text);
		assertTrue(String.valueOf(result.diagnostic()), result.isSuccess());
		return result.value();
	}

	private static void assertError(String text, String message, int line) {
		ProjectParseResult<ProjectManifest> result = ProjectManifest.parse(text);
		assertFalse(text, result.isSuccess());
		assertEquals(message, result.diagnostic().message());
		assertEquals(line, result.diagnostic().line());
	}
}

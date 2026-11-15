package de.markusfisch.android.shadereditor.editor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.regex.Pattern;

/**
 * Where Add uniform and Insert as texture put a statement, from an
 * independent review's probes: comments count as nothing, "# if" is a
 * conditional, a uniform ends at its semicolon, and a name is a whole
 * word.
 */
public class AddUniformPlaceTest {
	private static final String INSERT =
			"#pragma se input \"noise.png\"\nuniform sampler2D noise;";

	@Test
	public void licenseBeforeVersion() {
		String result = add("/* Copyright 2026 */\n#version 300 es\n" +
				"precision highp float;\nvoid main() {}", INSERT);
		assertTrue(result,
				result.indexOf("#version") < result.indexOf("#pragma"));
	}

	@Test
	public void lineCommentBeforeVersionWorks() {
		String result = add("// licence\n#version 300 es\n" +
				"precision highp float;\nvoid main() {}", INSERT);
		assertTrue(result,
				result.indexOf("#version") < result.indexOf("#pragma"));
	}

	@Test
	public void commentedUniformIsNotAnInsertionPoint() {
		String result = add("/* Example:\nuniform float old;\n*/\n" +
				"void main() {}", INSERT);
		assertFalse(result, result.indexOf("#pragma") >
				result.indexOf("/*") && result.indexOf("#pragma") <
				result.indexOf("*/"));
	}

	@Test
	public void spacedConditionalIsNotAnInsertionPoint() {
		String result = add("precision mediump float;\n# if FLAG\n" +
				"uniform float old;\n# endif\nvoid main() {}", INSERT);
		assertFalse(result, result.indexOf("#pragma") >
				result.indexOf("# if") && result.indexOf("#pragma") <
				result.indexOf("# endif"));
	}

	@Test
	public void multilineUniformStaysIntact() {
		String result = add("precision mediump float;\nuniform vec2\n" +
				" resolution;\nvoid main() {}", INSERT);
		assertTrue(result, result.contains("uniform vec2\n resolution;"));
	}

	@Test
	public void addingTimeDoesNotDeleteTimeScale() {
		String result = add("uniform float timeScale;\nvoid main() {}",
				"uniform float time;");
		assertTrue(result, result.contains("uniform float timeScale;"));
	}

	// The same uniform is still replaced, and one in a comment is not.
	@Test
	public void theSameUniformIsReplacedNotOneInAComment() {
		assertEquals("uniform float time;\nvoid main() {}",
				add("uniform float time;\nvoid main() {}",
						"uniform float time;"));
		String result = add("// uniform float time;\nvoid main() {}",
				"uniform float time;");
		assertTrue(result, result.startsWith("uniform float time;\n" +
				"// uniform float time;"));
	}

	@Test
	public void crlfInputUsesTheDocumentsNormalLFRule() {
		String result = add("uniform float old;\r\nvoid main() {}\r\n",
				"uniform float time;");
		assertEquals("uniform float old;\nuniform float time;\n" +
				"void main() {}\n", result);
	}

	// The release build's own Add uniform (ShaderEditor, with the old
	// editor) took timeScale for time only when a blank line followed
	// it; the shared pattern took it always. Now neither.
	@Test
	public void theReleasePatternTakesNoLongerName() {
		String source = "uniform float timeScale;\nvoid main() {}";
		Pattern master = Pattern.compile(
				"^(uniform[ \t]+float[ \t]+time.*\\n)$", Pattern.MULTILINE);
		assertFalse(master.matcher(source).find());
		assertTrue(master.matcher("uniform float timeScale;\n\n").find());
		assertFalse(AddUniform.sameUniform("uniform float time;")
				.matcher(source).find());
		assertFalse(AddUniform.sameUniform("uniform float time;")
				.matcher("uniform float timeScale;\n\n").find());
		assertTrue(AddUniform.sameUniform("uniform float time;")
				.matcher("uniform float time; // seconds\n").find());
	}

	private static String add(String source, String statement) {
		Document document = new Document(Text.of(source));
		AddUniform.apply(document, statement);
		return document.text().toString();
	}
}

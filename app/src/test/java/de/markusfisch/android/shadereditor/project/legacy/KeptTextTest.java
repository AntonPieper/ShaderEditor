package de.markusfisch.android.shadereditor.project.legacy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Map;
import java.util.Set;

import de.markusfisch.android.shadereditor.project.legacy.LegacyShaderConverter.LibraryTexture;

public class KeptTextTest {
	private static final Map<String, LibraryTexture> LIBRARY = Map.of(
			"noise", new LibraryTexture(false, 8, 8),
			"time", new LibraryTexture(false, 8, 8));

	@Test
	public void asciiTextIsTheConversionItself() {
		String text = "uniform sampler2D noise;\nvoid main() {}\n";
		assertEquals(convert(text).shader(), keep(text).shader());
		assertNull(keep(text).loss());
	}

	@Test
	public void whatTheConversionDidNotRewriteIsTheUsersText() {
		assertEquals("// café 世界\nvoid main() {} // é\n",
				keep("// café 世界\r\nvoid main() {} // é\r\n")
						.shader());
		// No line end at the end stays none.
		assertEquals("// é", keep("// é").shader());
	}

	@Test
	public void aRewrittenLineKeepsWhatIsAroundTheRewrite() {
		// The settings comment goes, the comments beside it stay.
		Kept kept = keep("/* ü */ uniform sampler2D noise; " +
				"///min:l;mag:l;\n// ä\nvoid main() {}\n");
		assertNull(kept.loss());
		assertEquals("/* \u00fc */\n#pragma se input \"textures/noise.png\" " +
				"wrap=repeat\nuniform sampler2D noise;\n// \u00e4\n" +
				"void main() {}\n", kept.shader());
	}

	@Test
	public void aRenamedUseKeepsTheCommentsOfItsLine() {
		// "time" is a builtin, so the sampler is renamed where used.
		Kept kept = keep("uniform sampler2D time;\nvoid main() {\n" +
				"\tgl_FragColor = texture2D(time, vec2(0.)); // été\n" +
				"}\n");
		assertNull(kept.loss());
		assertTrue(kept.shader(), kept.shader().contains(
				"texture2D(time_texture, vec2(0.)); // été\n"));
	}

	@Test
	public void aCharacterBetweenTwoOfCodeCannotBeKept() {
		Kept kept = keep("float a​b;\nvoid main() {}\n");
		assertNull(kept.shader());
		assertEquals("line 1 has the character ​ inside code, where " +
				"the old renderer dropped it and a file reads a space",
				kept.loss());
		// Beside a space, or at the end of a line, both read the same.
		assertNull(keep("float a ​b; \nvoid main() {}\n").loss());
	}

	@Test
	public void aCommentThatIsRewrittenAwayCannotLeaveItsTextAsCode() {
		Kept kept = keep("uniform sampler2D noise; ///min:l;mag:l; ☃\n" +
				"void main() {}\n");
		assertNull(kept.shader());
		assertEquals("line 1 has the character \u2603 in a comment the " +
				"conversion takes away", kept.loss());
	}

	// Two rewrites on a line are two, not one from the first to the
	// last: what lies between them is the user's.
	@Test
	public void aCharacterBetweenTwoRenamedUsesIsKept() {
		Kept kept = keep("uniform sampler2D time;\nvoid main(){\n" +
				" gl_FragColor = texture2D(time, vec2(0.)) \u00e9 + " +
				"texture2D(time, vec2(1.));\n}\n");
		assertNull(kept.loss());
		assertTrue(kept.shader(), kept.shader().contains(
				" gl_FragColor = texture2D(time_texture, vec2(0.)) \u00e9 + " +
						"texture2D(time_texture, vec2(1.));\n"));
	}

	// A refusal says which character, on which line.
	@Test
	public void aCharacterInsideARewriteIsRefusedWithItsLine() {
		Kept kept = keep("void main() {}\nuniform sampler2D noise; " +
				"///min:l;\u00e4mag:l;\n");
		assertNull(kept.shader());
		assertEquals("line 2 has the character \u00e4 in text the " +
				"conversion rewrites", kept.loss());
	}

	// The old renderer dropped the character and the line went on. A
	// file keeps it, and the compiler is handed the line without it.
	@Test
	public void aCharacterBehindAContinuationBackslashIsKept() {
		String text = "#version 300 es\n#define A 1.0 + \\\u00e9\n 2.0\n" +
				"out vec4 c;\nvoid main(){c=vec4(A);}\n";
		Kept kept = keep(text);
		assertNull(kept.loss());
		assertEquals(text, kept.shader());
	}

	// In a comment the compiler is handed a space, so the comment
	// would end where it went on for the old renderer.
	@Test
	public void aCommentThatWentOnBehindADroppedCharacterIsRefused() {
		Kept kept = keep("// note \\\u00e9\nfloat hidden;\nvoid main() {}\n");
		assertNull(kept.shader());
		assertEquals("line 1 ends a comment in a backslash and the " +
				"character \u00e9, which the old renderer dropped, so that " +
				"the comment went on in the next line", kept.loss());
		// Not at the end of the line, it is a comment like any other.
		assertNull(keep("// a \\\u00e9 b\nvoid main() {}\n").loss());
	}

	private record Kept(String shader, String loss) {
	}

	private static Kept keep(String text) {
		KeptText.Kept kept = KeptText.of(text, convert(text));
		return new Kept(kept.shader(), kept.loss());
	}

	private static LegacyShaderConverter.Result convert(String text) {
		return LegacyShaderConverter.convert(text, LIBRARY::get,
				Set.of("time", "resolution", "backbuffer"));
	}
}

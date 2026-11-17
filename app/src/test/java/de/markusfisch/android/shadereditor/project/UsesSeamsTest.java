package de.markusfisch.android.shadereditor.project;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

import org.junit.Test;

import java.util.List;

import de.markusfisch.android.shadereditor.syntax.GlslKnowledge;
import de.markusfisch.android.shadereditor.syntax.GlslLexer;

/**
 * Where an answer could be given by where something stands instead of
 * by what was proven: the seams the author looked for after the third
 * review found the asked declaration, the heads of a function and the
 * set of programs to be such places.
 */
public class UsesSeamsTest {
	private static List<String> told(String text, int at) {
		ProjectKnowledge.Uses uses = UsesHarness.uses(text, at);
		assertNotNull(text, uses);
		return UsesHarness.told(uses);
	}

	@Test
	public void headsWrittenDifferentlyAreOneFunction() {
		// A parameter without a name, with a qualifier, with a
		// precision, under another name, with its size at its type:
		// each time the two heads are one function, asked at either.
		String[] texts = {
				"float f(float);\nvoid g(){f(1.);}\n" +
						"float f(float a){return a;}",
				"float f(in float a);\nfloat f(float a){return a;}\n" +
						"void g(){f(1.);}",
				"float f(highp float a);\nfloat f(float a){return a;}\n" +
						"void g(){f(1.);}",
				"float f(const float a);\nfloat f(float b){return b;}\n" +
						"void g(){f(1.);}",
				"float f(float a[2]);\nfloat f(float[2] a){return a[0];}\n" +
						"void g(){float v[2];f(v);}",
		};
		for (String text : texts) {
			int first = text.indexOf("f(");
			int second = text.indexOf("float f(", first) + 6;
			int call = text.indexOf("f(", text.indexOf("void g"));
			List<String> expected = new java.util.ArrayList<>(List.of(
					"main.frag:" + first + " DECLARATION",
					"main.frag:" + second + " DECLARATION",
					"main.frag:" + call + " USE"));
			java.util.Collections.sort(expected, (a, b) ->
					Integer.parseInt(a.substring(10, a.indexOf(' '))) -
							Integer.parseInt(b.substring(10, b.indexOf(' '))));
			assertEquals(text, expected, told(text, first));
			assertEquals(text, expected, told(text, second));
		}
	}

	@Test
	public void aFunctionUnderTheNameOfOneOfTheLanguage() {
		// Version 100 lets a text declare a form of "max"; a call with
		// other arguments means the language's own. One form alone is
		// not meant whatever is given, there.
		String text = "float max(float a){return a;}\n" +
				"void g(){max(1., 2.); max(1.);}";
		assertEquals(List.of("main.frag:6 DECLARATION",
				"main.frag:39 AMBIGUOUS", "main.frag:52 USE"),
				told(text, 6));
	}

	@Test
	public void aStructThatIsNotTrustedListsItsMembers() {
		// The name of the struct stands in an arm that is not decided:
		// the member is not proven, and it is not dropped.
		String text = "struct S { float x; };\nS s;\nvoid f() {\n" +
				"#ifdef GL_A\n\tS t;\n#endif\n\ts.x;\n}\n";
		ProjectKnowledge.Uses uses = UsesHarness.uses(text,
				text.indexOf("x;"));
		ProjectKnowledge.Place place = null;
		for (ProjectKnowledge.Place p : uses.places()) {
			if (p.offset() == text.lastIndexOf("x;")) {
				place = p;
			}
		}
		assertNotNull(UsesHarness.told(uses).toString(), place);
		assertFalse(place.certain());
		assertFalse(uses.depends().isEmpty());
	}

	@Test
	public void noTextUsesUpTheStack() {
		// Twelve thousand of each thing that lies in itself: the
		// shared reader, which the release editor uses too, and a
		// question about a name answer or decline, and do not throw.
		String[] deep = {
				"float x = " + "(".repeat(12000) + "1." +
						")".repeat(12000) + ";",
				"float x; void f(){" + "{".repeat(12000) + "x;" +
						"}".repeat(12000) + "}",
				"float a[1]; float x = a" + "[".repeat(12000) + "0" +
						"]".repeat(12000) + ";",
				"float y; float x = " + "-".repeat(12000) + "y;",
				"bool y; bool x = " + "!".repeat(12000) + "y;",
				"float f(float a){return a;} float x = " +
						"f(".repeat(6000) + "1." + ")".repeat(6000) + ";",
				"struct S{float x;}; S s; float x = s" +
						".x".repeat(12000) + ";",
				"#if " + "(".repeat(12000) + "1" + ")".repeat(12000) +
						"\nfloat x;\n#endif",
				"#if 1" + " || 1".repeat(12000) + "\nfloat x;\n#endif",
				"#if " + "!".repeat(12000) + "0\nfloat x;\n#endif",
				"#define A(x) x\nfloat x = " + "A(".repeat(6000) + "1." +
						")".repeat(6000) + ";",
				"float x = 1." + " + 1.".repeat(12000) + ";",
				"float x; void f(){" + "if(true)".repeat(12000) + "x;}",
				"void f(){float x;" + "x=".repeat(12000) + "1.;}",
				"float x = true" + "?1.:true".repeat(6000) + "?1.:2.;",
		};
		for (String s : deep) {
			GlslKnowledge.of(GlslLexer.lex(s), s);
			GlslKnowledge.of(s);
			UsesHarness.uses(s, s.indexOf("x"));
		}
	}
}

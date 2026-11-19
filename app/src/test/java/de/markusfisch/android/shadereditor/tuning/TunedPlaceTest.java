package de.markusfisch.android.shadereditor.tuning;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.math.BigDecimal;

import de.markusfisch.android.shadereditor.editor.Text;
import de.markusfisch.android.shadereditor.syntax.GlslLexer;
import de.markusfisch.android.shadereditor.syntax.LineTokens;

import de.markusfisch.android.shadereditor.opengl.Tuning;

public class TunedPlaceTest {
	@Test
	public void findsTheNumberAtTheCaretOrRightAfterIt() {
		String text = "float r = 0.3;";
		TunedPlace place = TunedPlace.at(text, text.indexOf("0.3") + 1);
		assertNotNull(place);
		assertEquals("0.3", place.literal);
		assertEquals(Tuning.Type.FLOAT, place.type);
		// Right after it.
		assertNotNull(TunedPlace.at(text, text.indexOf(";")));
		assertNull(TunedPlace.at(text, 2));
	}

	@Test
	public void notInACommentOrADirective() {
		assertNull(TunedPlace.at("// 0.3", 4));
		assertNull(TunedPlace.at("#version 300 es\n", 10));
		// On a directive line only a #define's: its body is code where
		// the macro is used.
		String defines = "#define SPEED 0.35\n#define SAT(x) clamp(x, " +
				"0.0, 0.9)\n#if SPEED > 2.0\n#endif\n#pragma se pass 3\n";
		assertNotNull(TunedPlace.at(defines, defines.indexOf("0.35")));
		assertNotNull(TunedPlace.at(defines, defines.indexOf("0.9")));
		assertNull(TunedPlace.at(defines, defines.indexOf("2.0")));
		assertNull(TunedPlace.at(defines, defines.lastIndexOf("3")));
	}

	@Test
	public void theMinusInFrontBelongsToTheValue() {
		String text = "float floor = -45.0;";
		TunedPlace place = TunedPlace.at(text, text.indexOf("45"));
		assertNotNull(place);
		assertEquals(text.indexOf("-"), place.signStart);
		assertEquals(0, new BigDecimal("-45.0").compareTo(place.value));
		// -45.0 + 50 is 5.0, and the minus goes.
		assertEquals("5.0", place.text(new BigDecimal("5"),
				BigDecimal.ONE));
		// After a name it would join it: a space keeps them apart.
		String joined = "return-1.;";
		TunedPlace after = TunedPlace.at(joined, joined.indexOf("1."));
		assertNotNull(after);
		assertEquals(" 2.", after.text(new BigDecimal("2"), BigDecimal.ONE));
		assertEquals("-2.", after.text(new BigDecimal("-2"),
				BigDecimal.ONE));
	}

	@Test
	public void aMinusAfterAnOperandIsAnOperator() {
		String text = "float y = a - 5.0;";
		TunedPlace place = TunedPlace.at(text, text.indexOf("5.0"));
		assertNotNull(place);
		assertEquals(place.start, place.signStart);
		// a - 5.0 tuned by -10 is a - (-5.0), never a --5.0.
		assertEquals("(-5.0)", place.text(new BigDecimal("-5"),
				BigDecimal.ONE));
		String tight = "float y = a-5.0;";
		TunedPlace close = TunedPlace.at(tight, tight.indexOf("5.0"));
		assertNotNull(close);
		assertEquals(close.start, close.signStart);
	}

	@Test
	public void findsThePartsOfAVectorOfNumbers() {
		String text = "vec3 ro = vec3(0.0, 1.0, -3.0);";
		TunedPlace place = TunedPlace.at(text, text.indexOf("1.0"));
		assertNotNull(place);
		TunedPlace.Vector vector = place.vector();
		assertNotNull(vector);
		assertEquals("vec3", vector.type());
		assertEquals(3, vector.parts().size());
		assertEquals(1, vector.index());
		assertEquals(0, new BigDecimal("-3.0").compareTo(
				vector.parts().get(2).value));
	}

	@Test
	public void aVectorOfOtherThanNumbersIsNone() {
		String text = "vec2 p = vec2(x, 0.5);";
		TunedPlace place = TunedPlace.at(text, text.indexOf("0.5"));
		assertNotNull(place);
		assertNull(place.vector());
		String call = "float f = mix(0.2, 0.5, t);";
		TunedPlace inCall = TunedPlace.at(call, call.indexOf("0.5"));
		assertNotNull(inCall);
		assertNull(inCall.vector());
	}

	@Test
	public void anAngleOnlyInsideRadians() {
		String text = "float a = radians(30.0);";
		TunedPlace place = TunedPlace.at(text, text.indexOf("30"));
		assertNotNull(place);
		assertTrue(place.degrees);
		String plain = "float a = 1.5707963;";
		TunedPlace other = TunedPlace.at(plain, plain.indexOf("1.5"));
		assertNotNull(other);
		assertFalse(other.degrees);
	}

	// A few lines lexed from the state their first starts in find what
	// the whole text does: a number in a comment opened far above is
	// none, a vector over two lines has its parts, offsets are the
	// text's.
	@Test
	public void aWindowFindsWhatTheWholeTextDoes() {
		String text = "/*\n" + "0.1\n".repeat(20) + "*/\n" +
				"float a = 0.5;\n".repeat(30) +
				"float b = vec2(1.0,\n\t-2.0).x;\n";
		Text t = Text.of(text);
		Window.Lines states = line -> {
			LineTokens scratch = new LineTokens();
			int state = GlslLexer.TEXT_START;
			for (int n = 0; n < line; ++n) {
				state = GlslLexer.lexLine(t.line(n), true, state, scratch);
			}
			return state;
		};
		int comment = text.indexOf("0.1", 60);
		assertNull(Window.around(t, comment, states).place(comment));
		for (String number : new String[]{"0.5", "1.0", "2.0"}) {
			int at = text.lastIndexOf(number);
			TunedPlace whole = TunedPlace.at(text, at);
			TunedPlace window = Window.around(t, at, states).place(at);
			assertNotNull(number, window);
			assertEquals(whole.signStart, window.signStart);
			assertEquals(whole.end, window.end);
			assertEquals(whole.line(), window.line());
			assertEquals(whole.vector() != null, window.vector() != null);
		}
		int two = text.lastIndexOf("2.0");
		assertEquals(text.lastIndexOf("1.0"), Window.around(t, two, states)
				.place(two).vector().parts().get(0).start);
	}

	// Review 3, L5: while the editor's colour states are stale, a window
	// does not take them: a number inside a long comment is none.
	@Test
	public void staleStatesExposeNoNumberOfAComment() {
		String src = "/*\n" + "padding\n".repeat(450) + "7.5\n*/";
		var document = new de.markusfisch.android.shadereditor.editor.Document(
				Text.of(src));
		var styles = new de.markusfisch.android.shadereditor.editor
				.LineStyles(document, de.markusfisch.android.shadereditor
				.syntax.GlslSyntax.INSTANCE, 200);
		int at = src.indexOf("7.5");
		assertNull(Window.around(document.text(), at, styles::state)
				.place(at));
	}

	// Review 3, L8: the minus wraps an int as the shader does.
	@Test
	public void theSignedMinimumIsItself() {
		TunedPlace place = TunedPlace.at("int min = -2147483648;", 12);
		assertNotNull(place);
		assertEquals(new BigDecimal("-2147483648"), place.value);
	}

	// Device pass: a number first on its line after "(" stands right
	// after it on the statement line, as the numbers after it do.
	@Test
	public void noSpaceAfterABracketBeforeTheNumber() {
		String text = "vec3 tint = vec3(\n\t0.20,\n\t0.60);\n";
		int at = text.indexOf("0.20");
		Statement statement = Statement.of(Window.around(Text.of(text), at,
				line -> -1), at, at + 4, offset -> false);
		assertEquals("vec3 tint = vec3(", statement.before());
		assertEquals(", 0.60);", statement.after());
	}

	// Review 3, L7: the minus that begins a #define's body is the
	// value's sign; a comment may stand between # and define.
	@Test
	public void aMacroBodyKeepsItsSign() {
		String src = "#define GAIN -1.25\n#define F(x) -0.5\n" +
				"#define G (x) -2.0\n# /* note */ define H 0.25\n";
		assertEquals(new BigDecimal("-1.25"),
				TunedPlace.at(src, src.indexOf("1.25")).value);
		assertEquals(new BigDecimal("-0.5"),
				TunedPlace.at(src, src.indexOf("0.5")).value);
		assertEquals(new BigDecimal("2.0"),
				TunedPlace.at(src, src.indexOf("2.0")).value);
		assertNotNull(TunedPlace.at(src, src.indexOf("0.25")));
	}
}

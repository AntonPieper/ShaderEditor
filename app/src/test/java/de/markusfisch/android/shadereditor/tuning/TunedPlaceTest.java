package de.markusfisch.android.shadereditor.tuning;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.math.BigDecimal;

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
		// The literal alone, with the minus kept in front of it.
		assertEquals("(-5.0)", place.literalText(new BigDecimal("5"),
				BigDecimal.ONE));
		assertEquals(-5.0, place.literalValue(new BigDecimal("5")), 0);
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
}

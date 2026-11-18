package de.markusfisch.android.shadereditor.widget;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class UsesSheetTest {
	@Test
	public void aShortLineIsShownWholeWithoutItsIndent() {
		UsesSheet.Cut cut = UsesSheet.cut("\t\tfloat r = radius;", 12, 6, 40);
		assertEquals("float r = radius;", cut.text());
		assertEquals(10, cut.at());
	}

	@Test
	public void aLongLineIsCutAroundTheName() {
		String line = "vec3 colour = mix(background, foreground * radius, " +
				"smoothstep(0.0, 1.0, d)) + glow;";
		int at = line.indexOf("radius");
		UsesSheet.Cut cut = UsesSheet.cut(line, at, 6, 24);
		assertEquals(24, cut.text().length());
		assertEquals("…round * radius, smooth…", cut.text());
		assertEquals("radius", cut.text().substring(cut.at(), cut.at() + 6));
	}

	@Test
	public void aNameNearTheStartKeepsTheStart() {
		UsesSheet.Cut cut = UsesSheet.cut(
				"\treturn smoothstep(radius, 0.0, length(p));", 19, 6, 30);
		assertEquals("return smoothstep(radius, 0.0…", cut.text());
		assertEquals(18, cut.at());
	}

	@Test
	public void theNameNearAnEndKeepsThatEnd() {
		String line = "radius = a + b + c + d + e + f + g + h;";
		UsesSheet.Cut start = UsesSheet.cut(line, 0, 6, 12);
		assertEquals("radius = a …", start.text());
		assertEquals(0, start.at());
		UsesSheet.Cut end = UsesSheet.cut("a + b + c + d + e + radius", 20,
				6, 12);
		assertEquals("… e + radius", end.text());
		assertEquals(6, end.at());
	}

	@Test
	public void aNameLongerThanTheRoomIsShownFromItsStart() {
		String name = "aVeryLongFunctionNameIndeed";
		UsesSheet.Cut cut = UsesSheet.cut("float x = " + name + "(p);", 10,
				name.length(), 12);
		assertEquals("…" + name + "…", cut.text());
		assertEquals(1, cut.at());
	}
}

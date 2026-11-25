package de.markusfisch.android.shadereditor.editor;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import de.markusfisch.android.shadereditor.syntax.GlslKnowledge;
import de.markusfisch.android.shadereditor.syntax.Knowledge;

public class NameFilterTest {
	@Test
	public void aFieldOrALayoutQualifierIsNoNameToLookUp() {
		String text = """
				int location = 0;
				layout(location = 0) out vec4 color;
				float r = 1.0;
				struct Disc { vec2 c; float r; };
				void main() {
					Disc d = Disc(vec2(0.0), r);
					float x = d.
						r + d \\
						.c.x;
					// The second hand.
					color = vec4(r);
				}
				""";
		Knowledge known = GlslKnowledge.of(text);
		Text t = Text.of(text);
		assertTrue(NameFilter.noName(known, t, text.indexOf("r; }")));
		assertTrue(NameFilter.noName(known, t, text.indexOf("r + d")));
		assertTrue(NameFilter.noName(known, t, text.indexOf("c.x")));
		assertTrue(NameFilter.noName(known, t, text.indexOf("location = 0)")));
		assertFalse(NameFilter.noName(known, t, text.indexOf("r);")));
		assertFalse(NameFilter.noName(known, t, text.indexOf("Disc d")));
		assertFalse(NameFilter.noName(known, t, text.indexOf("location")));
		// A comment ending in "." does not make a field.
		assertFalse(NameFilter.noName(known, t, text.indexOf("color = vec4")));
	}
}

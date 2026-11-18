package de.markusfisch.android.shadereditor.fragment;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

import de.markusfisch.android.shadereditor.R;
import de.markusfisch.android.shadereditor.editor.Document;
import de.markusfisch.android.shadereditor.editor.Text;
import de.markusfisch.android.shadereditor.editor.WordPlaces;

public class WordUsesTest {
	private static final String LIB = "float sdCircle(vec2 p, float r) {\n" +
			"\treturn length(p) - r; // sdCircle\n}\n";
	private static final String MAIN = "#include \"lib.glsl\"\n" +
			"void main() {\n\tfloat d = sdCircle(p, 1.0) + sdCircle(q, .5);\n}\n";

	@Test
	public void renamesEveryFileAndOneUndoTakesAllBack() {
		Document lib = new Document(Text.of(LIB));
		Document main = new Document(Text.of(MAIN));
		Document.Link rename = new Document.Link() {
			@Override
			public void undo(Document document) {
				assertNull(WordUses.takeBack(List.of(lib, main), this));
			}
		};
		WordUses.write(lib, code("lib.glsl", lib), "sdCircle", "circle",
				rename);
		WordUses.write(main, code("main.frag", main), "sdCircle", "circle",
				rename);
		assertEquals(LIB.replaceFirst("sdCircle", "circle"),
				lib.text().toString());
		assertEquals(MAIN.replace("sdCircle", "circle"),
				main.text().toString());

		main.undo();

		assertEquals(LIB, lib.text().toString());
		assertEquals(MAIN, main.text().toString());
		assertFalse(lib.canRedo());
	}

	@Test
	public void undoTakesNothingBackWhenAFileChangedSince() {
		Document lib = new Document(Text.of(LIB));
		Document main = new Document(Text.of(MAIN));
		Document[] stuck = {null};
		Document.Link rename = new Document.Link() {
			@Override
			public void undo(Document document) {
				stuck[0] = WordUses.takeBack(List.of(lib, main), this);
			}
		};
		WordUses.write(lib, code("lib.glsl", lib), "sdCircle", "circle",
				rename);
		WordUses.write(main, code("main.frag", main), "sdCircle", "circle",
				rename);
		lib.replace(0, 0, Text.of("// mine\n"));

		main.undo();

		assertSame(lib, stuck[0]);
		assertTrue(main.text().toString().contains("circle(p"));
		assertTrue(lib.text().toString().contains("float circle"));
	}

	@Test
	public void aTextChangedSinceThePlacesWereFoundIsNoticed() {
		Text found = Text.of(MAIN);
		Document main = new Document(found);
		assertTrue(WordUses.unchanged(List.of(main), List.of(found)));
		// The same text again is no change.
		assertTrue(WordUses.unchanged(List.of(main),
				List.of(Text.of(MAIN))));
		main.replace(0, 0, Text.of(" "));
		assertFalse(WordUses.unchanged(List.of(main), List.of(found)));
	}

	@Test
	public void saysWhatIsNoNewName() {
		assertEquals(-1, WordUses.problem("", "radius"));
		assertEquals(-1, WordUses.problem("radius", "radius"));
		assertEquals(0, WordUses.problem("size", "radius"));
		assertEquals(0, WordUses.problem("_r2", "radius"));
		assertEquals(R.string.name_not_glsl, WordUses.problem("2r", "radius"));
		assertEquals(R.string.name_not_glsl, WordUses.problem("größe", "r"));
		assertEquals(R.string.name_not_glsl, WordUses.problem("a-b", "r"));
		assertEquals(R.string.name_word_of_glsl, WordUses.problem("if", "r"));
		assertEquals(R.string.name_word_of_glsl, WordUses.problem("vec3",
				"r"));
		assertEquals(R.string.name_word_of_glsl, WordUses.problem("texture",
				"r"));
		assertEquals(R.string.name_gl, WordUses.problem("gl_Thing", "r"));
		assertEquals(R.string.name_reserved, WordUses.problem("a__b", "r"));
	}

	private static List<WordPlaces.Place> code(String file,
			Document document) {
		return WordPlaces.inScope(WordPlaces.in(file, document.text(),
				"sdCircle"), file, null, null);
	}
}

package de.markusfisch.android.shadereditor.editor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

import de.markusfisch.android.shadereditor.syntax.GlslSyntax;
import de.markusfisch.android.shadereditor.syntax.Style;

public class DimmedLinesTest {
	private static final String TEXT = """
			precision mediump float;
			#define AA 1
			#if AA > 1
			float a = 1.0;
			float b = 2.0;
			#else
			float c = 3.0;
			#endif
			float d = 4.0;""";

	// How often the lines asked for their directives to run again.
	private int asked;

	@Test
	public void dimsOnlyForTheTextTheBuildRead() {
		Document document = new Document(Text.of(TEXT));
		Text read = document.text();
		DimmedLines dimmed = dimmed(document);
		document.replace(0, 0, Text.of(" "));
		assertFalse(dimmed.set(read, List.of(3, 4)));
		assertEquals("_________", marked(dimmed, document));
		assertTrue(dimmed.set(document.text(), List.of(3, 4)));
		assertEquals("___XX____", marked(dimmed, document));
		assertEquals(0, asked);
	}

	@Test
	public void followsEditsThatChangeNoCondition() {
		Document document = document();
		DimmedLines dimmed = dimmed(document);
		dimmed.set(document.text(), List.of(3, 4));
		// A line typed in the off arm is in it.
		insert(document, "float b = 2.0;", "\nfloat e = 5.0;");
		assertEquals("___XXX____", marked(dimmed, document));
		// Lines above move it.
		document.replace(0, 0, Text.of("// note\n"));
		assertEquals("____XXX____", marked(dimmed, document));
		insert(document, "float d", " + 0.0");
		assertEquals("____XXX____", marked(dimmed, document));
		// Joining two off lines keeps them off.
		String text = document.text().toString();
		int at = text.indexOf("\nfloat b");
		document.replace(at, at + 1, Text.EMPTY);
		assertEquals("____XX____", marked(dimmed, document));
		assertEquals(0, asked);
	}

	@Test
	public void keepsAllButTheEditedLinesAfterAnEditThatMayChangeACondition() {
		// The edit, the lines after it, and after the stale ones are gone.
		for (String[] edit : new String[][]{
				{"#if AA > 1", " + 1", "___XX____", "_________"},
				{"#define AA 1", "0", "___XX____", "_________"},
				{"float a = 1.0;", " /*", "____X____", "_________"},
				{"float d = 4.0;", " */", "___XX____", "___XX____"},
				{"float a = 1.0;", " \\", "____X____", "_________"},
				{"float c = 3.0;", "\n#endif\n#if 1", "___XX______",
						"___XX______"}}) {
			asked = 0;
			Document document = document();
			DimmedLines dimmed = dimmed(document);
			dimmed.set(document.text(), List.of(3, 4));
			insert(document, edit[0], edit[1]);
			assertEquals(edit[1], edit[2], marked(dimmed, document));
			assertEquals(edit[1], 1, asked);
			dimmed.clearStale();
			assertEquals(edit[1], edit[3], marked(dimmed, document));
		}
		// Behind a line that a backslash joins to it.
		asked = 0;
		Document document = new Document(Text.of("#if 0\n#define A \\\n" +
				"1\nx\n#endif"));
		DimmedLines dimmed = dimmed(document);
		dimmed.set(document.text(), List.of(1, 2, 3));
		insert(document, "\n1", " + 1");
		assertEquals("_X_X_", marked(dimmed, document));
		assertEquals(1, asked);
		// An edit across an edge.
		document = document();
		dimmed = dimmed(document);
		dimmed.set(document.text(), List.of(3, 4));
		String text = document.text().toString();
		document.replace(text.indexOf("1.0;"), text.indexOf("#else"),
				Text.of("1.0;\n"));
		assertEquals("________", marked(dimmed, document));
		assertEquals(2, asked);
	}

	@Test
	public void keepsALineTypedInTheOffArmWhileItWaits() {
		Document document = document();
		DimmedLines dimmed = dimmed(document);
		dimmed.set(document.text(), List.of(3, 4));
		insert(document, "#define AA 1", "0");
		insert(document, "float b = 2.0;", "\nfloat e = 5.0;");
		assertEquals("___XXX____", marked(dimmed, document));
		// An answer asked for before it is for another text.
		assertEquals(2, asked);
		// Lines above the stale ones keep where they start.
		document = document();
		dimmed = dimmed(document);
		dimmed.set(document.text(), List.of(3, 4));
		insert(document, "#else", " ");
		document.replace(0, 0, Text.of("// x\n// y\n"));
		dimmed.clearStale();
		assertEquals("_____XX____", marked(dimmed, document));
	}

	@Test
	public void dropsALateAnswer() {
		Document document = document();
		DimmedLines dimmed = dimmed(document);
		dimmed.set(document.text(), List.of(3, 4));
		insert(document, "#define AA 1", "0");
		Text first = document.text();
		insert(document, "#define AA 10", "0");
		assertEquals(2, asked);
		assertFalse(dimmed.answer(first, List.of(5, 6)));
		assertEquals("___XX____", marked(dimmed, document));
		assertTrue(dimmed.answer(document.text(), List.of(6)));
		assertEquals("______X__", marked(dimmed, document));
		// Exact again: nothing is stale, and a second answer is late.
		dimmed.clearStale();
		assertFalse(dimmed.answer(document.text(), List.of()));
		assertEquals("______X__", marked(dimmed, document));
	}

	@Test
	public void anAnswerKeepsTheLinesAboveTheEdit() {
		Document document = document();
		DimmedLines dimmed = dimmed(document);
		dimmed.set(document.text(), List.of(3, 4));
		insert(document, "#else", " ");
		// One that cannot tell, as for a broken #if below.
		assertTrue(dimmed.answer(document.text(), List.of()));
		assertEquals("___XX____", marked(dimmed, document));
		insert(document, "#else", " ");
		assertTrue(dimmed.answer(document.text(), List.of(0, 4, 6)));
		assertEquals("___XX_X__", marked(dimmed, document));
		// After the time ran out, too.
		insert(document, "#define AA 1", "0");
		dimmed.clearStale();
		assertEquals("_________", marked(dimmed, document));
		assertTrue(dimmed.answer(document.text(), List.of(6)));
		assertEquals("______X__", marked(dimmed, document));
	}

	@Test
	public void dropsAllOnAnEditThatMayChangeThePrograms() {
		for (String[] edit : new String[][]{
				{"float a = 1.0;", "\n#include \"lib.glsl\""},
				{"precision mediump float;", "\n#pragma se input \"a.png\""},
				{"float d = 4.0;", " " + "x".repeat(5000)}}) {
			asked = 0;
			Document document = document();
			DimmedLines dimmed = dimmed(document);
			dimmed.set(document.text(), List.of(3, 4));
			insert(document, edit[0], edit[1]);
			assertEquals(edit[1], "", marked(dimmed, document)
					.replace("_", ""));
			// Till the next build.
			insert(document, "#define AA 1", "0");
			assertEquals(edit[1], 0, asked);
		}
		Document document = new Document(Text.of("#include \"lib.glsl\"\n" +
				"#if 0\nx\n#endif"));
		DimmedLines dimmed = dimmed(document);
		dimmed.set(document.text(), List.of(2));
		insert(document, "lib", "2");
		assertEquals("____", marked(dimmed, document));
		assertTrue(dimmed.set(document.text(), List.of(2)));
		assertEquals("__X_", marked(dimmed, document));
	}

	@Test
	public void drawsADimmedLineAsOneRunOfComment() {
		Document document = document();
		LineStyles styles = new LineStyles(document, GlslSyntax.INSTANCE, 30);
		DimmedLines dimmed = dimmed(document);
		styles.setDimmed(dimmed);
		LineStyles.Runs before = styles.runs(3);
		assertTrue(before.size() > 1);
		dimmed.set(document.text(), List.of(3, 4));
		LineStyles.Runs runs = styles.runs(3);
		assertEquals(1, runs.size());
		assertEquals(Style.COMMENT, runs.style(0));
		assertEquals(before.start(0), runs.start(0));
		assertEquals(before.end(before.size() - 1), runs.end(0));
		// The tokens stay, for what reads them.
		assertEquals(before.line().size(), runs.line().size());
		assertTrue(styles.runs(8).size() > 1);
	}

	private DimmedLines dimmed(Document document) {
		return new DimmedLines(document, () -> ++asked);
	}

	private static Document document() {
		return new Document(Text.of(TEXT));
	}

	private static void insert(Document document, String after, String text) {
		int at = document.text().toString().indexOf(after) + after.length();
		document.replace(at, at, Text.of(text));
	}

	private static String marked(DimmedLines dimmed, Document document) {
		StringBuilder sb = new StringBuilder();
		for (int n = 0; n < document.text().lineCount(); ++n) {
			sb.append(dimmed.isOff(n) ? 'X' : '_');
		}
		return sb.toString();
	}
}

package de.markusfisch.android.shadereditor.editor;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class AddUniformTest {
	@Test
	public void goesBehindTheLastUniformLine() {
		assertEquals("uniform float a;\nuniform float time;\nvoid main(){}",
				add("uniform float a;\nvoid main(){}", "uniform float time;"));
	}

	@Test
	public void replacesTheSameUniform() {
		// As the old editor: only a line followed by an empty line or
		// the end of the text.
		assertEquals("uniform float a;\nuniform vec2 resolution;\n",
				add("uniform float a;\nuniform vec2  resolution;\n",
						"uniform vec2 resolution;"));
	}

	@Test
	public void goesBehindTheLastEndifWithAnEmptyLine() {
		assertEquals("#ifdef A\n#endif\n\nuniform float time;\nvoid main(){}",
				add("#ifdef A\n#endif\nvoid main(){}", "uniform float time;"));
	}

	@Test
	public void goesToTheTopOtherwise() {
		assertEquals("uniform float time;\nvoid main(){}",
				add("void main(){}", "uniform float time;"));
	}

	@Test
	public void isOneUndoStep() {
		Document document = new Document(Text.of(
				"uniform float a;\nuniform float time;\n"));
		AddUniform.apply(document, "uniform float time;");
		document.undo();
		assertEquals("uniform float a;\nuniform float time;\n",
				document.text().toString());
	}

	private static String add(String text, String statement) {
		Document document = new Document(Text.of(text));
		AddUniform.apply(document, statement);
		return document.text().toString();
	}
}

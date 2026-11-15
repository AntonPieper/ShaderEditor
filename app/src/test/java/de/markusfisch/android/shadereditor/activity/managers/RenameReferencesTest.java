package de.markusfisch.android.shadereditor.activity.managers;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.List;

import de.markusfisch.android.shadereditor.editor.Document;
import de.markusfisch.android.shadereditor.editor.Text;
import de.markusfisch.android.shadereditor.project.ProjectPath;

public class RenameReferencesTest {
	private static final String MAIN = "#pragma se input \"blur.frag\" filter=linear\n" +
			"uniform sampler2D blur;\n" +
			"#pragma se input \"fx/blur.frag\"\n" +
			"// \"blur.frag\" is read above\n" +
			"#include \"blur.frag\"\n";

	@Test
	public void changesTheListedLinesThatNameTheFileAsOneStep() {
		Document document = new Document(Text.of(MAIN));
		assertEquals(2, RenameReferences.change(document, path("main.frag"),
				List.of(1, 4, 5), path("blur.frag"), path("soft.frag")));
		assertEquals("#pragma se input \"soft.frag\" filter=linear\n" +
						"uniform sampler2D blur;\n" +
						"#pragma se input \"fx/blur.frag\"\n" +
						"// \"blur.frag\" is read above\n" +
						"#include \"soft.frag\"\n",
				document.text().toString());
		document.undo();
		assertEquals(MAIN, document.text().toString());
	}

	@Test
	public void leavesALineThatMovedAway() {
		Document document = new Document(Text.of(MAIN));
		assertEquals(0, RenameReferences.change(document, path("main.frag"),
				List.of(2, 9), path("blur.frag"), path("soft.frag")));
		assertEquals(MAIN, document.text().toString());
	}

	private static ProjectPath path(String path) {
		return ProjectPath.parse(path).value();
	}
}

package de.markusfisch.android.shadereditor.syntax;

import static org.junit.Assert.assertFalse;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

public class SyntaxSeamTest {
	// What colours, analyses, completes and indents asks the syntax of
	// the file; none of it knows a language itself.
	@Test
	public void theEditorKnowsNoLanguage() throws IOException {
		String folder = "src/main/java/de/markusfisch/android/shadereditor/";
		for (String name : new String[]{"editor/LineStyles",
				"editor/LineStylesScheduler", "editor/Analysis",
				"editor/AnalysisScheduler", "editor/AutoIndent",
				"editor/EditCommands", "editor/KeyCommands",
				"widget/CodeView", "widget/DocumentWork", "syntax/Syntax",
				"syntax/LexedLine", "syntax/LineTokens"}) {
			String text = Files.readString(
					new File(folder + name + ".java").toPath());
			for (String language : new String[]{"Glsl", "Outline.builder"}) {
				assertFalse(name + " knows " + language,
						text.contains(language));
			}
		}
	}
}

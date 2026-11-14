package de.markusfisch.android.shadereditor.project;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import de.markusfisch.android.shadereditor.syntax.LexedLine;
import de.markusfisch.android.shadereditor.syntax.LineTokens;
import de.markusfisch.android.shadereditor.syntax.Outline;
import de.markusfisch.android.shadereditor.syntax.Syntax;

public class ManifestSyntaxTest {
	@Test
	public void completesTheKeysWhereAKeyStands() {
		assertEquals("[entry] 2", complete("{\"en", null));
		assertEquals("[projectVersion, entry] 0", complete("\t\"", null));
		assertEquals("[projectVersion] 1",
				complete("{\"entry\": \"a\", \"p", null));
	}

	@Test
	public void completesTheFilesAsTheEntry() {
		List<String> files = List.of("main.frag", "map.frag", "x.glsl");
		assertEquals("[main.frag, map.frag] 2",
				complete("\t\"entry\": \"ma", files));
		assertEquals("[main.frag, map.frag, x.glsl] 0",
				complete("{\"entry\":\"", files));
		// Not what is typed already.
		assertEquals("[] 9", complete("\"entry\": \"main.frag", files));
	}

	@Test
	public void completesNothingElsewhere() {
		List<String> files = List.of("main.frag");
		// After the closing quote, in a number, outside of strings.
		assertEquals("[] 0", complete("{\"entry\"", files));
		assertEquals("[] 0", complete("\"projectVersion\": 1", files));
		assertEquals("[] 0", complete("\"entry\": ", files));
		// The value of another key.
		assertEquals("[] 1", complete("\"projectVersion\": \"m", files));
	}

	private static String complete(String line, List<String> files) {
		ManifestSyntax syntax = new ManifestSyntax();
		if (files != null) {
			syntax.setFiles(files);
		}
		List<String> offered = new ArrayList<>();
		syntax.complete(LexedLine.of(syntax, line, false, Syntax.TEXT_START,
						new LineTokens()), line.length(), 0, Outline.NONE,
				true, List.of(), (words, position) ->
						offered.add(words + " " + position));
		assertEquals(1, offered.size());
		return offered.get(0);
	}
}

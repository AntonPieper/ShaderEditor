package de.markusfisch.android.shadereditor.tree;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import de.markusfisch.android.shadereditor.syntax.BundledShaders;

/** The texts the tree's tests read: the app's own shaders, and those damaged. */
final class TreeTexts {
	// What a damaged text gets put in: what changes how the rest is read.
	private static final String[] PIECES = {"\\\n", "/*", "*/", "//", "\n", "#",
			"\"", "{", "}", "(", ")", ";", ",", "=", "+", ".", " ", "x", "1",
			"e", "ä", " ", "\r\n", "\\", "#define A(x) x\n", "#if 0\n",
			"#else\n", "#endif\n", "float", "struct"};

	private TreeTexts() {
	}

	/** The bundled shaders and the examples. */
	static List<String> own() {
		try {
			return new ArrayList<>(BundledShaders.read().values());
		} catch (IOException e) {
			throw new AssertionError(e);
		}
	}

	/** Text with one piece of it deleted, doubled or replaced. */
	static String damaged(String text, Random random) {
		int from = random.nextInt(text.length() + 1);
		int to = Math.min(text.length(), from + random.nextInt(4));
		String piece = PIECES[random.nextInt(PIECES.length)];
		switch (random.nextInt(3)) {
			case 0:
				return text.substring(0, from) + text.substring(to);
			case 1:
				return text.substring(0, to) + text.substring(from);
			default:
				return text.substring(0, from) + piece + text.substring(to);
		}
	}

	/** A text of pieces in no order. */
	static String soup(Random random, int pieces) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < pieces; ++i) {
			sb.append(PIECES[random.nextInt(PIECES.length)]);
		}
		return sb.toString();
	}
}

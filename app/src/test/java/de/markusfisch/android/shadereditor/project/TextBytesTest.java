package de.markusfisch.android.shadereditor.project;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import de.markusfisch.android.shadereditor.editor.Text;

public class TextBytesTest {
	@Test
	public void writesTheBytesOfTheStringEvenForLoneSurrogates()
			throws IOException {
		for (String s : new String[]{"A\uD83D", "A\uD83D\nB", "\uDE00A",
				"café\n😀"}) {
			byte[] expected = s.getBytes(StandardCharsets.UTF_8);
			ByteArrayOutputStream out = new ByteArrayOutputStream();

			TextBytes.write(Text.of(s), out);

			assertArrayEquals(s, expected, out.toByteArray());
			assertEquals(expected.length, TextBytes.length(Text.of(s)));
		}
	}
}

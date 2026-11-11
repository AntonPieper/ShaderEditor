package de.markusfisch.android.shadereditor.project;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

public class SourceTextTest {
	@Test
	public void stripsLeadingBom() {
		assertEquals("void main(){}\n", decode("\uFEFFvoid main(){}\n"));
	}

	@Test
	public void keepsBomThatIsNotLeading() {
		assertEquals("a\uFEFF", decode("a\uFEFF"));
	}

	@Test
	public void normalizesCrLfAndLoneCrKeepingLineNumbers() {
		String text = decode("a\r\nb\rc\n\r\nd");
		assertEquals("a\nb\nc\n\nd", text);
		assertEquals(List.of("a", "b", "c", "", "d"), SourceText.lines(text));
	}

	@Test
	public void finalLineEndingStartsNoLine() {
		assertEquals(List.of("a", ""), SourceText.lines("a\n\n"));
		assertEquals(List.of(), SourceText.lines(""));
	}

	@Test
	public void rejectsInvalidUtf8() {
		assertNull(SourceText.decode(new byte[]{'a', (byte) 0xC3}));
	}

	private static String decode(String text) {
		return SourceText.decode(text.getBytes(StandardCharsets.UTF_8));
	}
}

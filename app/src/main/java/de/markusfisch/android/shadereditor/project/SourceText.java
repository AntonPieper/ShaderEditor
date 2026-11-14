package de.markusfisch.android.shadereditor.project;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Intake of shader text: UTF-8, an optional leading BOM, and CRLF or a
 * lone CR normalized to LF. Every original line ending becomes exactly
 * one LF, so line numbers still refer to the original lines.
 */
public final class SourceText {
	private SourceText() {
	}

	/**
	 * Return the normalized text, or null if bytes are not UTF-8.
	 */
	public static String decode(byte[] bytes) {
		String text;
		try {
			text = StandardCharsets.UTF_8.newDecoder()
					.onMalformedInput(CodingErrorAction.REPORT)
					.onUnmappableCharacter(CodingErrorAction.REPORT)
					.decode(ByteBuffer.wrap(bytes))
					.toString();
		} catch (CharacterCodingException e) {
			return null;
		}
		if (!text.isEmpty() && text.charAt(0) == 0xFEFF) {
			text = text.substring(1);
		}
		return text.replace("\r\n", "\n").replace('\r', '\n');
	}

	/**
	 * Return where the characters a compiler cannot read begin when
	 * they end a line of code right behind a backslash, else -1. Such a
	 * line is written to go on in the next one, and for a compiler it
	 * does only when they are left out; a space in their place would
	 * end it. code is the line without its comments: a backslash in a
	 * comment is not meant.
	 */
	public static int afterContinuation(String line, String code) {
		int tail = line.length();
		while (tail > 0 && !compilerReads(line.charAt(tail - 1))) {
			--tail;
		}
		return tail < line.length() && tail > 0 && tail <= code.length() &&
				code.charAt(tail - 1) == '\\' ? tail : -1;
	}

	private static boolean compilerReads(char c) {
		return c == '\t' || (c >= 0x20 && c <= 0x7e);
	}

	/**
	 * Split normalized text into lines; a final line ending does not
	 * start another line.
	 */
	public static List<String> lines(String text) {
		List<String> lines = new ArrayList<>(List.of(text.split("\n", -1)));
		if (lines.get(lines.size() - 1).isEmpty()) {
			lines.remove(lines.size() - 1);
		}
		return lines;
	}
}

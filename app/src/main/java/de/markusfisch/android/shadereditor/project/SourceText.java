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

package de.markusfisch.android.shadereditor.project;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

import de.markusfisch.android.shadereditor.editor.Text;

/**
 * A {@link Text} as the UTF-8 bytes of a file, written line by line in
 * chunks of a few KiB, so no array of the whole text is made.
 */
final class TextBytes {
	private TextBytes() {
	}

	/**
	 * Write text to out as UTF-8, lines joined by '\n', with the bytes
	 * {@link String#getBytes} makes, so a lone surrogate becomes '?'.
	 * Out is flushed, not closed.
	 */
	static void write(Text text, OutputStream out) throws IOException {
		Writer writer = new OutputStreamWriter(new FilterOutputStream(out) {
			@Override
			public void write(byte[] b, int off, int len) throws IOException {
				out.write(b, off, len);
			}

			@Override
			public void close() throws IOException {
				out.flush();
			}
		}, StandardCharsets.UTF_8);
		for (int i = 0, lines = text.lineCount(); i < lines; ++i) {
			if (i > 0) {
				writer.write('\n');
			}
			writer.write(text.line(i));
		}
		// Only closing ends the input, so a trailing surrogate is written.
		writer.close();
	}

	/** Return the number of bytes write writes. */
	static long length(Text text) throws IOException {
		long[] length = {0};
		write(text, new OutputStream() {
			@Override
			public void write(int b) {
				++length[0];
			}

			@Override
			public void write(byte[] b, int off, int len) {
				length[0] += len;
			}
		});
		return length[0];
	}
}

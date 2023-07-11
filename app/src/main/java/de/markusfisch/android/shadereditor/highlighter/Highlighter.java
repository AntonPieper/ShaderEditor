package de.markusfisch.android.shadereditor.highlighter;


import android.text.Spannable;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.Closeable;


/**
 * A Highlighter can highlight text using tree-sitter.
 * <ul>
 *     <li>
 *         Use the {@link #highlight} method to generate
 *         highlights for given source code.
 *     </li>
 *     <li>
 *         To create a highlighter use {@link #create} and pass the highlighting names
 *         that you recognize.
 * 		</li>
 */
public class Highlighter implements Closeable {
	static {
		System.loadLibrary("rust");
	}

	/**
	 * Calls <code>onHighlight</code> for each highlighting token that this highlighter was registered for.
	 *
	 * @param source    the source code to highlight
	 * @param spannable the destination for the highlights
	 * @return false if an error occurred on the native side.
	 */
	public boolean highlight(@NonNull String source, @NonNull Spannable spannable) {
		long start = System.nanoTime();
		if (pointer == 0) return false;
		Native.highlight(pointer, source, spannable);
		Log.d("HIGHLIGHT", String.format("highlighting took %fms", (System.nanoTime() - start) * 1e-6));
		return true;
	}

	public static @Nullable Highlighter create(@NonNull String[] names, @NonNull int[] colors) {
		long pointer = Native.createHighlighter(names, colors);
		if (pointer == 0) return null;
		return new Highlighter(pointer, colors);
	}

	@Override
	public void close() {
		if (pointer != 0) {
			Native.deleteHighlighter(pointer);
			pointer = 0;
		}
	}

	private Highlighter(long pointer, @NonNull int[] colors) {
		this.pointer = pointer;
		this.colors = colors;
	}

	private long pointer;
	@NonNull
	int[] colors;

	private static class Native {
		private Native() {
		}

		public static native long createHighlighter(@NonNull String[] names, @NonNull int[] colors);

		public static native void highlight(long highlighter, @NonNull String source, @NonNull Spannable spannable);

		public static native void deleteHighlighter(long pointer);
	}
}

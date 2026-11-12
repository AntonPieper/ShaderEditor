package de.markusfisch.android.shadereditor.fragment;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.Executor;

/**
 * The editor's text while the app is in the background, kept in a file
 * instead of the saved instance state.
 * <p>
 * Saved instance state travels through a Binder transaction, which fails
 * with a TransactionTooLargeException above about 500 KB; a large shader
 * gets there. The state only carries the key returned by {@link #save}.
 * A text is only read back for the same key, so a file left by an older
 * save, or by another window, is never shown.
 */
final class EditorDraft {
	private static final String TAG = "EditorDraft";
	private static final String FILE_NAME = "editor_draft";

	/**
	 * Finds the directory of the draft. That can touch the disk, so it
	 * is only called on the executor or inside {@link #load}.
	 */
	@FunctionalInterface
	interface Dir {
		@NonNull
		File get();
	}

	@NonNull
	private final Dir dir;
	@NonNull
	private final Executor executor;

	/**
	 * @param dir      where the draft lives
	 * @param executor runs every write and delete, one after the other
	 */
	EditorDraft(@NonNull Dir dir, @NonNull Executor executor) {
		this.dir = dir;
		this.executor = executor;
	}

	@NonNull
	private File file() {
		return new File(dir.get(), FILE_NAME);
	}

	@NonNull
	static String newKey() {
		return UUID.randomUUID().toString();
	}

	/**
	 * Writes {@code text} under {@code key} on the executor. The old
	 * draft stays readable until the new one is complete.
	 */
	void save(@NonNull String key, @NonNull String text) {
		byte[] content = encode(key, text);
		executor.execute(() -> {
			File file = file();
			File tmp = new File(file.getPath() + ".tmp");
			try (OutputStream out = new FileOutputStream(tmp)) {
				out.write(content);
			} catch (IOException e) {
				Log.e(TAG, "Cannot write the editor draft", e);
				//noinspection ResultOfMethodCallIgnored
				tmp.delete();
				return;
			}
			if (!tmp.renameTo(file)) {
				Log.e(TAG, "Cannot replace the editor draft");
				//noinspection ResultOfMethodCallIgnored
				tmp.delete();
			}
		});
	}

	/**
	 * Reads the draft on the calling thread.
	 *
	 * @return the text saved under {@code key}, or null if the draft is
	 * missing or was saved under another key
	 */
	@Nullable
	String load(@NonNull String key) {
		File file = file();
		if (!file.isFile()) {
			return null;
		}
		try (InputStream in = new FileInputStream(file)) {
			byte[] content = new byte[(int) file.length()];
			int read = 0;
			while (read < content.length) {
				int n = in.read(content, read, content.length - read);
				if (n < 0) {
					break;
				}
				read += n;
			}
			return decode(key, content, read);
		} catch (IOException e) {
			Log.e(TAG, "Cannot read the editor draft", e);
			return null;
		}
	}

	/**
	 * Removes the draft on the executor, after any pending write.
	 */
	void delete() {
		executor.execute(() -> {
			//noinspection ResultOfMethodCallIgnored
			file().delete();
		});
	}

	@NonNull
	static byte[] encode(@NonNull String key, @NonNull String text) {
		return (key + '\n' + text).getBytes(StandardCharsets.UTF_8);
	}

	@Nullable
	static String decode(@NonNull String key, @NonNull byte[] content, int length) {
		String all = new String(content, 0, length, StandardCharsets.UTF_8);
		int newline = all.indexOf('\n');
		if (newline < 0 || !all.substring(0, newline).equals(key)) {
			return null;
		}
		return all.substring(newline + 1);
	}
}

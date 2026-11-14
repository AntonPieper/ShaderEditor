package de.markusfisch.android.shadereditor.project;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * A small file of what the app keeps beside the user's files. Reading
 * one has three outcomes, and they are never mixed up: it is not
 * there, it holds these bytes, or it is there and cannot be read. Who
 * changes what a file holds must not take the third for the first.
 */
final class StateFile {
	private StateFile() {
	}

	/**
	 * Return the bytes of file, or null when there is no such file.
	 *
	 * @throws IOException when it is there and cannot be read
	 */
	static byte[] read(File file) throws IOException {
		if (!file.exists()) {
			return null;
		}
		try (FileInputStream in = new FileInputStream(file)) {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			byte[] buffer = new byte[8192];
			for (int n; (n = in.read(buffer)) > 0; ) {
				bytes.write(buffer, 0, n);
			}
			return bytes.toByteArray();
		}
	}

	/** Replace file with bytes in one rename; whether it holds them. */
	static boolean replace(File file, byte[] bytes) {
		File folder = file.getParentFile();
		if (folder == null || (!folder.isDirectory() && !folder.mkdirs())) {
			return false;
		}
		File temp = new File(folder, file.getName() + ".tmp");
		try (FileOutputStream out = new FileOutputStream(temp)) {
			out.write(bytes);
			out.getFD().sync();
		} catch (IOException e) {
			temp.delete();
			return false;
		}
		if (temp.renameTo(file)) {
			return true;
		}
		temp.delete();
		return false;
	}
}

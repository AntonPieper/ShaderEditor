package de.markusfisch.android.shadereditor.project;

import android.content.res.AssetManager;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * A folder of files that come with the app, such as the examples: what
 * lies under one folder of its assets. It is read-only, and its bytes
 * change only with the app, so it needs no grant and is never stale.
 */
public final class AssetProjectFiles implements ProjectFiles {
	/** Reads the app's assets, as {@link AssetManager} does. */
	public interface Assets {
		/** Open the file at path; throw when there is none. */
		InputStream open(String path) throws IOException;

		/**
		 * The names in the folder at path; none when it is a file or
		 * there is nothing.
		 */
		String[] list(String path) throws IOException;
	}

	private final Assets assets;
	private final String root;

	public AssetProjectFiles(Assets assets, String root) {
		this.assets = assets;
		this.root = root;
	}

	/** The files under folder root of the app's assets. */
	public static AssetProjectFiles of(AssetManager manager, String root) {
		return new AssetProjectFiles(new Assets() {
			@Override
			public InputStream open(String path) throws IOException {
				return manager.open(path);
			}

			@Override
			public String[] list(String path) throws IOException {
				return manager.list(path);
			}
		}, root);
	}

	@Override
	public Read read(ProjectPath path) {
		return read(path, Long.MAX_VALUE);
	}

	@Override
	public Read read(ProjectPath path, long limit) {
		try (InputStream in = assets.open(asset(path))) {
			byte[] bytes = bytes(in, limit);
			return bytes == null
					? new Failed(TOO_LARGE)
					: new Content(bytes, SafProjectFiles.revision(bytes));
		} catch (FileNotFoundException e) {
			return null;
		} catch (IOException e) {
			return new Failed(SafProjectFiles.reason(e, e.getMessage()));
		}
	}

	@Override
	public byte[] head(ProjectPath path, int count) {
		try (InputStream in = assets.open(asset(path))) {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] buffer = new byte[Math.min(count, 8192)];
			int n;
			while (out.size() < count && (n = in.read(buffer, 0,
					Math.min(buffer.length, count - out.size()))) > 0) {
				out.write(buffer, 0, n);
			}
			return out.toByteArray();
		} catch (IOException e) {
			return null;
		}
	}

	@Override
	public Listing list(ProjectPath folder) {
		String dir = folder != null ? asset(folder) : root;
		try {
			String[] names = assets.list(dir);
			if (names == null || names.length == 0) {
				// The assets hold no empty folders.
				return null;
			}
			List<Entry> entries = new ArrayList<>();
			for (String name : names) {
				String[] inside = assets.list(dir + "/" + name);
				entries.add(new Entry(SafProjectFiles.nfc(name),
						inside != null && inside.length > 0));
			}
			return new Entries(entries);
		} catch (IOException e) {
			return new Failed(SafProjectFiles.reason(e, e.getMessage()));
		}
	}

	private String asset(ProjectPath path) {
		return root + "/" + path;
	}

	// All bytes of in, or null when there are more than limit.
	private static byte[] bytes(InputStream in, long limit)
			throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[] buffer = new byte[8192];
		long total = 0;
		int n;
		while ((n = in.read(buffer)) > 0) {
			total += n;
			if (total > limit) {
				return null;
			}
			out.write(buffer, 0, n);
		}
		return out.toByteArray();
	}
}

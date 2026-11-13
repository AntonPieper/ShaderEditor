package de.markusfisch.android.shadereditor.project;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import de.markusfisch.android.shadereditor.editor.Text;
import de.markusfisch.android.shadereditor.project.legacy.StoredFiles;

/**
 * Another store with unsaved text laid over it: a file with a draft
 * reads as the draft, everything else as the store has it, so a build
 * sees the current edits. Changes go to the store through the editing
 * session, not through this view. Safe to read from any thread.
 */
public final class DraftFiles implements ProjectFiles {
	/**
	 * Unsaved text of a file and the revision of the stored file it was
	 * based on, null when the file is new. Two drafts are equal when
	 * their text reads the same and their base is the same.
	 */
	public record Draft(Text text, String base) {
		public Draft {
			if (text == null) {
				throw new IllegalArgumentException("a draft needs text");
			}
		}

		public Draft(String text, String base) {
			this(text != null ? Text.of(text) : null, base);
		}

		public byte[] bytes() {
			return text.toString().getBytes(StandardCharsets.UTF_8);
		}

		/** Write the bytes of the text to out without making them all. */
		public void writeTo(OutputStream out) throws IOException {
			TextBytes.write(text, out);
		}

		@Override
		public boolean equals(Object o) {
			return o instanceof Draft draft &&
					text.toString().equals(draft.text.toString()) &&
					(base == null ? draft.base == null
							: base.equals(draft.base));
		}

		@Override
		public int hashCode() {
			return text.toString().hashCode() * 31 +
					(base != null ? base.hashCode() : 0);
		}
	}

	private final Map<ProjectPath, Draft> drafts = new LinkedHashMap<>();
	private final ProjectFiles store;

	public DraftFiles(ProjectFiles store) {
		this.store = store;
	}

	public ProjectFiles store() {
		return store;
	}

	@Override
	public Read read(ProjectPath path) {
		Draft draft = get(path);
		if (draft == null) {
			return store.read(path);
		}
		byte[] bytes = draft.bytes();
		String revision = StoredFiles.revision(bytes);
		return revision != null
				? new Content(bytes, "draft:" + revision)
				: new Failed("SHA-256 is not available");
	}

	@Override
	public Listing list(ProjectPath folder) {
		return store.list(folder);
	}

	/** Return the draft of path, or null when it has none. */
	public synchronized Draft get(ProjectPath path) {
		return drafts.get(path);
	}

	public synchronized void put(ProjectPath path, Draft draft) {
		drafts.put(path, draft);
	}

	/** Drop the draft of path; return whether there was one. */
	public synchronized boolean remove(ProjectPath path) {
		return drafts.remove(path) != null;
	}

	/**
	 * Return the paths of every file with a draft, in the order they
	 * were first drafted.
	 */
	public synchronized Set<ProjectPath> paths() {
		return new LinkedHashSet<>(drafts.keySet());
	}
}

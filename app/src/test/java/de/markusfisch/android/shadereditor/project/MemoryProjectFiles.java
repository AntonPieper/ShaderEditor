package de.markusfisch.android.shadereditor.project;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class MemoryProjectFiles implements ProjectFiles {
	private final Map<ProjectPath, Content> files = new HashMap<>();
	final List<ProjectPath> readPaths = new ArrayList<>();
	int reads;
	int writes;
	// The most bytes a write handed over in one call.
	int largestChunk;
	// Why every write fails, or null.
	String failure;
	// Whether writes are taken; a store is read-only by default.
	boolean writable;
	// Outside edits so far, so each gets its own revision.
	private int puts;

	MemoryProjectFiles put(String path, String text) {
		return put(path, text.getBytes(StandardCharsets.UTF_8));
	}

	MemoryProjectFiles put(String path, byte[] bytes) {
		files.put(ProjectPath.parse(path).value(),
				new Content(bytes, "r" + puts++));
		return this;
	}

	@Override
	public Content read(ProjectPath path) {
		++reads;
		readPaths.add(path);
		return files.get(path);
	}

	String text(String path) {
		Content content = files.get(ProjectPath.parse(path).value());
		return content != null
				? new String(content.bytes(), StandardCharsets.UTF_8)
				: null;
	}

	@Override
	public Change write(ProjectPath path, byte[] bytes,
			String expectedRevision) {
		return writable
				? write(path, out -> out.write(bytes), expectedRevision)
				: ProjectFiles.super.write(path, bytes, expectedRevision);
	}

	@Override
	public Change write(ProjectPath path, Source source,
			String expectedRevision) {
		if (!writable) {
			return ProjectFiles.super.write(path, source, expectedRevision);
		} else if (failure != null) {
			return new Refused(Refusal.FAILED, failure);
		}
		Content old = files.get(path);
		if (old == null ? expectedRevision != null
				: !old.revision().equals(expectedRevision)) {
			return new Refused(Refusal.CONFLICT, "the file has changed");
		}
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try {
			source.writeTo(new OutputStream() {
				@Override
				public void write(int b) {
					largestChunk = Math.max(largestChunk, 1);
					bytes.write(b);
				}

				@Override
				public void write(byte[] b, int off, int len) {
					largestChunk = Math.max(largestChunk, len);
					bytes.write(b, off, len);
				}
			});
		} catch (IOException e) {
			return new Refused(Refusal.FAILED, e.getMessage());
		}
		String revision = "w" + ++writes;
		files.put(path, new Content(bytes.toByteArray(), revision));
		return new Done(revision);
	}

	@Override
	public Change rename(ProjectPath from, ProjectPath to) {
		if (!writable) {
			return ProjectFiles.super.rename(from, to);
		} else if (!files.containsKey(from)) {
			return new Refused(Refusal.ABSENT, "there is no " + from);
		} else if (files.containsKey(to)) {
			return new Refused(Refusal.EXISTS, to + " exists");
		}
		files.put(to, files.remove(from));
		return new Done(null);
	}

	@Override
	public Change delete(ProjectPath path) {
		if (!writable) {
			return ProjectFiles.super.delete(path);
		}
		return files.remove(path) != null
				? new Done(null)
				: new Refused(Refusal.ABSENT, "there is no " + path);
	}
}

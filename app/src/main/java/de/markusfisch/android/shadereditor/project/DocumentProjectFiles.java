package de.markusfisch.android.shadereditor.project;

import java.io.IOException;
import java.util.List;

import de.markusfisch.android.shadereditor.project.DocumentResolver.Document;

/**
 * A project of one document opened on its own, without a folder. The
 * document is its only file, at a path made from its name, and the
 * running shader whatever that name is. There is no manifest; every
 * other path fails with a reason that asks for the folder: the grant of
 * a document reaches no other document, so no parent is guessed.
 */
public final class DocumentProjectFiles implements ProjectFiles {
	public static final String OPEN_FOLDER =
			"Open this shader's folder to load its other files";
	static final String LOST_FILE =
			"ShaderEditor lost access to this file, open it again";

	private final DocumentResolver resolver;
	private final String id;
	private final ProjectPath path;
	private final boolean writable;

	/**
	 * Open the document id, displayed as name; writable tells whether
	 * the grant allows writing.
	 */
	public DocumentProjectFiles(DocumentResolver resolver, String id,
			String name, boolean writable) {
		this.resolver = resolver;
		this.id = id;
		this.path = path(name);
		this.writable = writable;
	}

	/**
	 * Return the path of a document named name: the name, with
	 * characters a path cannot hold replaced, and prefixed with '_' when
	 * it would still not be a plain file name, like ".." or "c:x".
	 */
	public static ProjectPath path(String name) {
		StringBuilder sb = new StringBuilder();
		for (char c : SafProjectFiles.nfc(name != null ? name : "")
				.toCharArray()) {
			sb.append(c == '/' || c == '\\' || c == '"' ||
					Character.isISOControl(c) ? '_' : c);
		}
		String text = sb.length() > 0 ? sb.toString() : "shader.glsl";
		ProjectParseResult<ProjectPath> parsed = ProjectPath.parse(text);
		return parsed.isSuccess() && parsed.value().toString().equals(text)
				? parsed.value()
				: ProjectPath.parse("_" + text).value();
	}

	/**
	 * Return the path of the document, the running shader.
	 */
	public ProjectPath getPath() {
		return path;
	}

	/**
	 * Return what the document offers when path is its path: at most
	 * writing, since it has no folder to change.
	 */
	@Override
	public Offers offers(ProjectPath path) {
		if (!this.path.equals(path)) {
			return Offers.NONE;
		}
		try {
			return SafProjectFiles.offers(resolver.document(id),
					writable).write()
					? new Offers(true, false, false, false, false)
					: Offers.NONE;
		} catch (IOException | RuntimeException e) {
			return Offers.NONE;
		}
	}

	@Override
	public Read read(ProjectPath path) {
		return read(path, Long.MAX_VALUE);
	}

	@Override
	public Read read(ProjectPath path, long limit) {
		if (!this.path.equals(path)) {
			// A lone document has no manifest beside it.
			return ProjectManifest.FILE.equals(path)
					? null
					: new Failed(OPEN_FOLDER);
		}
		try {
			byte[] bytes = resolver.read(id, limit);
			return bytes != null
					? new Content(bytes, SafProjectFiles.revision(bytes))
					: new Failed(TOO_LARGE);
		} catch (IOException | RuntimeException e) {
			return new Failed(SafProjectFiles.reason(e, LOST_FILE));
		} catch (OutOfMemoryError e) {
			return new Failed("it is too large");
		}
	}

	@Override
	public Listing list(ProjectPath folder) {
		return folder == null
				? new Entries(List.of(new Entry(path.toString(), false)))
				: new Failed(OPEN_FOLDER);
	}

	@Override
	public Change write(ProjectPath path, byte[] bytes,
			String expectedRevision) {
		if (!this.path.equals(path)) {
			return openFolder();
		}
		try {
			Document document = resolver.document(id);
			if (!SafProjectFiles.offers(document, writable).write()) {
				return new Refused(Refusal.UNSUPPORTED,
						"this file is read-only");
			} else if (!SafProjectFiles.isAt(resolver, id, expectedRevision)) {
				return new Refused(Refusal.CONFLICT, expectedRevision == null
						? "the file exists"
						: "the file has changed");
			}
			SafProjectFiles.write(resolver, id, bytes, LOST_FILE);
			return new Done(SafProjectFiles.revision(bytes));
		} catch (IOException | RuntimeException e) {
			return SafProjectFiles.refused(e, LOST_FILE);
		}
	}

	@Override
	public Change createFolder(ProjectPath folder) {
		return openFolder();
	}

	@Override
	public Change rename(ProjectPath from, ProjectPath to) {
		return openFolder();
	}

	@Override
	public Change delete(ProjectPath path) {
		return openFolder();
	}

	private static Change openFolder() {
		return new Refused(Refusal.UNSUPPORTED,
				"Open this shader's folder to change its files");
	}
}

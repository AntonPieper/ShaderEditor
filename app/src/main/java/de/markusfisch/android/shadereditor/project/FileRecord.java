package de.markusfisch.android.shadereditor.project;

import java.util.ArrayList;
import java.util.List;

import de.markusfisch.android.shadereditor.editor.Document;
import de.markusfisch.android.shadereditor.editor.Text;

/**
 * What the project knows of one file, on the main thread: its document
 * while it is in memory, the stored revision its text was last equal
 * to, and the one operation on storage in flight for it. It does no
 * I/O: an operation starts as a {@link Request}, and what storage gave
 * is adopted only for the request still current, so text and base
 * change together, before the listeners of the document hear.
 */
final class FileRecord {
	enum Kind {
		OPEN, RELOAD, SAVE, CHECK
	}

	/**
	 * An operation on storage: its ticket, the revision of the document
	 * when it was asked, -1 without one, and the base it expects.
	 */
	record Request(Kind kind, int ticket, int revision, String base) {
	}

	final ProjectPath path;
	// What the view keeps for the document; the revision and modified
	// state its draft was last kept at, as the journal acknowledged it,
	// and as last sent to it; why the last write failed, or null; and
	// the tickets of the timers that flush it.
	Object state;
	Document.Listener listener;
	int drafted;
	boolean draftModified;
	int sent;
	boolean sentModified;
	String failure;
	int timer;
	int limit;

	private final List<Runnable> waiting = new ArrayList<>();
	private Document document;
	private String base;
	private Request op;
	private int tickets;

	FileRecord(ProjectPath path) {
		this.path = path;
	}

	/** Return the document, or null when it is not in memory. */
	Document document() {
		return document;
	}

	/**
	 * Return the revision the text was last equal to in storage, or
	 * null when unknown or there was no file.
	 */
	String base() {
		return base;
	}

	/** Return the operation in flight, or null. */
	Request pending() {
		return op;
	}

	/**
	 * Run start once no operation is in flight, now when none is; start
	 * is expected to {@link #start} one.
	 */
	void whenIdle(Runnable start) {
		if (op == null) {
			start.run();
		} else {
			waiting.add(start);
		}
	}

	/** Start an operation of kind; none may be in flight. */
	Request start(Kind kind) {
		if (op != null) {
			throw new IllegalStateException(op.kind() + " in flight");
		}
		op = new Request(kind, ++tickets,
				document != null ? document.revision() : -1, base);
		return op;
	}

	/**
	 * End request if it is the current one and start what waited for
	 * it; return whether it was current.
	 */
	boolean end(Request request) {
		if (request != op) {
			return false;
		}
		op = null;
		while (op == null && !waiting.isEmpty()) {
			waiting.remove(0).run();
		}
		return true;
	}

	/**
	 * Take document, opened from storage at revision, or from a draft
	 * based on it, for request; return whether it was taken.
	 */
	boolean adoptOpened(Request request, Document document,
			String revision) {
		if (request != op || this.document != null) {
			return false;
		}
		this.document = document;
		base = revision;
		drafted = sent = document.revision();
		return true;
	}

	/**
	 * Give the document text, stored at revision, as one change that
	 * can be undone, keeping the selection where it can; only when the
	 * document is as it was when request was asked. Return whether it
	 * took the text.
	 */
	boolean adoptStored(Request request, Text text, String revision) {
		if (request != op || document == null ||
				document.revision() != request.revision()) {
			return false;
		}
		Document.Selection selection = document.selection();
		document.beginBatch();
		document.replace(0, document.text().length(), text);
		document.select(Math.min(selection.anchor(), text.length()),
				Math.min(selection.head(), text.length()));
		document.markSaved(document.state());
		base = revision;
		drafted = sent = document.revision();
		draftModified = sentModified = false;
		document.endBatch();
		return true;
	}

	/**
	 * Take that the text of state was written at revision for request;
	 * return whether it was current.
	 */
	boolean adoptSaved(Request request, int state, String revision) {
		if (request != op) {
			return false;
		}
		base = revision;
		if (document != null) {
			document.markSaved(state);
		}
		return true;
	}

	/** Forget the document and return it. */
	Document drop() {
		Document dropped = document;
		document = null;
		return dropped;
	}
}

package de.markusfisch.android.shadereditor.project;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import de.markusfisch.android.shadereditor.editor.Document;
import de.markusfisch.android.shadereditor.editor.Text;

/**
 * What the project knows of one file, on the main thread: its document
 * while it is in memory, the stored revision its text was last equal
 * to, the one operation on storage in flight for it, and how far its
 * journal holds the unsaved text. It does no I/O: an operation starts
 * as a {@link Request}, a write of the journal as a {@link Flush}, and
 * what storage gave is adopted only for the request still current, so
 * text and base change together, before the listeners of the document
 * hear.
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

	enum Write {
		SNAPSHOT, APPEND, REMOVE
	}

	/**
	 * What the journal of the file is to do: write text as a snapshot
	 * with header at revision to, append changes from revision from to
	 * to, or remove the journal; to also tells the writes apart.
	 */
	record Flush(Write write, Journal.Header header, Text text, int from,
			int to, List<Document.Change> changes) {
	}

	final ProjectPath path;
	// What the view keeps for the document; why the last write of the
	// journal failed, or null; and the tickets of the timers that flush
	// it.
	Object state;
	Document.Listener listener;
	String failure;
	int timer;
	int limit;
	// What the last check found in storage.
	FileTexts.Found found = FileTexts.Found.SAME;
	// Whether the text at revision runs, and the revision asked for.
	OpenProject.Runs runs = OpenProject.Runs.UNKNOWN;
	int runsRevision = -1;
	int runsAsked = -1;
	// Why storage is not at the base anymore while the text is unsaved,
	// else null, and the revision storage held then, null when none.
	String conflict;
	String seen;
	// Whether the user chose to keep the unsaved text over the base,
	// so only an explicit save writes it.
	boolean kept;
	// Whether the file has a journal, as the writes sent tell; why the
	// journal it has could not be read, else null.
	boolean journaled;
	String unreadable;

	private final List<Runnable> waiting = new ArrayList<>();
	private Document document;
	private String base;
	private Request op;
	private int tickets;
	// The revision the journal continues from, -1 when it starts again
	// with a snapshot; the last write sent and the last acknowledged;
	// the revision of the document as last sent; and its changes since.
	private int journal = -1;
	private int writes;
	private int acked;
	private int flushed;
	private List<Document.Change> changes = new ArrayList<>();

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
	 * Return whether the file has unsaved text to save: its document is
	 * modified, or, when not in memory, it has a journal.
	 */
	boolean edited() {
		return document != null ? document.isModified() : journaled;
	}

	/**
	 * Return whether the file counts as unsaved: it is edited, or it had
	 * unsaved text that could not be read.
	 */
	boolean unsaved() {
		return unreadable != null || edited();
	}

	/**
	 * Return whether the record holds anything to keep: a document, an
	 * operation, a journal or why one could not be read.
	 */
	boolean needed() {
		return document != null || op != null || journaled ||
				unreadable != null;
	}

	/**
	 * Return whether the journal acknowledged everything sent and holds
	 * the document as it is: its text when modified, nothing when not.
	 */
	boolean settled() {
		if (acked != writes || failure != null || document == null) {
			return acked == writes && failure == null;
		}
		return document.isModified()
				? journal >= 0 && document.revision() == flushed
				: !journaled;
	}

	/**
	 * Take what the journal header of a file not in memory tells: its
	 * base and whether the user kept the text over it.
	 */
	void recover(Journal.Header header) {
		base = header.base();
		kept = header.kept();
		journaled = true;
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
	 * Take document, opened from storage at revision, or from the
	 * journal at revision journal based on it, -1 for none, for
	 * request; return whether it was taken.
	 */
	boolean adoptOpened(Request request, Document document,
			String revision, int journal) {
		if (request != op || this.document != null) {
			return false;
		}
		this.document = document;
		base = revision;
		journaled |= journal >= 0;
		this.journal = journal;
		writes = acked = Math.max(journal, 0);
		flushed = document.revision();
		changes = new ArrayList<>();
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
		found = FileTexts.Found.SAME;
		conflict = null;
		kept = false;
		document.endBatch();
		return true;
	}

	/**
	 * Take what a check for request found in storage; return whether it
	 * was current.
	 */
	boolean adoptFound(Request request, FileTexts.Found found) {
		if (request != op) {
			return false;
		}
		this.found = found;
		return true;
	}

	/**
	 * Take that storage is not at the base of request anymore, for
	 * reason, holding revision seen, null for none; only while the base
	 * is the same. Return whether it was taken.
	 */
	boolean adoptConflict(Request request, String reason, String seen) {
		if (request != op || !Objects.equals(base, request.base())) {
			return false;
		}
		conflict = reason;
		this.seen = seen;
		return true;
	}

	/**
	 * Base the unsaved text on revision, null for none, as the user
	 * consented to overwrite it at the next explicit save.
	 */
	void consent(String revision) {
		base = revision;
		kept = true;
		conflict = null;
		seen = null;
		journal = -1;
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
		found = FileTexts.Found.SAME;
		conflict = null;
		kept = false;
		// The journal header names the new base.
		journal = -1;
		if (document != null) {
			document.markSaved(state);
		} else {
			// Its journal was the text written, and is gone with it.
			journaled = false;
		}
		return true;
	}

	/** Keep change of the document for the next append. */
	void collect(Document.Change change) {
		changes.add(change);
	}

	/**
	 * Return what the journal is to write to hold the text of the
	 * document, or null when it does or the document is not in memory;
	 * it counts as sent.
	 */
	Flush flush() {
		if (document == null) {
			return null;
		}
		int revision = document.revision();
		List<Document.Change> changed = changes;
		if (!document.isModified()) {
			changes = new ArrayList<>();
			flushed = revision;
			if (!journaled) {
				return null;
			}
			journaled = false;
			journal = -1;
			return new Flush(Write.REMOVE, null, null, -1, ++writes,
					List.of());
		} else if (journal >= 0 && revision == flushed) {
			return null;
		}
		changes = new ArrayList<>();
		flushed = revision;
		journaled = true;
		int from = journal;
		journal = ++writes;
		return from < 0
				? new Flush(Write.SNAPSHOT, new Journal.Header(path, base,
						kept, kept ? base : null), document.text(), -1, writes,
						List.of())
				: new Flush(Write.APPEND, null, null, from, writes, changed);
	}

	/**
	 * Take what the journal did with flush; return whether a snapshot
	 * is to be sent now.
	 */
	boolean acknowledge(Flush flush, Journal.Appended appended) {
		// A later write tells how the journal is.
		if (flush.to() != writes) {
			return false;
		} else if (appended == Journal.Appended.DONE) {
			acked = writes;
			failure = null;
			// A new snapshot took the place of one that was damaged.
			if (flush.write() == Write.SNAPSHOT) {
				unreadable = null;
			}
			return false;
		}
		journal = -1;
		if (flush.write() == Write.REMOVE) {
			journaled = true;
		} else if (appended == Journal.Appended.SNAPSHOT) {
			return true;
		}
		failure = "the journal cannot be written";
		return false;
	}

	/** Forget the document and return it. */
	Document drop() {
		Document dropped = document;
		document = null;
		return dropped;
	}
}

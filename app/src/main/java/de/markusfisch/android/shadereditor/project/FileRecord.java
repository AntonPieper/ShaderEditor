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
 * as a {@link Request}, a write of the journal as a {@link Flush} the
 * worker completes by itself, and
 * what storage gave is adopted only for the request still current, so
 * text and base change together, before the listeners of the document
 * hear.
 */
final class FileRecord {
	enum Kind {
		OPEN, RELOAD, SAVE, CHECK, MOVE
	}

	/**
	 * An operation on storage: its ticket, the document when it started,
	 * null when not in memory, with its revision and state then, and the
	 * base it expects.
	 */
	record Request(Kind kind, int ticket, Document document, int revision,
			int state, String base) {
	}

	/**
	 * What the journal of the file is to do, in one task of the worker:
	 * hold text at revision to, by appending changes when it is at
	 * revision from and by a snapshot with header otherwise, or, when
	 * header is null, go; to also tells the writes apart. A journal
	 * that goes with a history goes only once that history, of text,
	 * is written: it holds what a reload replaced, and the history is
	 * what brings that back from then on.
	 */
	record Flush(Journal.Header header, Text text, int from, int to,
			List<Document.Change> changes, Document.History history) {
		boolean removes() {
			return header == null;
		}
	}

	// Where the file is; a rename gives it another path.
	ProjectPath path;
	// What the view keeps for the document; why the last write of the
	// journal failed, or null; and the tickets of the timers that flush
	// it.
	Object state;
	Document.Listener listener;
	String failure;
	int timer;
	int limit;
	// What storage was last seen to hold, compared with the base, and
	// its revision then, null when none is known. An observation: only
	// taking the stored text, a save or the user's consent make it the
	// base.
	FileTexts.Found found = FileTexts.Found.SAME;
	String seen;
	// How many callers are about to be told that the document is there.
	int awaited;
	// Whether the text at revision runs, and the revision asked for.
	OpenProject.Runs runs = OpenProject.Runs.UNKNOWN;
	int runsRevision = -1;
	int runsAsked = -1;
	// Whether the user chose to keep the unsaved text over the base,
	// so only an explicit save writes it.
	boolean kept;
	// The revision and the selection of the document its history was
	// kept at.
	int historyAt;
	Document.Selection historySelection;
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
	// Whether the journal of the file, not in memory, is to go.
	private boolean retire;
	// While the file is being deleted, the revision of the document
	// whose text goes with it, -1 without a document; else KEPT.
	private static final int KEPT = Integer.MIN_VALUE;
	private int discarding = KEPT;
	// Whether the journal holds text a reload replaced, as its header
	// says; the state of the document that text was, which a history
	// must lead back to before the journal may go, -1 when the text is
	// the document's again after a restart; and the revision the
	// document was opened at.
	private boolean replaced;
	private int replacedState = -1;
	private int opened;

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

	/**
	 * Return the revision a save expects storage to hold: the base, or
	 * none for a file without unsaved text that storage was seen not to
	 * hold, which a save creates again.
	 */
	String expected() {
		return !edited() && found == FileTexts.Found.MISSING ? null : base;
	}

	/**
	 * Return what names the text the file has now for a deletion: the
	 * revision of its document, -1 when it is not in memory.
	 */
	int stamp() {
		return document != null ? document.revision() : -1;
	}

	/**
	 * The file is being deleted with the text it has now, when deleting:
	 * until that is over, this text is not written to the journal again,
	 * whoever asks; text typed from now on is.
	 */
	void discard(boolean deleting) {
		discarding = deleting ? stamp() : KEPT;
	}

	/**
	 * Return whether the file is being deleted and still has the text
	 * the deletion is for.
	 */
	boolean discarded() {
		return discarding != KEPT && discarding == stamp();
	}

	/**
	 * The stored file and the journal are gone, and the document has
	 * text typed since the deletion was asked for: it is unsaved text
	 * of a file that is not there, to be kept in a new journal.
	 */
	void orphan() {
		discarding = KEPT;
		journaled = false;
		journal = -1;
		retire = false;
		replaced = false;
		see(FileTexts.Found.MISSING, null);
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
	 * operation, a journal, a write of it on its way or why one could
	 * not be read.
	 */
	boolean needed() {
		return document != null || op != null || journaled ||
				unreadable != null || retire || acked != writes;
	}

	/**
	 * Return why storage does not hold what the unsaved text is based on
	 * anymore, as last seen, or null when it does, cannot be read or
	 * there is no unsaved text.
	 */
	String conflict() {
		if (!edited()) {
			return null;
		}
		return switch (found) {
			case SAME, UNAVAILABLE -> null;
			case MISSING -> base != null ? "the file was deleted" : null;
			default -> base == null
					? "the file exists"
					: "the file has changed";
		};
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
	 * Take what the journal header of a file not in memory tells, its
	 * base and whether the user kept the text over it, and what storage
	 * was found to hold, at revision.
	 */
	void recover(Journal.Header header, FileTexts.Found found,
			String revision) {
		base = header.base();
		kept = header.kept();
		journaled = true;
		replaced = header.replaced();
		see(found, revision);
	}

	/**
	 * Return whether the journal holds text a reload replaced. While
	 * the file has unsaved text, that is this text: it came back.
	 */
	boolean replaced() {
		return replaced;
	}

	/**
	 * Take the text a reload replaced as unsaved text like any other,
	 * as the user decided; the journal says so by its next write.
	 */
	void keepReplaced() {
		replaced = false;
		replacedState = -1;
		journal = -1;
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
		op = new Request(kind, ++tickets, document,
				document != null ? document.revision() : -1,
				document != null ? document.state() : -1, base);
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
	 * journal at revision journal based on it, -1 for none, whose text
	 * a reload replaced when replaced, for request; return whether it
	 * was taken.
	 */
	boolean adoptOpened(Request request, Document document,
			String revision, int journal, boolean replaced) {
		if (request != op || this.document != null) {
			return false;
		}
		this.document = document;
		base = revision;
		if (journal < 0) {
			see(FileTexts.Found.SAME, revision);
		}
		journaled |= journal >= 0;
		this.journal = journal;
		writes = acked = Math.max(journal, 0);
		flushed = opened = document.revision();
		changes = new ArrayList<>();
		this.replaced = replaced;
		replacedState = -1;
		return true;
	}

	/**
	 * Return the revision the document had when it was opened, before
	 * anyone was told of it.
	 */
	int opened() {
		return opened;
	}

	/**
	 * Give the document text, stored at revision, as one change that
	 * can be undone, keeping the selection where it can; only when the
	 * document is still asked, at revision at: as it was when the user
	 * asked for the stored text, or when a check started. Return
	 * whether it took the text.
	 */
	boolean adoptStored(Request request, Document asked, int at, Text text,
			String revision) {
		if (request != op || document == null || document != asked ||
				document.revision() != at) {
			return false;
		}
		Document.Selection selection = document.selection();
		// Unsaved text goes from the screen; its journal stays until a
		// history holds it. One that waits for that already goes on
		// waiting, whatever is taken from storage meanwhile.
		if (document.isModified() && journaled) {
			replaced = true;
			replacedState = document.state();
		}
		document.beginBatch();
		document.replace(0, document.text().length(), text);
		document.select(Math.min(selection.anchor(), text.length()),
				Math.min(selection.head(), text.length()));
		document.markSaved(document.state());
		base = revision;
		see(FileTexts.Found.SAME, revision);
		kept = false;
		document.endBatch();
		return true;
	}

	/**
	 * Take what request found storage to hold, at revision, null when
	 * none is known. It is seen, not taken: the base stays, and unsaved
	 * text on another base is in conflict. Return whether request was
	 * current.
	 */
	boolean observe(Request request, FileTexts.Found found,
			String revision) {
		if (request != op) {
			return false;
		}
		see(found, revision);
		return true;
	}

	// Storage holds found at revision, compared with the base as it is
	// now.
	private void see(FileTexts.Found found, String revision) {
		boolean text = found == FileTexts.Found.SAME ||
				found == FileTexts.Found.CHANGED;
		this.found = !text ? found : Objects.equals(revision, base)
				? FileTexts.Found.SAME
				: FileTexts.Found.CHANGED;
		seen = revision;
	}

	/**
	 * Base the unsaved text on revision, null for none, as the user
	 * consented to overwrite it at the next explicit save.
	 */
	void consent(String revision) {
		base = revision;
		kept = true;
		// No text the document had is what storage holds there.
		if (document != null) {
			document.markSaved(-1);
		}
		see(revision != null
				? FileTexts.Found.SAME
				: FileTexts.Found.MISSING, revision);
		journal = -1;
	}

	/**
	 * Take that storage holds the text request started with at revision:
	 * a save wrote it, or a check found exactly it there. Return whether
	 * request was current.
	 */
	boolean adoptSaved(Request request, String revision) {
		if (request != op || document != request.document()) {
			return false;
		}
		base = revision;
		see(FileTexts.Found.SAME, revision);
		kept = false;
		// The journal header names the new base.
		journal = -1;
		if (document != null) {
			// Unsaved text that is stored now needs no history; a
			// journal that waits for one beside a saved document does.
			if (document.isModified()) {
				replaced = false;
			}
			document.markSaved(request.state());
		} else if (journaled) {
			replaced = false;
			// Its journal is the text stored, and goes by the next flush.
			journaled = false;
			retire = true;
		}
		return true;
	}

	/** Keep change of the document for the next append. */
	void collect(Document.Change change) {
		changes.add(change);
	}

	/**
	 * Return what the journal is to write to hold the text of the
	 * document, or to go when the file has no unsaved text anymore, or
	 * null when it is as it should be; it counts as sent.
	 */
	Flush flush() {
		return flush(false);
	}

	/**
	 * Return what {@link #flush()} returns; at a fence, where the text
	 * is to be held whatever happens to the answers on their way, also
	 * when the last write sent is not acknowledged: it may have failed
	 * and the failure may never be heard, so what the journal is to be
	 * is sent again, the text whole.
	 */
	Flush flush(boolean fence) {
		if (discarded()) {
			return null;
		}
		boolean unsure = fence && acked != writes;
		if (document == null) {
			// The last write of a file not in memory is its removal.
			if (!retire && !unsure) {
				return null;
			}
			retire = false;
			return new Flush(null, null, -1, ++writes, List.of(), null);
		}
		int revision = document.revision();
		List<Document.Change> changed = changes;
		if (!document.isModified()) {
			changes = new ArrayList<>();
			flushed = revision;
			if (!journaled && !unsure) {
				return null;
			} else if (replaced && !undoable()) {
				// No history can hold what the journal holds.
				return null;
			}
			journaled = false;
			journal = -1;
			return new Flush(null, document.text(), -1, ++writes,
					List.of(), replaced ? document.history() : null);
		} else if (journal >= 0 && revision == flushed && !unsure) {
			return null;
		}
		changes = new ArrayList<>();
		flushed = revision;
		journaled = true;
		// The journal holds the text typed now: whole, so that its
		// header does not go on saying a reload replaced it.
		int from = unsure || replaced ? -1 : journal;
		replaced = false;
		replacedState = -1;
		journal = ++writes;
		return new Flush(new Journal.Header(path, base, kept,
				kept ? base : null), document.text(), from, writes, changed,
				null);
	}

	/**
	 * Take whether the journal did what flush asked. Only the answer to
	 * the last write sent tells how the journal is: the text counts as
	 * held once that write, which names its revision, is acknowledged.
	 * An earlier write that failed counts as a failure until then.
	 */
	void acknowledge(Flush flush, boolean done) {
		if (flush.to() != writes) {
			// Until the last one sent is answered, one that failed is
			// not forgotten: nobody is told the text is held meanwhile.
			if (!done && flush.history() == null) {
				failure = "the journal cannot be written";
			}
			return;
		} else if (done) {
			acked = writes;
			failure = null;
			// A new snapshot took the place of one that was damaged.
			if (!flush.removes()) {
				unreadable = null;
			} else if (document != null && !document.isModified()) {
				replaced = false;
			}
			return;
		}
		journal = -1;
		if (flush.removes()) {
			journaled = true;
		}
		// Unsaved text is at stake only where the document has some.
		if (flush.history() == null) {
			failure = "the journal cannot be written";
		}
	}

	// Return whether a history of the document, kept now, leads back
	// to the text the reload replaced.
	private boolean undoable() {
		if (document.historyChars() <= Histories.MAX_CHARS) {
			for (Document.History.Step step : document.history().undo()) {
				if (step.stateBefore() == replacedState) {
					return true;
				}
			}
		}
		return false;
	}

	/** Forget the document and return it. */
	Document drop() {
		Document dropped = document;
		document = null;
		return dropped;
	}
}

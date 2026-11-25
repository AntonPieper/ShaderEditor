package de.markusfisch.android.shadereditor.editor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import de.markusfisch.android.shadereditor.project.ProjectDiagnostic;
import de.markusfisch.android.shadereditor.project.ProjectDiagnostic.Severity;

// The lines of a document that diagnostics mark; a line marked twice shows the
// more severe. Diagnostics are installed only for the revision they were made
// for. A mark follows the start of its line through edits and is dropped when
// its line can no longer be told apart: its text removed, joined to text in
// front of it, or for an empty line, text removed at its start. A missing mark
// is better than one on the wrong line. Indenting or unindenting lines keeps
// their marks, since each line keeps its text after the leading white space.
// One line with errors may be open.
public final class LineMarks {
	private static final Severity[] SEVERITIES = Severity.values();

	private final Document document;
	private final Document.Listener listener = new Document.Listener() {
		@Override
		public void onChange(Document.Change change, Text before) {
			move(change, before);
		}
	};
	// Sorted by line, one entry per line.
	private int[] lines = new int[0];
	private Severity[] severities = new Severity[0];
	private List<String>[] messages = newLists(0);
	private int size;
	private int changes;
	private int open = -1;
	// A missing ';' that the first error's row tells; its line moves like a
	// mark, and hintChanged once that line is edited.
	private int hint = -1;
	private int hinted = -1;
	private String hintMessage;
	private boolean hintChanged;

	public LineMarks(Document document) {
		this.document = document;
		document.addListener(listener);
	}

	public void close() {
		document.removeListener(listener);
	}

	// Diagnostics without a line or past the end are left out. Returns false
	// and keeps the marks if the document is no longer at revision.
	public boolean set(int revision, List<ProjectDiagnostic> diagnostics) {
		if (revision != document.revision()) {
			return false;
		}
		int lineCount = document.text().lineCount();
		int n = diagnostics.size();
		// Line, severity and index in one value, so that sorting puts the
		// most severe mark of a line first and keeps the order of its
		// diagnostics.
		long[] keys = new long[n];
		int count = 0;
		for (int i = 0; i < n; ++i) {
			ProjectDiagnostic diagnostic = diagnostics.get(i);
			int line = diagnostic.line() - 1;
			if (line >= 0 && line < lineCount) {
				keys[count++] = ((long) line * SEVERITIES.length +
						diagnostic.severity().ordinal()) * n + i;
			}
		}
		Arrays.sort(keys, 0, count);
		lines = new int[count];
		severities = new Severity[count];
		messages = newLists(count);
		size = 0;
		++changes;
		for (int i = 0; i < count; ++i) {
			ProjectDiagnostic diagnostic = diagnostics.get((int) (keys[i] % n));
			add(diagnostic.line() - 1, diagnostic.severity(),
					diagnostic.severity() == Severity.ERROR
							? Collections.singletonList(diagnostic.message())
							: Collections.emptyList());
		}
		if (severity(open) != Severity.ERROR) {
			open = -1;
		}
		hint = hinted = -1;
		hintChanged = false;
		if (n > 1 && diagnostics.get(1).isMissingSemicolon()) {
			hinted = diagnostics.get(0).line() - 1;
			hint = diagnostics.get(1).line() - 1;
			hintMessage = diagnostics.get(1).message();
		}
		return true;
	}

	public int[] errorLines() {
		int count = 0;
		for (int i = 0; i < size; ++i) {
			if (severities[i] == Severity.ERROR) {
				++count;
			}
		}
		int[] errors = new int[count];
		count = 0;
		for (int i = 0; i < size; ++i) {
			if (severities[i] == Severity.ERROR) {
				errors[count++] = lines[i];
			}
		}
		return errors;
	}

	// In the order of their diagnostics; the same list until the marks are set
	// again.
	public List<String> messages(int line) {
		int i = Arrays.binarySearch(lines, 0, size, line);
		return i < 0 ? Collections.emptyList() : messages[i];
	}

	// The line now marked with message that was on line when the marks were
	// set, the nearest if several; -1 if none.
	public int lineOf(int line, String message) {
		int found = -1;
		for (int i = 0; i < size; ++i) {
			if (messages[i].contains(message) && (found < 0 ||
					Math.abs(lines[i] - line) < Math.abs(found - line))) {
				found = lines[i];
			}
		}
		return found;
	}

	public String hintMessage() {
		return hintMessage;
	}

	// The line of the missing ';' the row of line tells, or -1.
	public int hintFor(int line) {
		return line == hinted ? hint : -1;
	}

	// Whether the line of the missing ';' was edited since the marks were
	// set.
	public boolean hintChanged() {
		return hintChanged;
	}

	// -1 for none.
	public int open() {
		return open;
	}

	// Or close the open one for -1. Returns whether line is open now.
	public boolean open(int line) {
		open = severity(line) == Severity.ERROR ? line : -1;
		return open == line;
	}

	// Changes whenever a mark is set, moves or is dropped.
	public int changes() {
		return changes;
	}

	public Severity severity(int line) {
		int i = Arrays.binarySearch(lines, 0, size, line);
		return i < 0 ? null : severities[i];
	}

	private void move(Document.Change change, Text before) {
		int start = change.start();
		int first = before.lineOf(start);
		int last = first + change.removed().lineCount() - 1;
		hintChanged |= hint >= first && hint <= last;
		if (size == 0 || reindents(change)) {
			return;
		}
		int end = start + change.removed().length();
		// Where what follows the change starts, how far the lines below
		// it move, and whether text stands in front of it on its line.
		Text inserted = change.inserted();
		int after = first + inserted.lineCount() - 1;
		int shift = after - last;
		boolean joined = !inserted.line(inserted.lineCount() - 1).isEmpty() ||
				(after == first && start > before.lineStart(first));
		int count = size;
		int oldHint = hint;
		size = 0;
		boolean moved = false;
		int opened = open;
		int oldHinted = hinted;
		open = hinted = -1;
		for (int i = 0; i < count; ++i) {
			int line = lines[i];
			if (line > last) {
				line += shift;
			} else if (line >= first) {
				int lineStart = before.lineStart(line);
				int lineEnd = lineStart + before.line(line).length();
				if ((start < end && start <= lineStart && lineEnd <= end) ||
						(lineStart > start && joined)) {
					moved = true;
					continue;
				}
				line = lineStart < start ? first : after;
			}
			moved |= line != lines[i];
			if (lines[i] == opened) {
				open = line;
			}
			hint = lines[i] == oldHint ? line : hint;
			hinted = lines[i] == oldHinted ? line : hinted;
			add(line, severities[i], messages[i]);
		}
		if (moved || size != count) {
			++changes;
		}
	}

	// Whether each of several lines keeps its text after the leading white
	// space, as indenting and unindenting do.
	private static boolean reindents(Document.Change change) {
		Text removed = change.removed();
		Text inserted = change.inserted();
		int n = removed.lineCount();
		if (n < 2 || n != inserted.lineCount()) {
			return false;
		}
		for (int i = 0; i < n; ++i) {
			String line = removed.line(i);
			int start = 0;
			while (start < line.length() && (line.charAt(start) == ' ' ||
					line.charAt(start) == '\t')) {
				++start;
			}
			if (!inserted.line(i).endsWith(line.substring(start))) {
				return false;
			}
		}
		return true;
	}

	// Lines arrive in order; a line that is there already keeps the more
	// severe mark and the messages of both.
	private void add(int line, Severity severity, List<String> errors) {
		if (size > 0 && lines[size - 1] == line) {
			if (severity.ordinal() < severities[size - 1].ordinal()) {
				severities[size - 1] = severity;
			}
			if (!errors.isEmpty()) {
				List<String> both = new ArrayList<>(messages[size - 1]);
				both.addAll(errors);
				messages[size - 1] = Collections.unmodifiableList(both);
			}
			return;
		}
		lines[size] = line;
		severities[size] = severity;
		messages[size] = errors;
		++size;
	}

	@SuppressWarnings("unchecked")
	private static List<String>[] newLists(int size) {
		return (List<String>[]) new List[size];
	}
}

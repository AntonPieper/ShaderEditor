package de.markusfisch.android.shadereditor.editor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import de.markusfisch.android.shadereditor.project.ProjectDiagnostic;
import de.markusfisch.android.shadereditor.project.ProjectDiagnostic.Severity;

/**
 * The lines of a document that a diagnostic marks, with the severity to
 * show; a line marked twice shows the more severe. Diagnostics are
 * installed only for the revision they were made for: those of an older
 * text are left out, and the build of the newer text brings current
 * ones. Marks follow their lines through edits: a mark moves to the line
 * that holds the start of its line after the change, so lines inserted
 * or removed above it, or a line break typed at its start, move it. A
 * mark is dropped when its line can no longer be told apart: when a
 * change removes all of its line's text, joins its line to text in
 * front of it, or, for an empty line, removes text next to or around the
 * line's start. A missing mark is better than one on the wrong line. A
 * change that replaces several lines by as many, as indenting does,
 * keeps every mark where it is. A line keeps the messages of its errors
 * in the order of the diagnostics, and one line with errors may be
 * open, to show them; it follows its mark and closes when the mark is
 * dropped or a new set has no error on it.
 */
public final class LineMarks {
	private static final Severity[] SEVERITIES = Severity.values();

	private final Document document;
	// Sorted by line, one entry per line.
	private int[] lines = new int[0];
	private Severity[] severities = new Severity[0];
	private List<String>[] messages = newLists(0);
	private int size;
	private int changes;
	private int open = -1;

	/** Keep the marks of document. */
	public LineMarks(Document document) {
		this.document = document;
		document.addListener(new Document.Listener() {
			@Override
			public void onChange(Document.Change change, Text before) {
				move(change, before);
			}
		});
	}

	/**
	 * Mark the lines of diagnostics, which are for this document at
	 * revision, in place of the marks before. Diagnostics without a line
	 * or for a line past the end are left out. Return false and keep the
	 * marks if the document is no longer at revision.
	 */
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
		return true;
	}

	/** Return the lines with an error, in order. */
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

	/**
	 * Return the messages of the errors of line, in the order of their
	 * diagnostics; the same list until the marks are set again.
	 */
	public List<String> messages(int line) {
		int i = Arrays.binarySearch(lines, 0, size, line);
		return i < 0 ? Collections.emptyList() : messages[i];
	}

	/** Return the open line, or -1 for none. */
	public int open() {
		return open;
	}

	/**
	 * Open line if it has an error, or close the open one for -1;
	 * return whether line is open now.
	 */
	public boolean open(int line) {
		open = severity(line) == Severity.ERROR ? line : -1;
		return open == line;
	}

	/**
	 * Return a number that changes whenever a mark is set, moves or is
	 * dropped.
	 */
	public int changes() {
		return changes;
	}

	/** Return the severity line is marked with, or null. */
	public Severity severity(int line) {
		int i = Arrays.binarySearch(lines, 0, size, line);
		return i < 0 ? null : severities[i];
	}

	private void move(Document.Change change, Text before) {
		int removed = change.removed().lineCount();
		if (size == 0 || (removed > 1 &&
				removed == change.inserted().lineCount())) {
			return;
		}
		int start = change.start();
		int end = start + change.removed().length();
		int first = before.lineOf(start);
		int last = first + change.removed().lineCount() - 1;
		// Where what follows the change starts, how far the lines below
		// it move, and whether text stands in front of it on its line.
		Text inserted = change.inserted();
		int after = first + inserted.lineCount() - 1;
		int shift = after - last;
		boolean joined = !inserted.line(inserted.lineCount() - 1).isEmpty() ||
				(after == first && start > before.lineStart(first));
		int count = size;
		size = 0;
		boolean moved = false;
		int opened = open;
		open = -1;
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
			add(line, severities[i], messages[i]);
		}
		if (moved || size != count) {
			++changes;
		}
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

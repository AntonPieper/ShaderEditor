package de.markusfisch.android.shadereditor.editor;

import java.util.Arrays;
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
 * keeps every mark where it is.
 */
public final class LineMarks {
	private static final Severity[] SEVERITIES = Severity.values();

	private final Document document;
	// Sorted by line, one entry per line.
	private int[] lines = new int[0];
	private Severity[] severities = new Severity[0];
	private int size;

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
		// Line and severity in one value, so that sorting puts the most
		// severe mark of a line first.
		int[] keys = new int[diagnostics.size()];
		int count = 0;
		for (ProjectDiagnostic diagnostic : diagnostics) {
			int line = diagnostic.line() - 1;
			if (line >= 0 && line < lineCount) {
				keys[count++] = line * SEVERITIES.length +
						diagnostic.severity().ordinal();
			}
		}
		Arrays.sort(keys, 0, count);
		lines = new int[count];
		severities = new Severity[count];
		size = 0;
		for (int i = 0; i < count; ++i) {
			add(keys[i] / SEVERITIES.length,
					SEVERITIES[keys[i] % SEVERITIES.length]);
		}
		return true;
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
		for (int i = 0; i < count; ++i) {
			int line = lines[i];
			if (line > last) {
				line += shift;
			} else if (line >= first) {
				int lineStart = before.lineStart(line);
				int lineEnd = lineStart + before.line(line).length();
				if ((start < end && start <= lineStart && lineEnd <= end) ||
						(lineStart > start && joined)) {
					continue;
				}
				line = lineStart < start ? first : after;
			}
			add(line, severities[i]);
		}
	}

	// Lines arrive in order; a line that is there already keeps the more
	// severe mark.
	private void add(int line, Severity severity) {
		if (size > 0 && lines[size - 1] == line) {
			if (severity.ordinal() < severities[size - 1].ordinal()) {
				severities[size - 1] = severity;
			}
			return;
		}
		lines[size] = line;
		severities[size] = severity;
		++size;
	}
}

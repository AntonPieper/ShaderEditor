package de.markusfisch.android.shadereditor.editor;

import java.util.Arrays;
import java.util.List;

import de.markusfisch.android.shadereditor.project.ProjectDiagnostic;
import de.markusfisch.android.shadereditor.project.ProjectDiagnostic.Severity;

/**
 * The lines of a document that a diagnostic marks, with the severity to
 * show; a line marked twice shows the more severe. Marks follow their
 * lines through edits: a mark moves to the line that holds the start of
 * its line after the change, so lines inserted or removed above it, or a
 * line break typed at its start, move it, and a line joined to the one
 * before takes the mark along. A mark is dropped when a change removes
 * all of its line's text; a mark on an empty line, when a change removes
 * text next to or around the line's start.
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
	 * Mark the lines of diagnostics, which are for this document, in
	 * place of the marks before. Diagnostics without a line or for a
	 * line past the end are left out.
	 */
	public void set(List<ProjectDiagnostic> diagnostics) {
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
	}

	/** Return the severity line is marked with, or null. */
	public Severity severity(int line) {
		int i = Arrays.binarySearch(lines, 0, size, line);
		return i < 0 ? null : severities[i];
	}

	private void move(Document.Change change, Text before) {
		if (size == 0) {
			return;
		}
		int start = change.start();
		int end = start + change.removed().length();
		int first = before.lineOf(start);
		int last = first + change.removed().lineCount() - 1;
		// Where what follows the change starts, and how far the lines
		// below it move.
		int after = first + change.inserted().lineCount() - 1;
		int shift = after - last;
		int count = size;
		size = 0;
		for (int i = 0; i < count; ++i) {
			int line = lines[i];
			if (line > last) {
				line += shift;
			} else if (line >= first) {
				int lineStart = before.lineStart(line);
				int lineEnd = lineStart + before.line(line).length();
				if (start < end && start <= lineStart && lineEnd <= end) {
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

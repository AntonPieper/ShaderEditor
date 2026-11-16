package de.markusfisch.android.shadereditor.activity.managers;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import de.markusfisch.android.shadereditor.R;
import de.markusfisch.android.shadereditor.editor.Document;
import de.markusfisch.android.shadereditor.editor.Text;
import de.markusfisch.android.shadereditor.project.HostCompletion;
import de.markusfisch.android.shadereditor.project.HostDirectives;
import de.markusfisch.android.shadereditor.project.OpenProject;
import de.markusfisch.android.shadereditor.project.ProjectFiles;
import de.markusfisch.android.shadereditor.project.ProjectPath;

/**
 * Changes the lines of the running shader that name a renamed file, as
 * its last build knew them, and nothing else. Each file changes in its
 * document, as one step of the rename: a file not in memory is loaded
 * first, and its change is unsaved text like any other, saved as the
 * project saves. A line that no longer names the file is left as it is.
 */
final class RenameReferences {
	/**
	 * A rename and the lines it changed, one action: Undo in any of
	 * those files asks it, and it takes back all of itself or nothing.
	 */
	abstract static class Rename implements Document.Link {
		// The documents changed, with their files when they changed.
		final Map<Document, ProjectPath> changed = new LinkedHashMap<>();
	}

	private RenameReferences() {
	}

	/**
	 * Return what the rename dialog's check box says of references, or
	 * null when there are none.
	 */
	@Nullable
	static CharSequence sentence(@NonNull Context context,
			@NonNull List<HostDirectives.Reference> references) {
		if (references.isEmpty()) {
			return null;
		}
		List<String> lines = new ArrayList<>();
		for (HostDirectives.Reference reference : references) {
			lines.add(context.getString(R.string.rename_also_line,
					reference.line(), reference.file().getFileName()));
		}
		return context.getString(R.string.rename_also,
				String.join(", ", lines));
	}

	/**
	 * Make references, lines that named from, name to; from was renamed
	 * to to already.
	 */
	static void change(@NonNull OpenProject project,
			@NonNull List<HostDirectives.Reference> references,
			@NonNull ProjectPath from, @NonNull ProjectPath to,
			@NonNull Rename rename) {
		Map<ProjectPath, List<Integer>> byFile = new LinkedHashMap<>();
		for (HostDirectives.Reference reference : references) {
			// A file that names itself was renamed with it.
			ProjectPath file = reference.file().equals(from)
					? to
					: reference.file();
			List<Integer> lines = byFile.get(file);
			if (lines == null) {
				lines = new ArrayList<>();
				byFile.put(file, lines);
			}
			lines.add(reference.line());
		}
		for (Map.Entry<ProjectPath, List<Integer>> entry :
				byFile.entrySet()) {
			ProjectPath file = entry.getKey();
			project.load(file, ProjectFiles.UNKNOWN, problem -> {
				Document document = problem == null
						? project.document(file)
						: null;
				if (document != null && change(document, file,
						entry.getValue(), from, to, rename) > 0) {
					rename.changed.put(document, file);
				}
			});
		}
	}

	/**
	 * Make the lines, from 1, of document, the text of file, that name
	 * from name to, as one step, of link when it is not null; return
	 * how many changed.
	 */
	static int change(@NonNull Document document, @NonNull ProjectPath file,
			@NonNull List<Integer> lines, @NonNull ProjectPath from,
			@NonNull ProjectPath to, @Nullable Document.Link link) {
		int changed = 0;
		document.beginBatch();
		for (int line : lines) {
			Text text = document.text();
			if (line < 1 || line > text.lineCount()) {
				continue;
			}
			HostCompletion.Choice choice = HostCompletion.retarget(
					text.line(line - 1), file, from, to);
			if (choice != null) {
				int start = text.lineStart(line - 1);
				document.replace(start + choice.start(),
						start + choice.end(), Text.of(choice.text()));
				++changed;
			}
		}
		if (link != null) {
			document.link(link);
		}
		document.endBatch();
		return changed;
	}
}

package de.markusfisch.android.shadereditor.fragment;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

import de.markusfisch.android.shadereditor.R;
import de.markusfisch.android.shadereditor.editor.Document;
import de.markusfisch.android.shadereditor.editor.Text;
import de.markusfisch.android.shadereditor.project.HostCompletion;
import de.markusfisch.android.shadereditor.widget.CodeView;

/**
 * The fixes of the error row: for a file a line names that is not
 * there, Use the nearest name in its folder and Create it; then what
 * else offers a fix.
 */
final class FileFixes implements CodeView.RowFix {
	/** What the fixes are of. */
	interface Host {
		/** Return the files around the file shown, or null. */
		@Nullable
		FileLinks links();

		/** Return the document shown. */
		@NonNull
		Document document();
	}

	@NonNull
	private final Context context;
	@NonNull
	private final Host host;
	@NonNull
	private final CodeView.RowFix other;

	FileFixes(@NonNull Context context, @NonNull Host host,
			@NonNull CodeView.RowFix other) {
		this.context = context;
		this.host = host;
		this.other = other;
	}

	@NonNull
	@Override
	public List<String> labels(int line, @NonNull List<String> messages) {
		List<String> labels = new ArrayList<>();
		HostCompletion.Missing missing = missing(line, messages);
		if (missing != null && missing.near() != null) {
			labels.add(context.getString(R.string.fix_use, missing.near()));
		}
		if (missing != null && missing.creatable()) {
			labels.add(context.getString(R.string.fix_create));
		}
		labels.addAll(other.labels(line, messages));
		return labels;
	}

	@Override
	public void apply(int line, @NonNull List<String> messages, int index) {
		HostCompletion.Missing missing = missing(line, messages);
		FileLinks links = host.links();
		if (missing != null && missing.near() != null && index-- == 0) {
			Document document = host.document();
			int start = document.text().lineStart(line);
			HostCompletion.Choice use = missing.use();
			document.replace(start + use.start(), start + use.end(),
					Text.of(use.text()));
		} else if (missing != null && missing.creatable() &&
				index-- == 0) {
			if (links != null) {
				links.create(missing.path(),
						missing.kind() == HostCompletion.Kind.INCLUDE);
			}
		} else {
			other.apply(line, messages, index);
		}
	}

	@NonNull
	@Override
	public List<String> shown(int line, @NonNull List<String> messages) {
		FileLinks links = host.links();
		if (links == null) {
			return messages;
		}
		List<String> shown = new ArrayList<>(messages.size());
		String text = host.document().text().line(line);
		for (String message : messages) {
			// The line above names the file, the toolbar the folder.
			shown.add(HostCompletion.missing(text, links.file(), message,
					links) != null
					? context.getString(R.string.file_not_in_folder)
					: message);
		}
		return shown;
	}

	@Nullable
	private HostCompletion.Missing missing(int line,
			@NonNull List<String> messages) {
		FileLinks links = host.links();
		if (links == null) {
			return null;
		}
		String text = host.document().text().line(line);
		for (String message : messages) {
			HostCompletion.Missing missing = HostCompletion.missing(text,
					links.file(), message, links);
			if (missing != null) {
				return missing;
			}
		}
		return null;
	}
}

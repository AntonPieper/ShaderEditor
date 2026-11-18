package de.markusfisch.android.shadereditor.fragment;

import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import de.markusfisch.android.shadereditor.app.ShaderEditorApp;
import de.markusfisch.android.shadereditor.editor.Document;
import de.markusfisch.android.shadereditor.editor.Text;
import de.markusfisch.android.shadereditor.editor.WordPlaces;
import de.markusfisch.android.shadereditor.project.ProjectPath;
import de.markusfisch.android.shadereditor.syntax.GlslKnowledge;
import de.markusfisch.android.shadereditor.widget.UsesSheet;

/**
 * Uses: every place a word is written in the folder of the file shown,
 * in a sheet. The places are found off the main thread in the texts of
 * the moment the sheet is shown. After a tap on a place, the strip's
 * key leads back to where Uses was asked and shows the sheet again,
 * found again.
 */
final class WordUses {
	interface Host {
		/** The files around the file shown, or null outside a folder. */
		@Nullable
		FileLinks links();

		@NonNull
		Document document();

		/**
		 * Go to line, from 1, of file, a path of the project, or of the
		 * file shown for null; the key back leads to where the caret
		 * was, then runs back.
		 */
		void jump(@Nullable String file, int line, @NonNull Runnable back);

		/** The code takes the focus again, and the keyboard if it was up. */
		void refocus(boolean keyboard);
	}

	private static final Executor FINDER = Executors.newSingleThreadExecutor();

	private final View view;
	private final Host host;
	// Counts the requests: the answer to an older one is dropped.
	private int asked;
	// The sheet of the word asked about last, the word, where it is
	// declared, null for the file shown, and the document it was asked
	// in, which the key back returns to.
	@Nullable
	private UsesSheet sheet;
	@NonNull
	private String word = "";
	@Nullable
	private String declared;
	@Nullable
	private Document asking;
	// Shows the sheet again once asking is shown, or null.
	@Nullable
	private Runnable back;

	WordUses(@NonNull View view, @NonNull Host host) {
		this.view = view;
		this.host = host;
	}

	boolean offered() {
		return host.links() != null;
	}

	/**
	 * Show the places of word, declared in file, a path of the project,
	 * or in the file shown for null; keyboard tells whether the keyboard
	 * comes back when the sheet goes.
	 */
	void show(@NonNull String word, @Nullable String file, boolean keyboard) {
		this.word = word;
		declared = file;
		asking = host.document();
		back = null;
		UsesSheet shown = new UsesSheet(view.getContext(),
				ShaderEditorApp.preferences.getFont(), this::go);
		shown.setOnDismiss(() -> view.post(() -> host.refocus(keyboard)));
		sheet = shown;
		find();
	}

	/** The editor shows document now. */
	void shown(@NonNull Document document) {
		Runnable again = back;
		if (again != null && document == asking) {
			back = null;
			// Once the file links know the file shown too.
			view.post(again);
		}
	}

	// Finds the places of the word and lists them in the sheet.
	private void find() {
		FileLinks links = host.links();
		UsesSheet shown = sheet;
		if (links == null || shown == null) {
			return;
		}
		Document document = host.document();
		String file = links.file().toString();
		Text text = document.text();
		int offset = document.selection().start();
		String word = this.word;
		String declared = this.declared != null ? this.declared : file;
		int ask = ++asked;
		links.folderTexts(read -> FINDER.execute(() -> {
			Map<String, Text> texts = new LinkedHashMap<>();
			Map<String, List<String>> names = new LinkedHashMap<>();
			for (Map.Entry<ProjectPath, Text> entry :
					read.texts().entrySet()) {
				String path = entry.getKey().toString();
				texts.put(path, entry.getValue());
				List<String> named = new ArrayList<>();
				for (ProjectPath to : read.names().get(entry.getKey())) {
					named.add(to.toString());
				}
				names.put(path, named);
			}
			// The shown file as the caret's offset belongs to it.
			texts.put(file, text);
			List<WordPlaces.Place> places = new ArrayList<>();
			boolean glued = false;
			for (Map.Entry<String, Text> entry : texts.entrySet()) {
				places.addAll(WordPlaces.in(entry.getKey(),
						entry.getValue(), word));
				glued |= WordPlaces.glues(entry.getValue());
			}
			WordPlaces.Function function = WordPlaces.functionOf(
					GlslKnowledge.of(text.toString()), offset, word);
			UsesSheet.Content content = new UsesSheet.Content(word, file,
					WordPlaces.group(places, file, declared, function,
							WordPlaces.connected(names, file)),
					read.unread(), glued);
			view.post(() -> {
				if (ask == asked && view.isAttachedToWindow()) {
					shown.show(content);
				}
			});
		}));
	}

	// Goes to place; the key back shows the sheet again.
	private void go(@NonNull WordPlaces.Place place) {
		FileLinks links = host.links();
		UsesSheet was = sheet;
		if (links == null) {
			return;
		}
		host.jump(place.file().equals(links.file().toString())
				? null
				: place.file(), place.line() + 1, () -> {
			if (sheet == was) {
				back = this::find;
				shown(host.document());
			}
		});
	}
}

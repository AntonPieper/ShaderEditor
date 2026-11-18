package de.markusfisch.android.shadereditor.fragment;

import android.content.Context;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.snackbar.Snackbar;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import de.markusfisch.android.shadereditor.R;
import de.markusfisch.android.shadereditor.app.ShaderEditorApp;
import de.markusfisch.android.shadereditor.editor.Document;
import de.markusfisch.android.shadereditor.editor.Text;
import de.markusfisch.android.shadereditor.editor.WordPlaces;
import de.markusfisch.android.shadereditor.project.ProjectParseResult;
import de.markusfisch.android.shadereditor.project.ProjectPath;
import de.markusfisch.android.shadereditor.syntax.GlslKnowledge;
import de.markusfisch.android.shadereditor.syntax.GlslWords;
import de.markusfisch.android.shadereditor.syntax.Style;
import de.markusfisch.android.shadereditor.widget.UsesSheet;

/**
 * Uses: every place a word is written in the folder of the file shown,
 * in a sheet, and Rename in it. The places are found off the main thread
 * in the texts of the moment the sheet is shown. After a tap on a place,
 * the strip's key leads back to where Uses was asked and shows the
 * sheet again, found again. A rename changes the places ticked in every
 * file in one turn, one step in each file, all of which one Undo takes
 * back; it changes nothing where a text is no longer the one its places
 * were found in, and finds them again instead.
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

		/** Run what runs from the texts now, if edits run it. */
		void run();
	}

	// The places of a word, the texts they were found in by file, and
	// the scope a rename stays in: the function of a local, else the
	// files connected to the shown one.
	private record Found(@NonNull UsesSheet.Content content,
			@NonNull Map<String, Text> texts,
			@Nullable WordPlaces.Function function,
			@NonNull Set<String> connected) {
	}

	/**
	 * A rename of from, and the word and the word left that were listed
	 * before it: Undo in any file it changed takes back all of it.
	 */
	private final class Rename implements Document.Link {
		private final String from;
		private final String word;
		@Nullable
		private final String left;
		private final Map<Document, ProjectPath> changed =
				new LinkedHashMap<>();

		private Rename(@NonNull String from, @NonNull String word,
				@Nullable String left) {
			this.from = from;
			this.word = word;
			this.left = left;
		}

		@Override
		public void undo(Document document) {
			FileLinks links = host.links();
			Document stuck = null;
			for (Map.Entry<Document, ProjectPath> entry :
					changed.entrySet()) {
				if (links == null ||
						links.document(entry.getValue()) != entry.getKey()) {
					stuck = entry.getKey();
					break;
				}
			}
			if (stuck == null) {
				stuck = takeBack(changed.keySet(), this);
			}
			if (stuck != null) {
				tell(context().getString(R.string.rename_not_undone, from,
						changed.get(stuck).getFileName()));
				return;
			}
			tell(context().getString(R.string.rename_undone, from));
			if (sheet != null && sheet.isShowing()) {
				WordUses.this.word = word;
				WordUses.this.left = left;
				find();
			}
		}
	}

	private static final Executor FINDER = Executors.newSingleThreadExecutor();
	// How long a rename that broke the build is told.
	private static final int BROKE_MILLIS = 10_000;

	private final View view;
	private final Host host;
	// Counts the requests: the answer to an older one is dropped.
	private int asked;
	// The sheet of the word asked about last, the word, the word a
	// rename left in places, else null, where the word is declared,
	// null for the file shown, and the document it was asked in, which
	// the key back returns to.
	@Nullable
	private UsesSheet sheet;
	@NonNull
	private String word = "";
	@Nullable
	private String left;
	@Nullable
	private String declared;
	@Nullable
	private Document asking;
	private boolean renamable;
	// What the sheet lists, or null before it does.
	@Nullable
	private Found found;
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
	 * comes back when the sheet goes; renamable whether Rename is
	 * offered.
	 */
	void show(@NonNull String word, @Nullable String file, boolean keyboard,
			boolean renamable) {
		this.word = word;
		left = null;
		declared = file;
		this.renamable = renamable;
		asking = host.document();
		found = null;
		back = null;
		UsesSheet shown = new UsesSheet(view.getContext(),
				ShaderEditorApp.preferences.getFont(), new UsesSheet.Actions() {
			@Override
			public void go(@NonNull WordPlaces.Place place) {
				WordUses.this.go(place);
			}

			@Nullable
			@Override
			public String problem(@NonNull String name) {
				int problem = WordUses.problem(name, WordUses.this.word);
				return problem < 0 ? "" : problem == 0 ? null
						: context().getString(problem, name);
			}

			@Override
			public void used(@NonNull String name,
					@NonNull UsesSheet.Warn warn) {
				WordUses.this.used(name, warn);
			}

			@Override
			public void rename(@NonNull String name,
					@NonNull Set<WordPlaces.Place> places) {
				apply(WordUses.this.word, name, places);
			}

			@Override
			public void renameLeft(@NonNull WordPlaces.Place place) {
				if (left != null) {
					apply(left, WordUses.this.word,
							Collections.singleton(place));
				}
			}
		});
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

	/**
	 * Return the text of why name cannot replace word: -1 when it is
	 * no other name, 0 when it can.
	 */
	static int problem(@NonNull String name, @NonNull String word) {
		if (name.isEmpty() || name.equals(word)) {
			return -1;
		}
		for (int i = 0, length = name.length(); i < length; ++i) {
			char c = name.charAt(i);
			if (!(c == '_' || (c >= 'a' && c <= 'z') ||
					(c >= 'A' && c <= 'Z') ||
					(i > 0 && c >= '0' && c <= '9'))) {
				return R.string.name_not_glsl;
			}
		}
		if (GlslWords.style(name, 0, name.length()) != Style.IDENTIFIER) {
			return R.string.name_word_of_glsl;
		} else if (name.startsWith("gl_")) {
			return R.string.name_gl;
		} else if (name.contains("__")) {
			return R.string.name_reserved;
		}
		return 0;
	}

	// Finds the places of the word, and of the word left in the scope of
	// the rename that left them, and lists them in the sheet.
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
		String left = this.left;
		String declared = this.declared != null ? this.declared : file;
		boolean renamable = this.renamable;
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
			List<WordPlaces.Place> places = places(texts, word);
			boolean glued = false;
			for (Text one : texts.values()) {
				glued |= WordPlaces.glues(one);
			}
			GlslKnowledge known = GlslKnowledge.of(text.toString());
			WordPlaces.Function function = WordPlaces.functionOf(known,
					offset, word);
			if (function == null && left != null) {
				function = WordPlaces.functionOf(known, offset, left);
			}
			Set<String> connected = WordPlaces.connected(names, file);
			List<WordPlaces.Group> groups = WordPlaces.group(places, file,
					declared, function, connected);
			List<WordPlaces.Place> leftOver = left != null
					? WordPlaces.inScope(places(texts, left), file, function,
					connected)
					: List.of();
			if (!leftOver.isEmpty()) {
				groups.add(0, new WordPlaces.Group(
						WordPlaces.Group.Kind.LEFT, left, leftOver));
			}
			Found now = new Found(new UsesSheet.Content(word, file, groups,
					read.unread(), glued, renamable), texts, function,
					connected);
			view.post(() -> {
				if (ask == asked && view.isAttachedToWindow()) {
					found = now;
					shown.show(now.content());
				}
			});
		}));
	}

	@NonNull
	private static List<WordPlaces.Place> places(
			@NonNull Map<String, Text> texts, @NonNull String word) {
		List<WordPlaces.Place> places = new ArrayList<>();
		for (Map.Entry<String, Text> entry : texts.entrySet()) {
			places.addAll(WordPlaces.in(entry.getKey(), entry.getValue(),
					word));
		}
		return places;
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

	// Tells warn where name is written in the scope of the rename.
	private void used(@NonNull String name, @NonNull UsesSheet.Warn warn) {
		Found now = found;
		if (now == null) {
			return;
		}
		String shown = now.content().shown();
		FINDER.execute(() -> {
			WordPlaces.Place first = WordPlaces.firstInScope(
					places(now.texts(), name), shown, now.function(),
					now.connected());
			Context context = context();
			String where = first == null
					? null
					: now.function() != null
					? context.getString(R.string.name_used_function, name,
					first.line() + 1)
					: first.file().equals(shown)
					? context.getString(R.string.name_used_here, name,
					first.line() + 1)
					: context.getString(R.string.name_used_in, name,
					first.file(), first.line() + 1);
			view.post(() -> warn.warn(where));
		});
	}

	// Writes to in places of from, found in what is listed; then lists
	// the places of to, with what was left of from. Files not in memory
	// are loaded first; nothing changes when one cannot be, or when a
	// text is no longer the one its places were found in.
	private void apply(@NonNull String from, @NonNull String to,
			@NonNull Set<WordPlaces.Place> places) {
		FileLinks links = host.links();
		Found was = found;
		if (links == null || was == null || places.isEmpty()) {
			return;
		}
		Map<String, List<WordPlaces.Place>> byFile = new LinkedHashMap<>();
		List<ProjectPath> paths = new ArrayList<>();
		for (WordPlaces.Place place : places) {
			List<WordPlaces.Place> inFile = byFile.get(place.file());
			if (inFile == null) {
				ProjectParseResult<ProjectPath> path = ProjectPath.parse(
						place.file());
				if (!path.isSuccess()) {
					return;
				}
				inFile = new ArrayList<>();
				byFile.put(place.file(), inFile);
				paths.add(path.value());
			}
			inFile.add(place);
		}
		links.load(paths, problem -> {
			if (found != was) {
				return;
			} else if (problem != null) {
				tell(context().getString(R.string.uses_not_renamed, problem));
				return;
			}
			List<Document> documents = new ArrayList<>();
			List<Text> texts = new ArrayList<>();
			for (ProjectPath path : paths) {
				Document document = links.document(path);
				Text text = was.texts().get(path.toString());
				if (document == null || text == null) {
					tell(context().getString(R.string.uses_not_renamed,
							path));
					return;
				}
				documents.add(document);
				texts.add(text);
			}
			if (!unchanged(documents, texts)) {
				find();
				tell(context().getString(R.string.uses_changed));
				return;
			}
			boolean built = links.builtWithoutErrors();
			Rename rename = new Rename(from, word, left);
			for (int i = 0; i < paths.size(); ++i) {
				write(documents.get(i), byFile.get(paths.get(i).toString()),
						from, to, rename);
				rename.changed.put(documents.get(i), paths.get(i));
			}
			renamed(rename, places.size(), false);
			// What built before and fails now says so, with Undo.
			if (built) {
				links.afterBuild(builds -> {
					if (!builds && rename.changed.keySet().iterator().next()
							.endsWith(rename)) {
						renamed(rename, places.size(), true);
					}
				});
			}
			host.run();
			word = to;
			left = from;
			find();
		});
	}

	/** Return whether each of documents has the text of texts there. */
	static boolean unchanged(@NonNull List<Document> documents,
			@NonNull List<Text> texts) {
		for (int i = 0; i < documents.size(); ++i) {
			Text now = documents.get(i).text();
			Text then = texts.get(i);
			if (now != then && !now.toString().equals(then.toString())) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Write to in places of from in document, as one step of link,
	 * from the last place on, so that the offsets before stay.
	 */
	static void write(@NonNull Document document,
			@NonNull List<WordPlaces.Place> places, @NonNull String from,
			@NonNull String to, @NonNull Document.Link link) {
		List<WordPlaces.Place> sorted = new ArrayList<>(places);
		Collections.sort(sorted, (a, b) -> Integer.compare(b.start(),
				a.start()));
		document.beginBatch();
		for (WordPlaces.Place place : sorted) {
			document.replace(place.start(), place.start() + from.length(),
					Text.of(to));
		}
		document.link(link);
		document.endBatch();
	}

	/**
	 * Take back the step of link in every one of documents, or in none
	 * when one has another step after it: return that one, else null.
	 */
	@Nullable
	static Document takeBack(@NonNull Collection<Document> documents,
			@NonNull Document.Link link) {
		for (Document document : documents) {
			if (!document.endsWith(link)) {
				return document;
			}
		}
		for (Document document : documents) {
			document.undoLinked(link);
		}
		return null;
	}

	// Says that count places were renamed, and that the shader no
	// longer builds when broke, with Undo.
	private void renamed(@NonNull Rename rename, int count, boolean broke) {
		Snackbar.make(messageParent(), context().getResources()
						.getQuantityString(broke
								? R.plurals.uses_renamed_broke
								: R.plurals.uses_renamed, count, count),
				broke ? BROKE_MILLIS : Snackbar.LENGTH_LONG).setAction(
				R.string.undo, v ->
				rename.undo(rename.changed.keySet().iterator().next())).show();
	}

	private void tell(@NonNull String message) {
		Snackbar.make(messageParent(), message, Snackbar.LENGTH_LONG).show();
	}

	@NonNull
	private View messageParent() {
		return sheet != null && sheet.isShowing() ? sheet.getView() : view;
	}

	@NonNull
	private Context context() {
		return view.getContext();
	}
}

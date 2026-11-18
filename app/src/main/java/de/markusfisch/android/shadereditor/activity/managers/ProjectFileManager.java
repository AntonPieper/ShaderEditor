package de.markusfisch.android.shadereditor.activity.managers;

import android.content.Context;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewStub;
import android.view.ViewTreeObserver;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.markusfisch.android.shadereditor.R;
import de.markusfisch.android.shadereditor.app.ShaderEditorApp;
import de.markusfisch.android.shadereditor.editor.Document;
import de.markusfisch.android.shadereditor.editor.Text;
import de.markusfisch.android.shadereditor.fragment.EditorFragment;
import de.markusfisch.android.shadereditor.navigation.Moves;
import de.markusfisch.android.shadereditor.navigation.Row;
import de.markusfisch.android.shadereditor.navigation.Tap;
import de.markusfisch.android.shadereditor.opengl.Tuning;
import de.markusfisch.android.shadereditor.opengl.UniformTable;
import de.markusfisch.android.shadereditor.project.FileTexts;
import de.markusfisch.android.shadereditor.project.Library;
import de.markusfisch.android.shadereditor.project.OpenProject;
import de.markusfisch.android.shadereditor.project.Preparation;
import de.markusfisch.android.shadereditor.project.ProjectDiagnostic;
import de.markusfisch.android.shadereditor.project.ProjectFiles;
import de.markusfisch.android.shadereditor.project.ProjectPath;
import de.markusfisch.android.shadereditor.tuning.TunedPlace;
import de.markusfisch.android.shadereditor.tuning.Tuner;
import de.markusfisch.android.shadereditor.tuning.TuningSession;
import de.markusfisch.android.shadereditor.tuning.Window;

/**
 * Opens files of folders in the editor and keeps the one open: its
 * project stays open while it is, the editor shows the project's
 * document of it, and its unsaved text is journaled when the app goes
 * to the background and checked against storage when it returns. A
 * tap on a file runs it when it can run and what runs does not use it
 * already. Call it on the main thread.
 */
public final class ProjectFileManager {
	public interface Listener extends ProjectFile.Listener {
		/** file is in the editor now, in place of what was there. */
		void onShown(@NonNull ProjectFile file);

		/**
		 * The file in the editor was deleted, or the one open before
		 * the screen was made again is gone: show something else.
		 */
		void onGone();

		/**
		 * Files of the folder opened before with key were renamed or
		 * deleted.
		 */
		void onMoved(@NonNull String key);

		/**
		 * A file was asked for while the old editor is in use, which
		 * cannot show it: say so, with an action that runs turnOn.
		 */
		void onNeedsNewEditor(@NonNull Runnable turnOn);

		/**
		 * The number tuned last stopped being tuned: as asked, because
		 * the text was about to change or run, or because it could
		 * not be tuned (errors say why; empty otherwise).
		 */
		default void onTuningEnded(@NonNull List<ProjectDiagnostic> errors) {
		}
	}

	/** The engine a tuning session builds its revisions on. */
	public interface Engine {
		/**
		 * Run preparation as a revision of picture, with tunings; heard
		 * hears whether it draws, if not null.
		 */
		void continuePlan(@NonNull Preparation preparation, float quality,
				@NonNull Object picture, @NonNull List<Tuning> tunings,
				@Nullable Tuning.Listener heard);

		/** Show the value of the tuning that runs with the next frame. */
		void applyTuning();
	}

	// How long the finger rests before a constant is built again.
	private static final long REST_MILLIS = 150;

	/** Runs what a project runs. */
	public interface Runner {
		/**
		 * Run preparation at quality; null when nothing runs, and then
		 * report nothingRuns.
		 */
		void run(@Nullable Preparation preparation,
				@NonNull List<ProjectDiagnostic> nothingRuns, float quality);
	}


	// A file asked for while the old editor was in use, opened once the
	// screen is made again with the new one; a shader of the library is
	// in the editor until then.
	private record Asked(@NonNull String key, @NonNull ProjectPath path,
			boolean single, boolean tapped) {
	}

	// The quality of the file that was in the editor before the screen was
	// made again, for that file once it shows.
	private record Kept(@NonNull String key, @NonNull ProjectPath path,
			float quality) {
	}

	@NonNull
	private final AppCompatActivity activity;
	@NonNull
	private final EditorFragment editor;
	@NonNull
	private final ProjectHolds holds;
	@NonNull
	private final Listener listener;
	@NonNull
	private final Runner runner;
	@NonNull
	private final Engine engine;
	// The tuning sessions of the file in the editor, one at a time.
	@NonNull
	private final Tuner tuner;
	private final ExecutorService tuning =
			Executors.newSingleThreadExecutor();
	private boolean tuningEdits;
	private final android.os.Handler rests =
			new android.os.Handler(android.os.Looper.getMainLooper());
	@Nullable
	private Runnable resting;
	// The last session's document and states, while what runs is one of
	// them.
	@Nullable
	private Document tunedDocument;
	// The project of the session that began last.
	@Nullable
	private OpenProject tunedProject;
	@Nullable
	private Set<Integer> tunedStates;
	@Nullable
	private ProjectFile file;
	// The bar that says what changed outside the app, once made.
	@Nullable
	private View bar;
	// The sentence of the bar a screen reader was sent to last, while the
	// bar is shown.
	@Nullable
	private String pointed;
	// The build of what runs asked for last, of which project and file.
	@Nullable
	private OpenProject.Build build;
	@Nullable
	private OpenProject built;
	@Nullable
	private ProjectPath builtEntry;
	// The line to go to once the file asked for last is shown, or 0.
	private int line;
	// Whether the file asked for last was asked for from a line, as a
	// jump: shown where its caret is, the landing is said as a jump's.
	private boolean landing;
	@Nullable
	private String key;
	@Nullable
	private Asked askedFor;
	@Nullable
	private Kept keptQuality;
	// The file tapped last until it is known whether it can run.
	@Nullable
	private ProjectPath deciding;
	// Counts the files asked for; an answer for one before is void.
	private int asked;
	// The number of the last rename or deletion of the project heard of.
	private int heard;
	// Whether the last build delivered is of paths renamed since.
	private boolean stale;
	private boolean destroyed;

	public ProjectFileManager(@NonNull AppCompatActivity activity,
			@NonNull EditorFragment editor, @NonNull ProjectHolds holds,
			@NonNull Listener listener, @NonNull Runner runner,
			@NonNull Engine engine) {
		this.activity = activity;
		this.runner = runner;
		this.engine = engine;
		tuner = new Tuner(new TuningHost(),
				ContextCompat.getMainExecutor(activity), tuning);
		this.editor = editor;
		this.holds = holds;
		this.listener = listener;
	}

	/** Return the file in the editor, or null. */
	@Nullable
	public ProjectFile file() {
		return file;
	}

	/** Return the key of the folder of the file in the editor, or null. */
	@Nullable
	public String key() {
		return file != null ? key : null;
	}

	/**
	 * Open the file at path of the folder or single file opened before
	 * with key, as a tap on it does; return whether the drawer closes.
	 * Only the new editor can show it; with the old one the file is not
	 * opened, and a message offers to turn the new one on and open it.
	 */
	public boolean open(@NonNull String key, @NonNull ProjectPath path,
			boolean single) {
		return open(key, path, single, true);
	}

	/**
	 * The library, whose root files are shaders, is known. A screen
	 * made again may show its file before that: what depends on the
	 * library is settled for that file now.
	 */
	public void onLibrary() {
		ProjectFile shown = file;
		if (shown != null && holds.projects().isLibrary(key)) {
			editor.setShaderToy(Library.convertsShaderToy(shown.getPath()));
		}
	}

	/**
	 * Show the file at path of the folder, or single file, opened
	 * before with key as the file it showed last: what runs there runs,
	 * whatever the file is.
	 */
	public void show(@NonNull String key, @NonNull ProjectPath path,
			boolean single) {
		open(key, path, single, false);
	}

	/**
	 * Show the file at path of the project of the file in the editor at
	 * line, from 1, as an error does that the list leads to; what runs
	 * stays.
	 */
	public void openAt(@NonNull ProjectPath path, int line) {
		ProjectFile shown = file;
		if (shown == null) {
			return;
		} else if (path.equals(shown.getPath())) {
			editor.navigateToLine(line);
			return;
		}
		if (open(key, path, shown.isSingle(), false)) {
			this.line = line;
			landing = true;
		}
	}

	private boolean open(@NonNull String key, @NonNull ProjectPath path,
			boolean single, boolean tapped) {
		if (!ShaderEditorApp.preferences.useNewEditor()) {
			Runnable turnOn = () -> {
				ShaderEditorApp.preferences.setUseNewEditor(true);
				// The screen is made again with the new editor, and
				// opens the file from its saved state.
				askedFor = new Asked(key, path, single, tapped);
				activity.recreate();
			};
			if (tapped) {
				listener.onNeedsNewEditor(turnOn);
				// A tap closes the drawer all the same, so that this can
				// be read.
				return true;
			}
			// The file that was in the editor before the screen was made
			// again: what comes for a lost one comes in its place, after
			// the screen is made, and then the message.
			ContextCompat.getMainExecutor(activity).execute(() -> {
				if (!destroyed) {
					listener.onGone();
					listener.onNeedsNewEditor(turnOn);
				}
			});
			return false;
		}
		int ask = ++asked;
		line = 0;
		landing = false;
		holds.open(key, opened -> {
			OpenProject project = opened.project();
			if (project == null) {
				failed(ask, tapped, path, opened.problem());
				return;
			} else if (ask != asked || destroyed) {
				holds.release(project);
				return;
			}
			project.load(path, ProjectFiles.UNKNOWN, problem -> {
				Document document = problem == null && ask == asked &&
						!destroyed
						? project.show(path)
						: null;
				if (document == null) {
					holds.release(project);
					failed(ask, tapped, path, problem);
					// A file tapped that cannot be opened may be gone:
					// its folder is listed as storage has it now.
					if (tapped && problem != null) {
						listener.onMoved(key);
					}
					return;
				}
				show(key, project, path, document, single, tapped);
			});
		});
		return true;
	}

	private void failed(int ask, boolean tapped, @NonNull ProjectPath path,
			@Nullable String problem) {
		if (ask != asked || destroyed || problem == null) {
			return;
		}
		// A reason that names the file says enough by itself.
		listener.onFailed(problem.startsWith(path + " ")
				? problem
				: activity.getString(R.string.file_cannot_open,
						path.toString(), problem));
		if (!tapped) {
			listener.onGone();
		}
	}

	private void show(@NonNull String key, @NonNull OpenProject project,
			@NonNull ProjectPath path, @NonNull Document document,
			boolean single, boolean tapped) {
		leave();
		this.key = key;
		ProjectFile shown = new ProjectFile(activity, project, path, single,
				listener);
		Kept kept = keptQuality;
		keptQuality = null;
		if (kept != null && kept.key().equals(key) &&
				kept.path().equals(path)) {
			shown.setQuality(kept.quality());
		}
		file = shown;
		heard = last(project.events(0));
		project.setListener(ContextCompat.getMainExecutor(activity),
				this::changed);
		project.setBeforeReading(() -> endTuning(true));
		editor.showDocument(document, project.syntax(path),
				project.scope(path), project.editorState(path),
				holds.projects().isLibrary(key) &&
						Library.convertsShaderToy(path));
		listener.onShown(shown);
		showOutside();
		if (line > 0) {
			editor.navigateToLine(line);
			line = 0;
		} else if (landing) {
			editor.landed();
		}
		landing = false;
		deciding = tapped ? path : null;
		decide();
		if (project != built ||
				!Objects.equals(project.running(), builtEntry)) {
			run();
		}
	}

	/**
	 * Run what the project of the file in the editor runs, prepared
	 * from the text its documents have now.
	 */
	public void run() {
		run(false);
	}

	/**
	 * Run after an edit; between states the last tuning session made,
	 * the build goes on from the picture, as the session's builds did.
	 */
	public void runEdit() {
		run(true);
	}

	private void run(boolean edit) {
		ProjectFile shown = file;
		if (shown == null) {
			return;
		}
		// What runs replaces the session's build.
		endTuning(true);
		OpenProject project = shown.getProject();
		Document document = project.document(shown.getPath());
		boolean tuned = document != null && document == tunedDocument &&
				tunedStates != null && tunedStates.contains(document.state());
		if (!tuned) {
			tunedDocument = null;
			tunedStates = null;
		}
		boolean goesOn = edit && tuned;
		built = project;
		builtEntry = project.running();
		project.prepare(UniformTable.builtins(), prepared -> {
			ProjectFile now = file;
			if (now == null || now.getProject() != project) {
				return;
			}
			build = prepared;
			stale = false;
			Preparation preparation = prepared.preparation();
			if (goesOn && preparation != null) {
				engine.continuePlan(preparation, now.getQuality(),
						Arrays.asList(key, project.running()), List.of(),
						null);
				return;
			}
			// Nothing runs, as for a folder without a shader: a picture
			// or errors left from before are not this project's.
			runner.run(preparation, prepared.nothingRuns(),
					now.getQuality());
		});
	}

	/** Keep what the build run last reported, by file. */
	public void onDiagnostics(@NonNull List<ProjectDiagnostic> diagnostics) {
		if (file != null && build != null &&
				file.getProject() == built) {
			file.getProject().setDiagnostics(build, diagnostics);
		}
	}

	/** Let the file in the editor go, as another item is shown there. */
	public void leave() {
		ProjectFile left = file;
		if (left == null) {
			return;
		}
		endTuning(true);
		tunedDocument = null;
		tunedStates = null;
		file = null;
		deciding = null;
		stale = false;
		build = null;
		built = null;
		builtEntry = null;
		OpenProject project = left.getProject();
		project.setEditorState(left.getPath(), editor.getDocumentState());
		project.setListener(null, null);
		project.setBeforeReading(null);
		holds.release(project);
		showOutside();
	}

	/** Look for changes made elsewhere, when the app is in front again. */
	public void onStart() {
		ProjectFile checked = file;
		if (checked == null) {
			return;
		}
		// A refresh may read a file again.
		endTuning(true);
		OpenProject project = checked.getProject();
		project.refresh(reread -> {
			if (file != checked) {
				return;
			}
			// The file in the editor says so in its bar; the others are
			// named once.
			List<String> others = new ArrayList<>();
			for (ProjectPath conflict : project.conflicts().keySet()) {
				if (!conflict.equals(checked.getPath())) {
					others.add(conflict.toString());
				}
			}
			if (!others.isEmpty()) {
				Collections.sort(others);
				listener.onFailed(activity.getString(
						R.string.conflict_others,
						TextUtils.join(", ", others)));
			}
			String unprotected = project.unprotected();
			if (unprotected != null) {
				listener.onFailed(unprotected);
			}
			run();
			listener.onChanged();
		});
	}

	/** Keep everything typed, when the app goes to the background. */
	public void onStop() {
		// Back in front, a bar that is still shown is pointed out again.
		pointed = null;
		if (file != null) {
			file.getProject().flush(() -> {
			});
		}
	}

	/**
	 * Return what of the file in the editor is kept when the screen is
	 * made again, or of the file that was asked for while the old
	 * editor is in use; null when there is neither.
	 */
	@Nullable
	public OpenState state() {
		ProjectFile saved = file;
		if (saved == null) {
			Asked asked = askedFor;
			return asked != null
					? OpenState.asked(asked.key(), asked.path().toString(),
							asked.single(), asked.tapped())
					: null;
		}
		saved.getProject().setEditorState(saved.getPath(),
				editor.getDocumentState());
		if (key == null) {
			return null;
		}
		// Outside the library nothing else keeps the quality.
		OpenState state = OpenState.file(key, saved.getPath().toString(),
				saved.isSingle(), saved.getQuality());
		// A tap that has not found out yet whether the file runs.
		return saved.getPath().equals(deciding) ? state.undecided() : state;
	}

	/**
	 * Open the file that was in the editor before the screen was made
	 * again, or the one asked for when the new editor was turned on.
	 */
	public void restore(@NonNull OpenState state) {
		var parsed = state.isFile() && state.path() != null
				? ProjectPath.parse(state.path())
				: null;
		String restored = state.key();
		if (parsed == null || !parsed.isSuccess() || restored == null) {
			listener.onGone();
			return;
		}
		if (state.kind() == OpenState.Kind.FILE) {
			keptQuality = new Kept(restored, parsed.value(),
					state.quality());
		}
		open(restored, parsed.value(), state.single(), state.tapped());
	}

	public void destroy() {
		leave();
		tuner.destroy();
		tuning.shutdown();
		destroyed = true;
	}

	/**
	 * Return the number at the caret of the file in the editor that can
	 * be tuned, or null.
	 */
	@Nullable
	public TunedPlace numberAtCaret() {
		ProjectFile shown = file;
		Document document = shown != null
				? shown.getProject().document(shown.getPath())
				: null;
		int caret = document != null ? document.selection().head() : 0;
		return document != null
				? Window.around(document.text(), caret, editor::lineState)
						.place(caret)
				: null;
	}

	/**
	 * Return whether a number is being tuned; not while its session
	 * changes the text.
	 */
	public boolean isTuning() {
		return tuner.open() != null && !tuningEdits;
	}

	/**
	 * Tune place, from {@link #numberAtCaret()}; null when it cannot
	 * start.
	 */
	@Nullable
	public TuningSession tune(@NonNull TunedPlace place,
			@NonNull TuningSession.Listener tuned) {
		ProjectFile shown = file;
		Document document = shown != null
				? shown.getProject().document(shown.getPath())
				: null;
		if (document == null || tuner.open() != null) {
			return null;
		}
		tunedProject = shown.getProject();
		return tuner.begin(document, shown.getPath(), place, tuned);
	}

	/** End tuning as {@link TuningSession#end} does. */
	public void endTuning(boolean keep) {
		TuningSession session = tuner.open();
		if (session == null || tuningEdits) {
			return;
		}
		session.end(keep);
		rests.removeCallbacks(resting);
		resting = null;
		ProjectFile shown = file;
		tunedDocument = shown != null
				? shown.getProject().document(shown.getPath())
				: null;
		tunedStates = session.states();
		listener.onTuningEnded(List.of());
	}

	// The project and the engine as a tuning session needs them.
	private final class TuningHost implements TuningSession.Host {
		@Nullable
		@Override
		public Text text(@NonNull ProjectPath path) {
			OpenProject project = project();
			Document document = project != null
					? project.document(path)
					: null;
			return document != null ? document.text() : null;
		}

		@Override
		public void prepare(
				@NonNull List<OpenProject.Replacement> replacements,
				@NonNull FileTexts.Callback<Preparation> done) {
			OpenProject project = project();
			if (project != null) {
				project.prepare(UniformTable.builtins(), replacements,
						prepared -> done.done(prepared.preparation()));
			}
		}

		// Even of a text that changed meanwhile: it retires the session's.
		@Override
		public void prepare(@NonNull FileTexts.Callback<Preparation> done) {
			OpenProject project = project();
			if (project != null) {
				project.prepareAlways(UniformTable.builtins(),
						prepared -> done.done(prepared.preparation()));
			}
		}

		@Override
		public void run(@NonNull Preparation preparation,
				@NonNull List<Tuning> tunings,
				@Nullable Tuning.Listener heard) {
			// Another project shown now runs its own picture.
			ProjectFile shown = file;
			if (shown != null && shown.getProject() == tunedProject) {
				engine.continuePlan(preparation, shown.getQuality(),
						Arrays.asList(key, shown.getProject().running()),
						tunings, heard);
			}
		}

		@Override
		public void applyTuning() {
			engine.applyTuning();
		}

		@Override
		public void edit(@NonNull Runnable edit) {
			editor.skipNextRun();
			tuningEdits = true;
			try {
				edit.run();
			} finally {
				tuningEdits = false;
			}
		}

		@Override
		public int lineState(int line) {
			return editor.lineState(line);
		}

		@Override
		public void rest(@NonNull Runnable build) {
			rests.removeCallbacks(resting);
			resting = build;
			rests.postDelayed(build, REST_MILLIS);
		}

		@Nullable
		private OpenProject project() {
			ProjectFile shown = file;
			return shown != null ? shown.getProject() : null;
		}
	}

	private void changed() {
		// Files were renamed or deleted: what is tuned may be gone. A
		// build or a keystroke tells this listener too, and does not;
		// any change drops a build being prepared.
		ProjectFile shown = file;
		if (isTuning() && shown != null &&
				!shown.getProject().events(heard).isEmpty()) {
			endTuning(true);
		}
		TuningSession session = tuner.open();
		if (session != null) {
			session.projectChanged();
		}
		follow();
		decide();
		// Another file was chosen to run, or found to run; or a rename
		// made the last build stale: a build asked for while the project
		// still settles what runs is not delivered, so it is asked for
		// again until one is.
		if (file != null && file.getProject() == built && (stale ||
				!Objects.equals(built.running(), builtEntry))) {
			run();
		}
		listener.onChanged();
		showOutside();
	}

	// The bar under the toolbar says what storage holds in place of the
	// file in the editor, as its project knows it, and offers what can
	// be done; it is made the first time there is something to say.
	private void showOutside() {
		ProjectFile shown = file;
		ProjectFile.Outside outside = shown != null ? shown.outside() : null;
		if (bar == null) {
			if (outside == null) {
				return;
			}
			bar = ((ViewStub) activity.findViewById(
					R.id.conflict_bar_stub)).inflate();
		}
		bar.setVisibility(outside != null ? View.VISIBLE : View.GONE);
		if (outside == null) {
			pointed = null;
			return;
		}
		ProjectFile.Outside.Kind kind = outside.kind();
		boolean unsaved = shown.isModified();
		TextView text = bar.findViewById(R.id.conflict_text);
		String sentence = activity.getString(switch (kind) {
			case CHANGED -> R.string.conflict_changed;
			case DELETED -> R.string.conflict_deleted;
			case GONE -> R.string.conflict_gone;
			case UNREADABLE -> unsaved
					? R.string.conflict_unreadable_unsaved
					: R.string.conflict_unreadable;
			case JOURNAL, JOURNAL_UNREAD -> R.string.conflict_journal;
		}, shown.getTitle());
		text.setText(sentence);
		if (!sentence.equals(pointed)) {
			pointed = sentence;
			point(text);
		}
		boolean decides = kind == ProjectFile.Outside.Kind.CHANGED;
		// The bar goes with its button: a screen reader hears that the
		// file's text is shown now, or that the edits stay.
		View said = bar;
		offer(R.id.conflict_reload, decides, () -> {
			Runnable back = focusBack();
			shown.resolve(outside, OpenProject.Choice.RELOAD, () -> {
				back.run();
				said.announceForAccessibility(activity.getString(
						R.string.a11y_reloaded, shown.getTitle()));
			});
		});
		offer(R.id.conflict_keep, decides ||
						kind == ProjectFile.Outside.Kind.DELETED, () -> {
			Runnable back = focusBack();
			shown.resolve(outside, OpenProject.Choice.KEEP, () -> {
				back.run();
				said.announceForAccessibility(activity.getString(
						R.string.a11y_kept, shown.getTitle()));
			});
		});
		offer(R.id.conflict_discard, kind == ProjectFile.Outside.Kind.JOURNAL,
				() -> discard(shown));
		offer(R.id.conflict_retry,
				kind == ProjectFile.Outside.Kind.JOURNAL_UNREAD,
				() -> reread(shown));
		offer(R.id.conflict_copy, unsaved &&
						shown.can(OpenItem.Action.SAVE_COPY),
				shown::saveCopy);
		offer(R.id.conflict_save, kind == ProjectFile.Outside.Kind.GONE &&
						shown.can(OpenItem.Action.SAVE),
				() -> shown.save(true));
	}

	// A button of the bar with the input focus, as TalkBack 17 gives it
	// the button it clicks, goes with the bar, and the keys typed next
	// would go nowhere: once the bar is done, the code has it back.
	@NonNull
	private Runnable focusBack() {
		boolean focused = bar.hasFocus();
		return () -> {
			if (focused || bar.hasFocus()) {
				editor.focusCode();
			}
		};
	}

	// A screen reader goes to the bar's sentence when it appears or says
	// something else, as the app comes back to the front. A live region
	// was not heard then whenever the code had the input focus: the screen
	// reader's reading of the code cut it short, and the same sentence
	// again within half a minute it drops. The input focus stays where it
	// is; the window must have the focus first, or the screen reader puts
	// its own focus back over this one.
	private static void point(@NonNull View view) {
		AccessibilityManager manager = (AccessibilityManager) view
				.getContext().getSystemService(Context.ACCESSIBILITY_SERVICE);
		if (manager == null || !manager.isTouchExplorationEnabled()) {
			return;
		}
		Runnable go = () -> {
			if (view.isShown()) {
				view.performAccessibilityAction(
						AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null);
			}
		};
		if (view.hasWindowFocus()) {
			view.post(go);
			return;
		}
		view.getViewTreeObserver().addOnWindowFocusChangeListener(
				new ViewTreeObserver.OnWindowFocusChangeListener() {
					@Override
					public void onWindowFocusChanged(boolean hasFocus) {
						if (hasFocus) {
							view.getViewTreeObserver()
									.removeOnWindowFocusChangeListener(this);
							view.post(go);
						}
					}
				});
	}

	// Unsaved text that is given up is gone for good: only after a yes.
	private void discard(@NonNull ProjectFile shown) {
		new MaterialAlertDialogBuilder(activity)
				.setTitle(activity.getString(R.string.conflict_discard_title,
						shown.getTitle()))
				.setMessage(R.string.conflict_discard_message)
				.setPositiveButton(R.string.conflict_discard,
						(dialog, which) -> shown.discard())
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}

	// Read unsaved text the device had no memory for once more: the
	// file is opened again, with that text when it could be read now.
	private void reread(@NonNull ProjectFile shown) {
		String key = this.key;
		ProjectPath path = shown.getPath();
		shown.getProject().reread(path, problem -> {
			if (file == shown && !destroyed) {
				show(key, path, shown.isSingle());
			}
		});
	}

	// Whatever a button of the bar does may give the file another text:
	// a number being tuned is written first, like any typing, so that
	// it is part of what the action is for.
	private void offer(int id, boolean offered, @NonNull Runnable action) {
		View button = bar.findViewById(id);
		button.setVisibility(offered ? View.VISIBLE : View.GONE);
		button.setOnClickListener(v -> {
			endTuning(true);
			action.run();
		});
	}

	// The file in the editor follows a rename of it or of a folder it
	// is in, with the same document; the last build names old paths,
	// so it is built again until one is delivered. Deleted, it closes,
	// unless it was typed in after the deletion was asked for: then
	// its document stays, as unsaved text of a file that is not there.
	private void follow() {
		ProjectFile shown = file;
		if (shown == null) {
			return;
		}
		OpenProject project = shown.getProject();
		List<OpenProject.Event> events = project.events(heard);
		if (events.isEmpty()) {
			return;
		}
		// Some were missed: everything is looked at again.
		boolean gone = events.get(0).number() > heard + 1;
		heard = last(events);
		String path = shown.getPath().toString();
		boolean renamed = false;
		for (OpenProject.Event event : events) {
			if (event.change() instanceof OpenProject.Renamed rename) {
				renamed = true;
				path = Moves.renamed(path, rename.from().toString(),
						rename.to().toString());
			} else if (event.change() instanceof
					OpenProject.Deleted deleted) {
				gone |= Moves.within(path, deleted.path().toString());
			}
		}
		ProjectPath now = ProjectPath.parse(path).value();
		String moved = key;
		if (gone && project.document(now) == null) {
			leave();
			listener.onGone();
		} else if (!now.equals(shown.getPath())) {
			if (deciding != null && deciding.equals(shown.getPath())) {
				deciding = now;
			}
			file = new ProjectFile(activity, project, now, shown.isSingle(),
					listener);
			file.setQuality(shown.getQuality());
			listener.onShown(file);
		}
		stale |= renamed;
		if (moved != null) {
			listener.onMoved(moved);
		}
	}

	private static int last(@NonNull List<OpenProject.Event> events) {
		return events.isEmpty()
				? 0
				: events.get(events.size() - 1).number();
	}

	// A tap runs a file that can run unless what runs uses it, once it
	// is known whether its text defines main.
	private void decide() {
		ProjectFile tapped = file;
		if (tapped == null || deciding == null ||
				!deciding.equals(tapped.getPath())) {
			return;
		}
		OpenProject project = tapped.getProject();
		OpenProject.Runs runs = project.runs(deciding);
		if (runs == OpenProject.Runs.UNKNOWN) {
			return;
		}
		ProjectPath path = deciding;
		deciding = null;
		boolean inUse = path.equals(project.running()) || project.uses(path);
		if (Tap.of(runs == OpenProject.Runs.YES
				? Row.Kind.SHADER
				: Row.Kind.TEXT, inUse) == Tap.OPEN_AND_RUN) {
			project.runThisFile(path);
		}
	}
}

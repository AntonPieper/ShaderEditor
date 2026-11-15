package de.markusfisch.android.shadereditor.opengl;

import static de.markusfisch.android.shadereditor.opengl.TestProjects.lines;
import static de.markusfisch.android.shadereditor.opengl.TestProjects.path;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import de.markusfisch.android.shadereditor.project.BuiltinNames;
import de.markusfisch.android.shadereditor.project.FileTexts;
import de.markusfisch.android.shadereditor.project.OpenProject;
import de.markusfisch.android.shadereditor.project.Preparation;
import de.markusfisch.android.shadereditor.project.ProjectDiagnostic;
import de.markusfisch.android.shadereditor.project.ProjectFiles;
import de.markusfisch.android.shadereditor.project.ProjectLimits;
import de.markusfisch.android.shadereditor.project.ProjectPath;
import de.markusfisch.android.shadereditor.tuning.TuningSession;

// A session tunes one number: one build to enter, values without a
// build, one build of the text to leave; a number that must stay a
// constant is built with each new text instead. The documents never
// hold the uniform.
public class TuningSessionTest {
	private static final String PRECISION = lines(
			"#ifdef GL_FRAGMENT_PRECISION_HIGH",
			"precision highp float;",
			"#else",
			"precision mediump float;",
			"#endif");
	private static final String SHADER = lines(PRECISION,
			"uniform float time;",
			"void main(void) {",
			"	float s = 0.0;",
			"	for (int i = 0; i < 3; ++i) {",
			"		s += 0.3 * sin(time);",
			"	}",
			"	gl_FragColor = vec4(s);",
			"}");

	private final Map<String, String> texts = new LinkedHashMap<>();
	private final List<Run> runs = new ArrayList<>();
	private final List<Runnable> preparing = new ArrayList<>();
	private final List<String> heard = new ArrayList<>();
	private final List<String> written = new ArrayList<>();
	private int applied;

	private record Run(@NonNull String text, boolean temporary,
			@Nullable Tuning tuning) {
	}

	@Test
	public void valuesShowWithoutABuildAndTheTextIsBuiltAtTheEnd() {
		var session = begin("0.3", Tuning.Type.FLOAT);
		assertEquals(1, runs.size());
		var tuning = runs.get(0).tuning;
		assertNotNull(tuning);
		assertEquals("se_tune0", tuning.name());
		assertEquals(0.3, tuning.value(), 0);
		assertTrue(runs.get(0).temporary);
		assertTrue(runs.get(0).text.contains("s += se_tune0 * sin(time);"));
		tuning.live();
		assertEquals(List.of("live"), heard);
		assertEquals(TuningSession.State.LIVE, session.state());

		for (int i = 1; i <= 100; ++i) {
			session.set(0.3 + i * 0.01);
		}
		assertEquals(100, applied);
		assertEquals(1.3, tuning.value(), 1e-9);
		assertEquals(1, runs.size());

		// Revert: the text as it was, built once more.
		session.end(null);
		assertEquals(2, runs.size());
		assertEquals(SHADER, runs.get(1).text);
		assertFalse(runs.get(1).temporary);
		assertNull(runs.get(1).tuning);
		assertEquals(SHADER, texts.get("main.frag"));
		assertEquals(List.of(), written);
		// Nothing after the end.
		session.set(2.0);
		assertEquals(100, applied);
	}

	@Test
	public void doneWritesTheNumberOnceAndBuildsThatText() {
		var session = begin("0.3", Tuning.Type.FLOAT);
		runs.get(0).tuning.live();
		session.set(0.42);
		session.end("0.42");
		assertEquals(List.of("main.frag:0.42"), written);
		String done = SHADER.replace("0.3 *", "0.42 *");
		assertEquals(done, texts.get("main.frag"));
		assertEquals(done, runs.get(1).text);
		assertFalse(runs.get(1).temporary);
	}

	@Test
	public void theUniformIsNeverWritten() {
		var session = begin("0.3", Tuning.Type.FLOAT);
		assertThrows(IllegalArgumentException.class,
				() -> session.end("se_tune0"));
		assertEquals(List.of(), written);
	}

	// What is set while the build is made is what it starts with.
	@Test
	public void aValueSetBeforeTheBuildIsItsFirst() {
		int at = SHADER.indexOf("0.3");
		var session = TuningSession.begin(new Host(true), Runnable::run,
				path("main.frag"), at, at + 3, Tuning.Type.FLOAT, listener());
		session.set(0.9);
		settle();
		assertEquals(0.9, runs.get(0).tuning.value(), 0);
		assertEquals(0, applied);
	}

	// The compiler refused: the build before draws on, and each new
	// text is built, going on from the picture.
	@Test
	public void aRefusedBuildMustRebuildEachText() {
		var session = begin("0.3", Tuning.Type.FLOAT);
		var errors = List.of(ProjectDiagnostic.error("must be constant"));
		runs.get(0).tuning.refused(errors);
		assertEquals(List.of("must rebuild [must be constant]"), heard);
		assertEquals(TuningSession.State.MUST_REBUILD, session.state());
		session.set(0.5);
		assertEquals(0, applied);
		session.rebuild("0.5");
		assertEquals(2, runs.size());
		assertNull(runs.get(1).tuning);
		assertTrue(runs.get(1).temporary);
		assertTrue(runs.get(1).text.contains("s += 0.5 * sin(time);"));
		assertEquals(SHADER, texts.get("main.frag"));
	}

	// The tokens say so: nothing is built until a text is asked for.
	@Test
	public void aLoopCountOfGlslEs100MustRebuildWithoutAFirstBuild() {
		var session = begin("3", Tuning.Type.INT);
		assertEquals(List.of(), runs);
		assertEquals(1, heard.size());
		assertTrue(heard.get(0), heard.get(0).startsWith(
				"must rebuild [main.frag:10: GLSL needs a constant here " +
						"(a for loop of GLSL ES 1.00)"));
		session.rebuild("5");
		assertEquals(1, runs.size());
		assertTrue(runs.get(0).text.contains("i < 5;"));
		// Revert builds the text, as a build of the session ran.
		session.end(null);
		assertEquals(SHADER, runs.get(1).text);
	}

	// While a text is prepared, only the newest asked for follows.
	@Test
	public void rebuildsFollowOneAnother() {
		int at = SHADER.indexOf("3;");
		var session = TuningSession.begin(new Host(true), Runnable::run,
				path("main.frag"), at, at + 1, Tuning.Type.INT, listener());
		settle();
		session.rebuild("4");
		session.rebuild("5");
		session.rebuild("6");
		settle();
		assertEquals(2, runs.size());
		assertTrue(runs.get(0).text.contains("i < 4;"));
		assertTrue(runs.get(1).text.contains("i < 6;"));
	}

	@Test
	public void whatCannotBeTunedFailsAndEndsWithoutABuild() {
		var session = begin("0.0", Tuning.Type.INT);
		assertEquals(List.of("failed [main.frag:9: a float, not a int]"),
				heard);
		session.end(null);
		assertEquals(List.of(), runs);
	}

	@Test
	public void anEndBeforeTheBuildIsReadyBuildsNothing() {
		int at = SHADER.indexOf("0.3");
		var session = TuningSession.begin(new Host(true), Runnable::run,
				path("main.frag"), at, at + 3, Tuning.Type.FLOAT, listener());
		session.end(null);
		settle();
		assertEquals(List.of(), runs);
		assertEquals(List.of(), heard);
	}

	@NonNull
	private TuningSession begin(@NonNull String number,
			@NonNull Tuning.Type type) {
		texts.put("main.frag", SHADER);
		int at = SHADER.indexOf(number);
		return TuningSession.begin(new Host(false), Runnable::run,
				path("main.frag"), at, at + number.length(), type, listener());
	}

	@NonNull
	private TuningSession.Listener listener() {
		texts.putIfAbsent("main.frag", SHADER);
		return new TuningSession.Listener() {
			@Override
			public void onLive() {
				heard.add("live");
			}

			@Override
			public void onMustRebuild(@NonNull List<ProjectDiagnostic> why) {
				heard.add("must rebuild " + why);
			}

			@Override
			public void onFailed(@NonNull List<ProjectDiagnostic> errors) {
				heard.add("failed " + errors);
			}
		};
	}

	private void settle() {
		while (!preparing.isEmpty()) {
			preparing.remove(0).run();
		}
	}

	// The texts as the editor has them, prepared as OpenProject does,
	// and an engine that runs what it gets.
	private final class Host implements TuningSession.Host {
		// Whether preparations wait for settle().
		private final boolean waits;

		private Host(boolean waits) {
			this.waits = waits;
		}

		@Override
		public String text(@NonNull ProjectPath path) {
			return texts.get(path.toString());
		}

		@Override
		public void prepare(@NonNull OpenProject.Replacement replacement,
				@NonNull FileTexts.Callback<Preparation> done) {
			deliver(replacement, done);
		}

		@Override
		public void prepare(@NonNull FileTexts.Callback<Preparation> done) {
			deliver(null, done);
		}

		@Override
		public void run(@NonNull Preparation preparation,
				@Nullable Tuning tuning) {
			runs.add(new Run(new String(preparation.files().get(
					path("main.frag")).bytes(), StandardCharsets.UTF_8),
					preparation.temporary(), tuning));
		}

		@Override
		public void applyTuning() {
			++applied;
		}

		@Override
		public void write(@NonNull ProjectPath path, int start, int end,
				@NonNull String text) {
			String before = texts.get(path.toString());
			texts.put(path.toString(), before.substring(0, start) + text +
					before.substring(end));
			written.add(path + ":" + text);
		}

		private void deliver(@Nullable OpenProject.Replacement replacement,
				@NonNull FileTexts.Callback<Preparation> done) {
			var files = new HashMap<ProjectPath, ProjectFiles.Content>();
			for (var entry : texts.entrySet()) {
				String text = entry.getValue();
				if (replacement != null &&
						replacement.path().toString().equals(entry.getKey())) {
					text = text.substring(0, replacement.start()) +
							replacement.text() +
							text.substring(replacement.end());
				}
				files.put(path(entry.getKey()), new ProjectFiles.Content(
						text.getBytes(StandardCharsets.UTF_8), "r"));
			}
			var preparation = Preparation.prepare(files::get,
					path("main.frag"), ProjectLimits.DEFAULT,
					BuiltinNames.ALL);
			Preparation prepared = replacement != null
					? preparation.asTemporary()
					: preparation;
			if (waits) {
				preparing.add(() -> done.done(prepared));
			} else {
				done.done(prepared);
			}
		}
	}
}

package de.markusfisch.android.shadereditor.opengl;

import static de.markusfisch.android.shadereditor.opengl.TestProjects.lines;
import static de.markusfisch.android.shadereditor.opengl.TestProjects.path;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.markusfisch.android.shadereditor.editor.Text;
import de.markusfisch.android.shadereditor.project.BuiltinNames;
import de.markusfisch.android.shadereditor.project.FileTexts;
import de.markusfisch.android.shadereditor.project.OpenProject;
import de.markusfisch.android.shadereditor.project.Preparation;
import de.markusfisch.android.shadereditor.project.ProjectDiagnostic;
import de.markusfisch.android.shadereditor.project.ProjectFiles;
import de.markusfisch.android.shadereditor.project.ProjectLimits;
import de.markusfisch.android.shadereditor.project.ProjectPath;
import de.markusfisch.android.shadereditor.project.RenderPlan;
import de.markusfisch.android.shadereditor.project.SourceLocation;
import de.markusfisch.android.shadereditor.tuning.TunedPreparation;

// The running shader built with numbers of one file replaced by the
// uniforms the engine then declares: in the file that runs, a pass or an
// include, with every line where the user wrote it.
public class TunedBuildTest {
	private static final String PRECISION = lines(
			"#ifdef GL_FRAGMENT_PRECISION_HIGH",
			"precision highp float;",
			"#else",
			"precision mediump float;",
			"#endif");
	private static final String DECLARATION = lines(
			"#ifdef GL_FRAGMENT_PRECISION_HIGH",
			"uniform highp float se_tune0;",
			"#else",
			"uniform mediump float se_tune0;",
			"#endif");

	private final Map<String, String> texts = new LinkedHashMap<>();

	@Test
	public void aNumberOfTheShaderThatRuns() {
		texts.put("main.frag", lines(PRECISION,
				"void main(void) {",
				"	gl_FragColor = vec4(0.3);",
				"}"));
		var ready = ready("main.frag", "0.3");
		assertEquals(List.of(), ready.constants());
		assertTrue(ready.preparation().temporary());
		var source = source(ready, "main.frag", 2);
		assertTrue(source.fragment(),
				source.fragment().startsWith("#define SHADER_EDITOR 1\n" +
						DECLARATION));
		assertKeepsTheUsersLines(source, "main.frag", "0.3", "se_tune0");
	}

	@Test
	public void inGlslEs300TheDeclarationFollowsTheVersion() {
		texts.put("main.frag", lines("#version 300 es", PRECISION,
				"out vec4 color;",
				"void main(void) {",
				"	color = vec4(0.3);",
				"}"));
		var source = source(ready("main.frag", "0.3"), "main.frag", 3);
		assertTrue(source.fragment(), source.fragment().startsWith(
				"#version 300 es\n#define SHADER_EDITOR 1\n" + DECLARATION));
		assertKeepsTheUsersLines(source, "main.frag", "0.3", "se_tune0");
	}

	// Each pass that includes the file declares and gets it.
	@Test
	public void aNumberOfAnInclude() {
		putPasses("#include \"common.glsl\"", "#include \"common.glsl\"");
		texts.put("common.glsl", lines(
				"float glow(float d) {",
				"	return 0.02 / d;",
				"}"));
		var ready = ready("common.glsl", "0.02");
		for (String file : List.of("main.frag", "blur.frag")) {
			var source = source(ready, file, 2);
			assertTrue(file, source.fragment().contains(DECLARATION));
			assertKeepsTheUsersLines(source, "common.glsl", "0.02", "se_tune0");
		}
	}

	@Test
	public void aNumberOfAPass() {
		// A global initializer would need a constant: a function's
		// return does not.
		putPasses("", "float glow() { return 0.5; }");
		var ready = ready("blur.frag", "0.5");
		assertTrue(source(ready, "blur.frag", 2).fragment()
				.contains(DECLARATION));
		assertFalse(source(ready, "main.frag", 2).fragment()
				.contains("se_tune0"));
	}

	// A name a file of the build has is taken: the build says which
	// names the files have, and one with another name is ready. Other
	// names of the prefix do not matter; one at the largest number a
	// long holds is no number to count on.
	@Test
	public void aNameTheTextHasIsNotTaken() {
		texts.put("main.frag", lines(PRECISION,
				"#include \"common.glsl\"",
				"void main(void) {",
				"	gl_FragColor = vec4(0.3 * se_tune4);",
				"}"));
		texts.put("common.glsl", "float se_tune4 = 1.0; // not se_tune0\n");
		assertEquals(new TunedPreparation.Taken(Set.of("se_tune0",
				"se_tune4")), prepare("main.frag", "0.3", "se_tune0"));
		assertTrue(prepare("main.frag", "0.3", "se_tune5")
				instanceof TunedPreparation.Ready);
		texts.put("common.glsl", "float se_tune4 = 1.0, " +
				"se_tune9223372036854775807 = 2.0;\n");
		assertTrue(prepare("main.frag", "0.3", "se_tune0")
				instanceof TunedPreparation.Ready);
	}

	// F4: GLSL splices a line that ends in a backslash with the next one
	// before it reads names (GLSL ES 3.00, 3.3): a name split so is the
	// text's own.
	@Test
	public void aNameSplitByALineContinuationIsNotTaken() {
		texts.put("main.frag", lines("#version 300 es", PRECISION,
				"out vec4 color;",
				"void main(void) {",
				"	float se_tu\\",
				"ne0 = 0.7;",
				"	color = vec4(0.3);",
				"}"));
		assertEquals(new TunedPreparation.Taken(Set.of("se_tune0")),
				prepare("main.frag", "0.3", "se_tune0"));
	}

	// Several numbers of one build, two of one line and one in the body
	// of a macro that two passes use through an include: each program
	// declares what its code uses, and each line is the user's with the
	// numbers replaced.
	@Test
	public void severalNumbersOfOneBuild() {
		putPasses("#include \"common.glsl\"", "#include \"common.glsl\"");
		String common = lines(
				"#define GLOW 0.02",
				"float glow(float d) {",
				"	return GLOW / max(d, 0.001) * mix(0.5, 1.5, d);",
				"}");
		texts.put("common.glsl", common);
		var parts = new ArrayList<OpenProject.Replacement>();
		String[] numbers = {"0.02", "0.001", "0.5", "1.5"};
		for (int i = 0; i < numbers.length; ++i) {
			int at = common.indexOf(numbers[i]);
			parts.add(new OpenProject.Replacement(path("common.glsl"), at,
					at + numbers[i].length(), i == 3 ? "2.5" : "se_tune" + i));
		}
		TunedPreparation.Result[] result = {null};
		TunedPreparation.prepare(new Project(), path("common.glsl"), parts,
				List.of(), Runnable::run, Runnable::run,
				done -> result[0] = done);
		assertTrue(String.valueOf(result[0]),
				result[0] instanceof TunedPreparation.Ready);
		var ready = (TunedPreparation.Ready) result[0];
		var tunings = List.of(
				new Tuning("se_tune0", Tuning.Type.FLOAT, 0.02),
				new Tuning("se_tune1", Tuning.Type.FLOAT, 0.001),
				new Tuning("se_tune2", Tuning.Type.FLOAT, 0.5));
		String replaced = common.replace("0.02", "se_tune0")
				.replace("0.001", "se_tune1").replace("0.5", "se_tune2")
				.replace("1.5", "2.5");
		for (String file : List.of("main.frag", "blur.frag")) {
			var source = source(ready, file, 2, tunings);
			for (int i = 0; i < 3; ++i) {
				assertTrue(file, source.fragment().contains(
						"uniform highp float se_tune" + i + ";"));
			}
			assertKeepsTheUsersLines(source, "common.glsl", replaced);
		}
	}

	// Where the tokens say a constant is needed: in GLSL ES 1.00 a loop
	// count must be one, in GLSL ES 3.00 it may be a uniform.
	@Test
	public void aConstantOfAnIncludeReadInGlslEs100() {
		String loop = lines(
				"float sum() {",
				"	float s = 0.0;",
				"	for (int i = 0; i < 3; ++i) {",
				"		s += 0.1;",
				"	}",
				"	return s;",
				"}");
		putPasses("#include \"loop.glsl\"", "");
		texts.put("loop.glsl", loop);
		int at = loop.indexOf("3;");
		assertEquals(List.of(at), ((TunedPreparation.Ready) prepare(
				"loop.glsl", "3;", "se_tune0")).constants());
		// In GLSL ES 3.00 a loop count may be a uniform.
		texts.put("main.frag", lines("#version 300 es", PRECISION,
				"#pragma se input \"blur.frag\"",
				"uniform sampler2D blur;",
				"#include \"loop.glsl\"",
				"out vec4 color;",
				"void main(void) {",
				"	color = texture(blur, vec2(sum()));",
				"}"));
		texts.put("blur.frag", lines("#version 300 es", PRECISION,
				"out vec4 color;",
				"void main(void) {",
				"	color = vec4(1.0);",
				"}"));
		assertEquals(List.of(), ((TunedPreparation.Ready) prepare(
				"loop.glsl", "3;", "se_tune0")).constants());
	}

	// A file that is not read by what runs.
	@Test
	public void whatCannotBeTunedFailsOnItsLine() {
		texts.put("main.frag", lines(PRECISION,
				"void main(void) {",
				"	gl_FragColor = vec4(0.3);",
				"}"));
		texts.put("other.glsl", "float x = 2.0;\n");
		assertFailed(prepare("other.glsl", "2.0", "se_tune0"), 1,
				"not part of what runs");
	}

	@Test
	public void aShaderWithErrorsFailsWithThem() {
		texts.put("main.frag", lines(PRECISION,
				"#include \"missing.glsl\"",
				"void main(void) {",
				"	gl_FragColor = vec4(0.3);",
				"}"));
		var result = prepare("main.frag", "0.3", "se_tune0");
		assertTrue(result.toString(),
				result instanceof TunedPreparation.Failed failed &&
						failed.errors().get(0).line() == 7);
	}

	private void putPasses(String mainLine, String blurLine) {
		texts.put("main.frag", lines(PRECISION,
				"#pragma se input \"blur.frag\"",
				"uniform sampler2D blur;",
				mainLine,
				"void main(void) {",
				"	gl_FragColor = texture2D(blur, vec2(0.0));",
				"}"));
		texts.put("blur.frag", lines(PRECISION,
				blurLine,
				"void main(void) {",
				"	gl_FragColor = vec4(1.0);",
				"}"));
	}

	// Every line of file in the source is the user's line, as replaced
	// has it where it differs.
	private void assertKeepsTheUsersLines(@NonNull PlanSource source,
			@NonNull String file, @NonNull String replaced) {
		String[] user = texts.get(file).split("\n", -1);
		String[] lines = replaced.split("\n", -1);
		String[] compiled = source.fragment().split("\n", -1);
		boolean found = false;
		for (int i = 0; i < compiled.length - 1; ++i) {
			SourceLocation at = source.sourceMap().locate(i + 1);
			if (at == null || !path(file).equals(at.file())) {
				continue;
			}
			assertEquals(lines[at.line() - 1], compiled[i]);
			found |= !user[at.line() - 1].equals(compiled[i]);
		}
		assertTrue(found);
	}

	private void assertKeepsTheUsersLines(@NonNull PlanSource source,
			@NonNull String file, @NonNull String number,
			@NonNull String name) {
		assertKeepsTheUsersLines(source, file,
				texts.get(file).replace(number, name));
	}

	@NonNull
	private TunedPreparation.Ready ready(@NonNull String file,
			@NonNull String number) {
		var result = prepare(file, number, "se_tune0");
		assertTrue(String.valueOf(result),
				result instanceof TunedPreparation.Ready);
		return (TunedPreparation.Ready) result;
	}

	// The first number of file in the text replaced by text.
	private TunedPreparation.Result prepare(@NonNull String file,
			@NonNull String number, @NonNull String text) {
		int at = texts.get(file).indexOf(number);
		int end = at;
		while (end < number.length() + at &&
				"0123456789.eEfFuUxX".indexOf(number.charAt(end - at)) > -1) {
			++end;
		}
		TunedPreparation.Result[] result = {null};
		TunedPreparation.prepare(new Project(), path(file), List.of(
						new OpenProject.Replacement(path(file), at, end, text)),
				List.of(at), Runnable::run, Runnable::run,
				done -> result[0] = done);
		assertNotNull(result[0]);
		return result[0];
	}

	@NonNull
	private static PlanSource source(@NonNull TunedPreparation.Ready ready,
			@NonNull String file, int glesVersion) {
		return source(ready, file, glesVersion, List.of(
				new Tuning("se_tune0", Tuning.Type.FLOAT, 0)));
	}

	@NonNull
	private static PlanSource source(@NonNull TunedPreparation.Ready ready,
			@NonNull String file, int glesVersion,
			@NonNull List<Tuning> tunings) {
		RenderPlan plan = ready.preparation().plan();
		assertNotNull(plan);
		for (RenderPlan.Program program : plan.programs()) {
			if (program.file().equals(path(file))) {
				var errors = new ArrayList<ProjectDiagnostic>();
				var source = PlanSource.of(program, glesVersion, tunings,
						errors);
				assertEquals(List.of(), errors);
				assertNotNull(source);
				return source;
			}
		}
		throw new AssertionError("no program of " + file);
	}

	private static void assertFailed(TunedPreparation.Result result, int line,
			String message) {
		assertEquals(String.valueOf(result), List.of(new ProjectDiagnostic(
						ProjectDiagnostic.Severity.ERROR, message,
						result instanceof TunedPreparation.Failed failed
								? failed.errors().get(0).file()
								: null, line)),
				((TunedPreparation.Failed) result).errors());
	}

	// The texts, prepared as OpenProject does with a replacement.
	private final class Project implements TunedPreparation.Project {
		@Override
		public Text text(@NonNull ProjectPath path) {
			String text = texts.get(path.toString());
			return text != null ? Text.of(text) : null;
		}

		@Override
		public void prepare(
				@NonNull List<OpenProject.Replacement> replacements,
				@NonNull FileTexts.Callback<Preparation> done) {
			var backwards = new ArrayList<>(replacements);
			backwards.sort((a, b) -> b.start() - a.start());
			var files = new HashMap<ProjectPath, ProjectFiles.Content>();
			for (var entry : texts.entrySet()) {
				String text = entry.getValue();
				for (var replacement : backwards) {
					if (replacement.path().toString().equals(entry.getKey())) {
						text = text.substring(0, replacement.start()) +
								replacement.text() +
								text.substring(replacement.end());
					}
				}
				files.put(path(entry.getKey()), new ProjectFiles.Content(
						text.getBytes(StandardCharsets.UTF_8), "r"));
			}
			done.done(Preparation.prepare(files::get, path("main.frag"),
					ProjectLimits.DEFAULT, BuiltinNames.ALL).asTemporary());
		}
	}
}

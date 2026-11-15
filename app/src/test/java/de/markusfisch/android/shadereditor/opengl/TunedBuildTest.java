package de.markusfisch.android.shadereditor.opengl;

import static de.markusfisch.android.shadereditor.opengl.TestProjects.lines;
import static de.markusfisch.android.shadereditor.opengl.TestProjects.path;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;

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
import de.markusfisch.android.shadereditor.project.RenderPlan;
import de.markusfisch.android.shadereditor.project.SourceLocation;
import de.markusfisch.android.shadereditor.tuning.ConstantContext;
import de.markusfisch.android.shadereditor.tuning.NumberLiteral;
import de.markusfisch.android.shadereditor.tuning.TunedPreparation;

// The running shader built with one number of one file replaced by the
// uniform the engine then declares: in the file that runs, a pass or an
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
	private final List<String> replacedBy = new ArrayList<>();

	@Test
	public void aNumberOfTheShaderThatRuns() {
		texts.put("main.frag", lines(PRECISION,
				"void main(void) {",
				"	gl_FragColor = vec4(0.3);",
				"}"));
		var ready = ready("main.frag", "0.3");
		assertEquals("se_tune0", ready.name());
		assertEquals(new NumberLiteral(Tuning.Type.FLOAT, 0.3), ready.number());
		assertTrue(ready.preparation().temporary());
		var source = source(ready, "main.frag", 2);
		assertTrue(source.fragment(),
				source.fragment().startsWith("#define SHADER_EDITOR 1\n" +
						DECLARATION));
		assertKeepsTheUsersLines(source, "main.frag", "0.3");
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
		assertKeepsTheUsersLines(source, "main.frag", "0.3");
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
			assertKeepsTheUsersLines(source, "common.glsl", "0.02");
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

	// One more build with a name no file of the build has.
	@Test
	public void aNameTheTextHasIsNotTaken() {
		texts.put("main.frag", lines(PRECISION,
				"#include \"common.glsl\"",
				"void main(void) {",
				"	gl_FragColor = vec4(0.3 * se_tune4);",
				"}"));
		texts.put("common.glsl", "float se_tune4 = 1.0; // not se_tune0\n");
		var ready = ready("main.frag", "0.3");
		assertEquals("se_tune5", ready.name());
		assertEquals(List.of("se_tune0", "se_tune5"), replacedBy);
	}

	// Where the tokens say a constant is needed nothing is built.
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
		var result = prepare("loop.glsl", at, at + 1, Tuning.Type.INT);
		assertEquals(new TunedPreparation.Constant(ConstantContext.Kind.LOOP,
				new NumberLiteral(Tuning.Type.INT, 3)), result);
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
		assertTrue(prepare("loop.glsl", at, at + 1, Tuning.Type.INT)
				instanceof TunedPreparation.Ready);
	}

	@Test
	public void whatCannotBeTunedFailsOnItsLine() {
		texts.put("main.frag", lines(PRECISION,
				"// 0.5",
				"void main(void) {",
				"	gl_FragColor = vec4(0.3);",
				"}"));
		String text = texts.get("main.frag");
		int comment = text.indexOf("0.5");
		assertFailed(prepare("main.frag", comment, comment + 3,
				Tuning.Type.FLOAT), 7, "not a number of the code");
		int number = text.indexOf("0.3");
		assertFailed(prepare("main.frag", number, number + 3,
				Tuning.Type.INT), 9, "a float, not a int");
		// A file that is not read by what runs.
		texts.put("other.glsl", "float x = 2.0;\n");
		assertFailed(prepare("other.glsl", 10, 13, Tuning.Type.FLOAT), 1,
				"not part of what runs");
		assertTrue(replacedBy.size() == 1);
	}

	@Test
	public void aShaderWithErrorsFailsWithThem() {
		texts.put("main.frag", lines(PRECISION,
				"#include \"missing.glsl\"",
				"void main(void) {",
				"	gl_FragColor = vec4(0.3);",
				"}"));
		int number = texts.get("main.frag").indexOf("0.3");
		var result = prepare("main.frag", number, number + 3,
				Tuning.Type.FLOAT);
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

	// Every line of file in the source is the user's line, the number
	// replaced by the name on its own line only.
	private void assertKeepsTheUsersLines(@NonNull PlanSource source,
			@NonNull String file, @NonNull String number) {
		String[] user = texts.get(file).split("\n", -1);
		String[] compiled = source.fragment().split("\n", -1);
		boolean found = false;
		for (int i = 0; i < compiled.length - 1; ++i) {
			SourceLocation at = source.sourceMap().locate(i + 1);
			if (at == null || !path(file).equals(at.file())) {
				continue;
			}
			String line = user[at.line() - 1];
			if (line.contains(number)) {
				assertEquals(line.replace(number, "se_tune0"), compiled[i]);
				found = true;
			} else {
				assertEquals(line, compiled[i]);
			}
		}
		assertTrue(found);
	}

	@NonNull
	private TunedPreparation.Ready ready(@NonNull String file,
			@NonNull String number) {
		int at = texts.get(file).indexOf(number);
		var result = prepare(file, at, at + number.length(),
				NumberLiteral.at(texts.get(file), at, at + number.length())
						.type());
		assertTrue(String.valueOf(result),
				result instanceof TunedPreparation.Ready);
		return (TunedPreparation.Ready) result;
	}

	private TunedPreparation.Result prepare(@NonNull String file, int start,
			int end, @NonNull Tuning.Type type) {
		TunedPreparation.Result[] result = {null};
		TunedPreparation.prepare(new Project(), path(file), start, end, type,
				done -> result[0] = done);
		assertNotNull(result[0]);
		return result[0];
	}

	@NonNull
	private static PlanSource source(@NonNull TunedPreparation.Ready ready,
			@NonNull String file, int glesVersion) {
		RenderPlan plan = ready.preparation().plan();
		assertNotNull(plan);
		for (RenderPlan.Program program : plan.programs()) {
			if (program.file().equals(path(file))) {
				var errors = new ArrayList<ProjectDiagnostic>();
				var source = PlanSource.of(program, glesVersion,
						new Tuning(ready.name(), ready.number().type(),
								ready.number().value(), new Tuning.Listener() {
							@Override
							public void onLive() {
							}

							@Override
							public void onRefused(
									@NonNull List<ProjectDiagnostic> e) {
							}
						}), errors);
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
		public String text(@NonNull ProjectPath path) {
			return texts.get(path.toString());
		}

		@Override
		public void prepare(@NonNull OpenProject.Replacement replacement,
				@NonNull FileTexts.Callback<Preparation> done) {
			replacedBy.add(replacement.text());
			var files = new HashMap<ProjectPath, ProjectFiles.Content>();
			for (var entry : texts.entrySet()) {
				String text = entry.getValue();
				if (replacement.path().toString().equals(entry.getKey())) {
					text = text.substring(0, replacement.start()) +
							replacement.text() +
							text.substring(replacement.end());
				}
				files.put(path(entry.getKey()), new ProjectFiles.Content(
						text.getBytes(StandardCharsets.UTF_8), "r"));
			}
			done.done(Preparation.prepare(files::get, path("main.frag"),
					ProjectLimits.DEFAULT, BuiltinNames.ALL).asTemporary());
		}
	}
}

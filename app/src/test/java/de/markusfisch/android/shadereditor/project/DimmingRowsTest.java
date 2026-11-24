package de.markusfisch.android.shadereditor.project;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import de.markusfisch.android.shadereditor.editor.DimmedLines;
import de.markusfisch.android.shadereditor.editor.Document;
import de.markusfisch.android.shadereditor.editor.Text;

/**
 * The grey lines of the example shaders the owner tried, key by key, as
 * the editor gets them: the build's lines, then after each key what the
 * document shows right after it and once the run again answers. Lines
 * are 1-based, as the editor numbers them.
 */
public class DimmingRowsTest {
	private static final ProjectPath MAIN = path("main.frag");
	// The pass of Main.frag of the checklist.
	private static final String PASSES_MAIN = """
			#version 300 es
			precision highp float;

			#pragma se input "Pass.frag"
			uniform sampler2D Pass;
			uniform vec2 resolution;
			out vec4 fragColor;

			// Set TINT to 0 to see the pass as it is.
			#define TINT 1

			void main() {
				vec2 st = gl_FragCoord.xy / resolution;
				vec3 col = texture(Pass, st).rgb;
			#if TINT
				col *= vec3(1.0, 0.6, 0.3);
			#else
				col *= 1.0;
			#endif
				fragColor = vec4(col, 1.0);
			}
			""";

	private Document document;
	private DimmedLines dimmed;
	private BuiltLines.Again again;
	private boolean asked;
	// The grey lines right after the last key.
	private String afterKey;

	// Switches.frag's README, "Try these", and rows 2 and 3.
	@Test
	public void switchesDimsWhatItsReadmeSays() throws IOException {
		String text = probe("example-switches.frag");
		// The ES 3.00 #else of GL_FRAGMENT_PRECISION_HIGH is grey, the
		// arms of the extension only a driver knows are not.
		open(text, MAIN, files(MAIN, text), 3);
		String built = "[15, 34, 37, 38, 51]";
		assertEquals(built, grey());
		int two = text.indexOf("#define AA 2") + 11;
		String aa1 = "[15, 26, 27, 28, 29, 30, 31, 32, 37, 38, 51]";
		assertEquals(aa1, key(two, two, "-"));
		assertEquals(built, afterKey);
		assertEquals(built, key(two, two + 1, ""));
		// Nothing after AA: the #if cannot be told, so 34 is plain.
		assertEquals("[15, 37, 38, 51]", key(two, two + 1, ""));
		assertEquals(aa1, key(two, two, "1"));
		assertEquals("[15, 37, 38, 51]", key(two, two + 1, ""));
		assertEquals(built, key(two, two, "2"));
		// Above the edit nothing changes, also right after the key.
		int zero = text.indexOf("#if 0") + 4;
		assertEquals("[15, 34, 51]", key(zero, zero, "!"));
		assertEquals(built, afterKey);
		assertEquals(built, key(zero, zero + 1, ""));
		// Ordinary lines: nothing runs again, a line typed into grey is.
		int end40 = text.indexOf("v);\n\t// Only the driver") + 3;
		assertEquals(built, key(end40, end40, " x"));
		assertFalse(asked);
		assertEquals(built, key(end40, end40 + 2, ""));
		int end34 = text.indexOf("v = disc(uv);") + 13;
		assertEquals(built, key(end34, end34, " // y"));
		assertFalse(asked);
		assertEquals("[15, 34, 35, 38, 39, 52]",
				key(end34 + 5, end34 + 5, "\n\tv += 0.0;"));
		assertFalse(asked);
		assertEquals(built, key(end34, end34 + 16, ""));
		// SOFT switched on when the last / goes.
		int soft = document.text().toString().indexOf("// #define SOFT");
		assertEquals(built, key(soft + 2, soft + 3, ""));
		assertEquals(built, key(soft + 1, soft + 2, ""));
		assertEquals("[17, 34, 37, 38, 51]", key(soft, soft + 1, ""));
		// A build of the text now says the same: it changes nothing.
		String now = document.text().toString();
		assertEquals(lines(BuiltLines.of(plan(files(MAIN, now)))
				.off(MAIN, 3)), grey());
	}

	// Template.frag: the template's precision switch, which only the
	// driver decides in ES 1.00, is not grey; its own #else is.
	@Test
	public void templateDimsItsOwnSwitchOnly() throws IOException {
		String text = probe("example-template.frag");
		open(text, MAIN, files(MAIN, text), 3);
		assertEquals("[18]", grey());
		open(text, MAIN, files(MAIN, text), 2);
		assertEquals("[18]", grey());
	}

	// Row 4: every block of a 2,000-line file flips at once.
	@Test
	public void aLongFileFlipsEveryBlockOnce() throws IOException {
		String text = probe("example-long.frag");
		open(text, MAIN, files(MAIN, text), 3);
		List<Integer> off = dimmedLines();
		assertEquals(248, off.size());
		assertEquals(15, (int) off.get(0));
		assertEquals(1991, (int) off.get(off.size() - 1));
		int two = text.indexOf("#define AA 2") + 11;
		key(two, two, "-");
		List<Integer> flipped = dimmedLines();
		assertEquals(248, flipped.size());
		assertEquals(13, (int) flipped.get(0));
		assertTrue(text.split("\n")[flipped.get(0) - 1]
				.contains("return length"));
		key(two, two + 1, "");
		assertEquals(off, dimmedLines());
		int last = document.text().toString().indexOf("fract(v");
		key(last, last, "abc");
		assertFalse(asked);
		assertEquals(off, dimmedLines());
	}

	// Rows 6 and 7: a pass has its own switch; an edit of the #pragma
	// that names it drops the grey until the next build.
	@Test
	public void aPassAndItsShaderDimTheirOwnSwitches() throws IOException {
		String pass = probe("example-pass.frag");
		ProjectPath passPath = path("Pass.frag");
		ProjectPath mainPath = path("Main.frag");
		MemoryProjectFiles files = files(mainPath, PASSES_MAIN);
		files.put("Pass.frag", pass);
		open(pass, passPath, files, mainPath, 3);
		assertEquals("[18]", grey());
		int one = pass.indexOf("#define RINGS 1") + 14;
		assertEquals("[]", key(one, one + 1, ""));
		assertEquals("[16]", key(one, one, "0"));
		assertEquals("[]", key(one, one + 1, ""));
		assertEquals("[18]", key(one, one, "1"));
		open(PASSES_MAIN, mainPath, files, mainPath, 3);
		assertEquals("[18]", grey());
		int tint = PASSES_MAIN.indexOf("#define TINT 1") + 13;
		assertEquals("[]", key(tint, tint + 1, ""));
		assertEquals("[16]", key(tint, tint, "0"));
		key(tint, tint + 1, "1");
		int pragma = document.text().toString().indexOf("\"Pass.frag\"") +
				11;
		assertEquals("[]", key(pragma, pragma, " "));
		assertFalse(asked);
	}

	// Rows 8 and 9: each file's build tells its own lines, the same
	// again for the same text.
	@Test
	public void eachFileShowsOnlyItsOwnLines() throws IOException {
		String switches = probe("example-switches.frag");
		String template = probe("example-template.frag");
		assertEquals(List.of(17), BuiltLines.of(plan(files(MAIN, template)))
				.off(MAIN, 3));
		assertEquals(List.of(14, 33, 36, 37, 50), BuiltLines.of(
				plan(files(MAIN, switches))).off(MAIN, 3));
		assertEquals(BuiltLines.of(plan(files(MAIN, switches))).off(MAIN, 3),
				BuiltLines.of(plan(files(MAIN, switches))).off(MAIN, 3));
	}

	// Row 15: on OpenGL ES 2.0, which refuses #version 300 es, nothing of
	// those is grey, also after a key; ES 1.00 shaders keep their lines.
	@Test
	public void openGlEs2DimsOnlyWhatItCompiles() throws IOException {
		String switches = probe("example-switches.frag");
		open(switches, MAIN, files(MAIN, switches), 2);
		assertEquals("[]", grey());
		int two = switches.indexOf("#define AA 2") + 11;
		assertEquals("[]", key(two, two, "-"));
		String rings = probe("example-rings-es100.frag");
		open(rings, MAIN, files(MAIN, rings), 2);
		assertEquals("[14]", grey());
		MemoryProjectFiles passes = files(path("Main.frag"), PASSES_MAIN);
		passes.put("Pass.frag", probe("example-pass.frag"));
		BuiltLines built = BuiltLines.of(plan(passes, path("Main.frag")));
		assertEquals(List.of(), built.off(path("Main.frag"), 2));
		assertEquals(List.of(), built.off(path("Pass.frag"), 2));
	}

	// Random edits, each answered as the editor answers it: no line is
	// grey that a build of the text then compiles.
	@Test
	public void randomEditsNeverGreyACompiledLine() {
		String[] pieces = {"#if 0\n", "#if 1\n", "#endif\n", "#else\n",
				"#elif 1\n", "#define A 1\n", "#ifdef A\n", "#undef A\n",
				"float x;\n", "x", "\n", "/*", "*/", "\\", "0", "1", " ",
				"#", "//", "A", "#if A\n", "#ifndef B\n", "#if FOO\n"};
		String start = """
				#version 300 es
				precision mediump float;
				#define A 1
				#if A
				float a;
				#else
				float b;
				#endif
				#ifdef B
				float c;
				#elif 0
				float d;
				#else
				float e;
				#endif
				out vec4 o;
				void main() { o = vec4(0.0); }
				""";
		for (long seed = 0; seed < 300; ++seed) {
			Random random = new Random(seed);
			open(start, MAIN, files(MAIN, start), 3);
			for (int step = 0; step < 25; ++step) {
				String now = document.text().toString();
				int lo = now.indexOf('\n') + 1;
				int hi = now.lastIndexOf("out vec4");
				if (hi <= lo) {
					break;
				}
				int at = lo + random.nextInt(hi - lo);
				int kind = random.nextInt(10);
				if (kind < 6) {
					key(at, at, pieces[random.nextInt(pieces.length)]);
				} else if (kind < 9) {
					key(at, Math.min(hi, at + 1 + random.nextInt(12)), "");
				} else {
					asked = false;
					document.undo();
					if (asked) {
						dimmed.answer(document.text(), again.off(document
								.text().toString()));
					}
				}
				Preparation fresh = Preparation.prepare(files(MAIN,
						document.text().toString()), MAIN,
						ProjectLimits.DEFAULT, BuiltinNames.ALL);
				if (!fresh.diagnostics().isEmpty()) {
					// Nothing compiles; grey above the edit may stay.
					continue;
				}
				List<Integer> wrong = dimmedLines();
				for (int n : BuiltLines.of(fresh.plan()).off(MAIN, 3)) {
					wrong.remove(Integer.valueOf(n + 1));
				}
				assertEquals("seed " + seed + " step " + step + "\n" +
						document.text(), List.of(), wrong);
			}
		}
	}

	private void open(String text, ProjectPath path, MemoryProjectFiles files,
			int gles) {
		open(text, path, files, path, gles);
	}

	private void open(String text, ProjectPath path, MemoryProjectFiles files,
			ProjectPath entry, int gles) {
		Preparation preparation = Preparation.prepare(files, entry,
				ProjectLimits.DEFAULT, BuiltinNames.ALL);
		assertEquals(List.of(), preparation.diagnostics());
		document = new Document(Text.of(text));
		dimmed = new DimmedLines(document, () -> asked = true);
		assertTrue(dimmed.set(document.text(), BuiltLines.of(
				preparation.plan()).off(path, gles)));
		again = BuiltLines.again(preparation, entry, BuiltinNames.ALL, path,
				gles);
	}

	// Replaces start to end with text; returns the grey lines once the
	// run again answered, if the key asked for one.
	private String key(int start, int end, String text) {
		asked = false;
		document.replace(start, end, Text.of(text));
		afterKey = grey();
		if (asked) {
			dimmed.answer(document.text(), again.off(document.text()
					.toString()));
		}
		return grey();
	}

	private String grey() {
		return dimmedLines().toString();
	}

	private List<Integer> dimmedLines() {
		List<Integer> lines = new ArrayList<>();
		for (int n = 0; n < document.text().lineCount(); ++n) {
			if (dimmed.isOff(n)) {
				lines.add(n + 1);
			}
		}
		return lines;
	}

	private static String lines(List<Integer> off) {
		List<Integer> lines = new ArrayList<>();
		for (int n : off) {
			lines.add(n + 1);
		}
		return lines.toString();
	}

	private static RenderPlan plan(MemoryProjectFiles files) {
		return plan(files, MAIN);
	}

	private static RenderPlan plan(MemoryProjectFiles files,
			ProjectPath entry) {
		return Preparation.prepare(files, entry, ProjectLimits.DEFAULT,
				BuiltinNames.ALL).plan();
	}

	private static MemoryProjectFiles files(ProjectPath path, String text) {
		MemoryProjectFiles files = new MemoryProjectFiles();
		files.put(path.toString(), text);
		return files;
	}

	private static String probe(String name) throws IOException {
		return Files.readString(new File(DirectiveCases.probes(), name)
				.toPath());
	}

	private static ProjectPath path(String path) {
		return ProjectPath.parse(path).value();
	}
}

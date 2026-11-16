package de.markusfisch.android.shadereditor.opengl;

import static de.markusfisch.android.shadereditor.opengl.TestProjects.lines;
import static de.markusfisch.android.shadereditor.opengl.TestProjects.prepare;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import de.markusfisch.android.shadereditor.project.HostDirective.Filter;
import de.markusfisch.android.shadereditor.project.HostDirective.Format;
import de.markusfisch.android.shadereditor.project.HostDirective.Mipmap;
import de.markusfisch.android.shadereditor.project.HostDirective.Wrap;
import de.markusfisch.android.shadereditor.project.Preparation;
import de.markusfisch.android.shadereditor.project.ProjectDiagnostic;
import de.markusfisch.android.shadereditor.project.RenderPlan.Sampling;

// The output of a pass that an input reads with a mipmap filter gets its
// mipmaps made again after every draw, before what reads it draws, and
// only then. Where OpenGL ES 2.0 cannot make them, the input reads with
// its base filter and is warned why, never black.
public class PassMipmapsTest {
	private static final String MAIN = lines(
			"#ifdef GL_FRAGMENT_PRECISION_HIGH",
			"precision highp float;",
			"#else",
			"precision mediump float;",
			"#endif",
			"void main(void) {",
			"	gl_FragColor = vec4(1.0);",
			"}");
	private static final Sampling BASE = new Sampling(Filter.LINEAR,
			Filter.LINEAR, Mipmap.NONE, Wrap.CLAMP, Wrap.CLAMP);

	@Test
	public void aPassReadWithMipmapsHasThemBeforeItIsRead() {
		var engine = new FakeEngine(reads("filter=linear mipmap=linear"), 3);
		engine.resize(8, 4);
		engine.draw(0);
		var frame = frame(engine, 1);
		int a = last(engine, "a.frag");
		int main = last(engine, "main.frag");
		assertEquals(List.of("draw " + a, "mipmap " + a, "draw " + main,
				"draw 0"), frame);
	}

	// Nobody reads it with mipmaps: no cost.
	@Test
	public void withoutAMipmapFilterNothingIsMade() {
		var engine = new FakeEngine(reads("filter=linear"), 3);
		engine.resize(8, 4);
		engine.draw(0);
		assertEquals(0, mipmaps(engine.device.calls));
		var frame = frame(engine, 1);
		assertEquals(List.of("draw " + last(engine, "a.frag"),
				"draw " + last(engine, "main.frag"), "draw 0"), frame);
	}

	// A converted database shader's backbuffer: the pass reads its own
	// last frame. The cleared history at the first frame has them too.
	@Test
	public void theBackbufferHasThemAfterEveryFrame() {
		var engine = new FakeEngine(prepare("main.frag", lines(
				"#pragma se input filter=linear mipmap=linear",
				"uniform sampler2D backbuffer;",
				MAIN)), 3);
		engine.resize(8, 4);
		var first = frame(engine, 0);
		int drawn = last(engine, "main.frag");
		assertEquals(2, mipmaps(first));
		assertEquals("mipmap " + drawn, first.get(first.indexOf(
				"draw " + drawn) + 1));
		var next = frame(engine, 1);
		int again = last(engine, "main.frag");
		assertEquals(List.of("draw " + again, "mipmap " + again, "draw 0"),
				next);
	}

	// OpenGL ES 2.0 makes no mipmaps of 6x4 without OES_texture_npot:
	// the input reads with its base filter, and says why.
	@Test
	public void es2WithoutAPowerOfTwoReadsWithTheBaseFilter() {
		var engine = new FakeEngine(reads("filter=linear mipmap=linear"), 2);
		engine.resize(6, 4);
		engine.draw(0);
		engine.draw(1);
		assertEquals(0, mipmaps(engine.device.calls));
		int a = last(engine, "a.frag");
		assertTrue(engine.device.calls.contains("sample " + a + " " + BASE));
		assertEquals(List.of("WARNING main.frag:1 a reads a.frag with " +
				"min=linear instead of mipmaps: OpenGL ES 2.0 makes " +
				"mipmaps only of a size that is a power of two, and the " +
				"target is 6x4"), warnings(engine));
	}

	@Test
	public void es2MakesThemOfAPowerOfTwo() {
		var engine = new FakeEngine(reads("filter=linear mipmap=linear"), 2);
		engine.resize(8, 4);
		engine.draw(0);
		var frame = frame(engine, 1);
		int a = last(engine, "a.frag");
		assertTrue(frame.contains("mipmap " + a));
		assertEquals(List.of(), warnings(engine));
	}

	@Test
	public void es2WithOesTextureNpotMakesThemOfAnySize() {
		var engine = new FakeEngine(new FakeGlDevice(),
				reads("filter=linear mipmap=linear"),
				TargetFormats.of(2, "GL_OES_texture_npot"));
		engine.resize(6, 4);
		engine.draw(0);
		var frame = frame(engine, 1);
		assertTrue(frame.contains("mipmap " + last(engine, "a.frag")));
		assertEquals(List.of(), warnings(engine));
	}

	// A device that fails to make them: the base filter, and said.
	@Test
	public void whereTheyCannotBeMadeTheBaseFilterIsRead() {
		var device = new FakeGlDevice();
		var engine = new FakeEngine(device,
				reads("filter=linear mipmap=linear"),
				TargetFormats.of(3, ""));
		device.fail("generateMipmap", 1);
		engine.resize(8, 4);
		engine.draw(0);
		var frame = frame(engine, 1);
		int a = last(engine, "a.frag");
		assertEquals(0, mipmaps(frame));
		assertTrue(engine.device.calls.contains("sample " + a + " " + BASE));
		assertEquals(1, warnings(engine).size());
		assertTrue(warnings(engine).get(0), warnings(engine).get(0)
				.startsWith("WARNING a.frag:0 Cannot make mipmaps of a " +
						"target of 8x4 in format=rgba8, glGenerateMipmap " +
						"failed"));
	}

	@Test
	public void theRuleOfTheFormats() {
		assertNull(TargetFormats.of(3, "").noMipmaps(Format.RGBA16F, 6, 4));
		assertNull(TargetFormats.of(2, "").noMipmaps(Format.RGBA8, 8, 4));
		assertTrue(TargetFormats.of(2, "").noMipmaps(Format.RGBA8, 6, 4)
				.contains("power of two"));
		assertTrue(TargetFormats.of(2, "GL_OES_texture_half_float " +
				"GL_EXT_color_buffer_half_float").noMipmaps(Format.RGBA16F,
				8, 4).contains("OES_texture_half_float_linear"));
		assertNull(TargetFormats.of(2, "GL_OES_texture_half_float " +
				"GL_OES_texture_half_float_linear").noMipmaps(
				Format.RGBA16F, 8, 4));
	}

	// main.frag reads a.frag with these options.
	@NonNull
	private static Preparation reads(@NonNull String options) {
		return prepare(
				"main.frag", lines(
						"#pragma se input \"a.frag\" " + options,
						"uniform sampler2D a;",
						MAIN),
				"a.frag", MAIN);
	}

	// The draws and mipmaps of one frame.
	@NonNull
	private static List<String> frame(@NonNull FakeEngine engine,
			float time) {
		var calls = engine.device.calls;
		int from = calls.size();
		engine.draw(time);
		var frame = new ArrayList<String>();
		for (var call : calls.subList(from, calls.size())) {
			if (call.startsWith("draw ") || call.startsWith("mipmap ")) {
				frame.add(call);
			}
		}
		return frame;
	}

	private static int mipmaps(@NonNull List<String> calls) {
		int count = 0;
		for (var call : calls) {
			if (call.startsWith("mipmap ")) {
				++count;
			}
		}
		return count;
	}

	private static int last(@NonNull FakeEngine engine, @NonNull String file) {
		var draws = engine.draws(file);
		return draws.get(draws.size() - 1).target();
	}

	@NonNull
	private static List<String> warnings(@NonNull FakeEngine engine) {
		var warnings = new ArrayList<String>();
		for (var d : engine.diagnostics) {
			if (d.severity() == ProjectDiagnostic.Severity.WARNING) {
				warnings.add("WARNING " + d.file() + ":" + d.line() + " " +
						d.message());
			}
		}
		return warnings;
	}
}

package de.markusfisch.android.shadereditor.opengl;

import static de.markusfisch.android.shadereditor.opengl.TestProjects.lines;
import static de.markusfisch.android.shadereditor.opengl.TestProjects.path;
import static de.markusfisch.android.shadereditor.opengl.TestProjects.prepare;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

import de.markusfisch.android.shadereditor.project.HostDirective.Format;
import de.markusfisch.android.shadereditor.project.Preparation;
import de.markusfisch.android.shadereditor.project.ProjectDiagnostic;

// A device may name an extension for drawing into half-float textures
// and still not draw into them. What it can do is tried once, when the
// context is made, and rgba16f is refused where the try fails.
public class HalfFloatProbeTest {
	private static final String NAMED = "GL_OES_texture_half_float " +
			"GL_OES_texture_half_float_linear GL_EXT_color_buffer_half_float";
	private static final String CANNOT = "format=rgba16f needs a device " +
			"that draws into half-float textures, which this device cannot " +
			"do: it names EXT_color_buffer_half_float, but drawing into a " +
			"half-float texture failed; use format=rgba8";
	private static final String PRECISION = lines(
			"#ifdef GL_FRAGMENT_PRECISION_HIGH",
			"precision highp float;",
			"#else",
			"precision mediump float;",
			"#endif");
	private static final int HALF_FLOAT_OES = 0x8D61;
	private static final String MAIN = "void main(void) { " +
			"gl_FragColor = vec4(1.0); }";

	@Test
	public void aDeviceThatNamesItAndDrawsIntoItKeepsRgba16f() {
		var device = new FakeGlDevice();
		device.extensions = NAMED;
		var formats = device.targetFormats(2);
		assertNull(formats.refusal(Format.RGBA16F));
		assertTrue(formats.filters(Format.RGBA16F));
		assertTrue(device.calls.contains("createFramebuffer"));
		assertTrue(device.live.isEmpty());
	}

	@Test
	public void aDeviceThatNamesItButCannotDrawIntoItRefusesRgba16f() {
		var device = new FakeGlDevice();
		device.extensions = NAMED;
		device.drawsHalfFloat = false;
		var formats = device.targetFormats(2);
		assertEquals(CANNOT, formats.refusal(Format.RGBA16F));
		assertNull(formats.refusal(Format.RGBA8));
		assertTrue(device.live.isEmpty());

		// The same on OpenGL ES 3.0, with the extension it names.
		device.extensions = "GL_EXT_color_buffer_float";
		assertEquals(CANNOT.replace("EXT_color_buffer_half_float",
						"EXT_color_buffer_float"),
				device.targetFormats(3).refusal(Format.RGBA16F));
	}

	@Test
	public void aDeviceThatDoesNotNameItIsNotTried() {
		var device = new FakeGlDevice();
		device.extensions = "GL_OES_texture_half_float";
		assertEquals("format=rgba16f needs a device that draws into " +
						"half-float textures (EXT_color_buffer_half_float), " +
						"which this device does not have; use format=rgba8",
				device.targetFormats(2).refusal(Format.RGBA16F));
		assertFalse(device.calls.contains("createFramebuffer"));
	}

	// The projects of the device check: e (an rgba16f pass beside an
	// rgba8 one), j (the presented pass in rgba16f) and k (an rgba16f
	// pass that starts as an rgba16f seed) are refused at each target
	// line in rgba16f, before any target is made.
	@Test
	public void rgba16fProjectsAreRefusedAtTheirTargetLines() {
		assertEquals(List.of(error("hdr16.frag")), refused(prepare(
				"main.frag", lines(PRECISION,
						"#pragma se input \"hdr16.frag\"",
						"uniform sampler2D hdr16;",
						"#pragma se input \"hdr8.frag\"",
						"uniform sampler2D hdr8;",
						"void main(void) { gl_FragColor = " +
								"texture2D(hdr16, vec2(0.0)) + " +
								"texture2D(hdr8, vec2(0.0)); }"),
				"hdr16.frag", lines("#pragma se target format=rgba16f",
						PRECISION, MAIN),
				"hdr8.frag", lines(PRECISION, MAIN))));
		assertEquals(List.of(error("main.frag")), refused(prepare(
				"main.frag", lines("#pragma se target format=rgba16f",
						PRECISION, MAIN))));
		var k = refused(prepare(
				"main.frag", lines(PRECISION,
						"#pragma se input \"state.frag\"",
						"uniform highp sampler2D state;",
						"void main(void) { gl_FragColor = " +
								"texture2D(state, vec2(0.0)); }"),
				"state.frag", lines(
						"#pragma se target format=rgba16f " +
								"initial=\"seed.frag\"",
						"#pragma se input \"state.frag\" frame=previous",
						"uniform highp sampler2D previous;",
						PRECISION,
						"void main(void) { gl_FragColor = " +
								"texture2D(previous, vec2(0.0)); }"),
				"seed.frag", lines("#pragma se target format=rgba16f",
						PRECISION, MAIN)));
		assertTrue(k.toString(), k.contains(error("state.frag")));
		assertTrue(k.toString(), k.contains(error("seed.frag")));
		assertEquals(k.toString(), 2, k.size());
	}

	private static List<ProjectDiagnostic> refused(
			Preparation preparation) {
		var device = new FakeGlDevice();
		device.extensions = NAMED;
		device.drawsHalfFloat = false;
		var formats = device.targetFormats(2);
		var engine = new FakeEngine(device, preparation, formats);
		assertNull(engine.runtime);
		// No half-float texture but the one that was tried.
		assertEquals(1, device.types.values().stream()
				.filter(type -> type == HALF_FLOAT_OES).count());
		return engine.diagnostics.stream()
				.filter(d -> d.severity() == ProjectDiagnostic.Severity.ERROR)
				.toList();
	}

	private static ProjectDiagnostic error(String file) {
		return new ProjectDiagnostic(ProjectDiagnostic.Severity.ERROR,
				CANNOT, path(file), 1);
	}
}

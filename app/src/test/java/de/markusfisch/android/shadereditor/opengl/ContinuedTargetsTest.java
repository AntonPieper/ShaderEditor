package de.markusfisch.android.shadereditor.opengl;

import static de.markusfisch.android.shadereditor.opengl.TestProjects.lines;
import static de.markusfisch.android.shadereditor.opengl.TestProjects.prepare;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

// A build of a tuning session goes on from the build that draws: it
// draws into the same targets, so the previous frames of the passes go
// on, unless a pass changed its size or format.
public class ContinuedTargetsTest {
	private static final String MAIN = lines(
			"#ifdef GL_FRAGMENT_PRECISION_HIGH",
			"precision highp float;",
			"#else",
			"precision mediump float;",
			"#endif",
			"void main(void) {",
			"	gl_FragColor = vec4(1.0);",
			"}");

	private final FakeGlDevice device = new FakeGlDevice();
	private final TargetFormats formats = TargetFormats.of(3,
			"GL_EXT_color_buffer_half_float");

	@Test
	public void theNextBuildDrawsIntoTheTargetsOfTheOneItGoesOn() {
		var drawing = engine("");
		var made = targets(drawing);
		for (int frame = 0; frame < 3; ++frame) {
			drawing.draw(frame);
		}
		var sim = drawing.draws("sim.frag");
		int lastFrame = sim.get(sim.size() - 1).target();

		var next = engine("");
		var live = textures();
		assertEquals(ShaderSwap.Allocation.MADE, next.runtime.createTargets(8,
				4, drawing.runtime, new ArrayList<>()));
		// Nothing was made for it.
		assertEquals(live, textures());
		drawing.runtime.release();
		assertTrue(device.live.containsAll(made));
		next.frame.surfaceWidth = 8;
		next.frame.surfaceHeight = 4;
		next.frame.width = 8;
		next.frame.height = 4;
		next.draw(3);
		// Its first frame reads the frame the build before drew last, as
		// a frame of that build would have.
		var first = next.draws("sim.frag").get(0);
		assertTrue(first.units().containsValue(lastFrame));
		assertFalse(String.valueOf(first.target()),
				first.target() == lastFrame);

		// A shared target goes with the last build that has it.
		next.runtime.release();
		for (int texture : made) {
			assertFalse(device.live.contains(texture));
		}
	}

	// The build that draws keeps its targets when the next one does not
	// replace it, as when a newer request superseded that.
	@Test
	public void aNextBuildThatGoesLeavesThemToTheOneThatDraws() {
		var drawing = engine("");
		var made = targets(drawing);
		drawing.draw(0);
		var next = engine("");
		next.runtime.createTargets(8, 4, drawing.runtime, new ArrayList<>());
		next.runtime.release();
		assertTrue(device.live.containsAll(made));
		drawing.draw(1);
		assertTrue(device.live.containsAll(made));
	}

	@Test
	public void aPassOfAnotherSizeOrFormatStartsAgain() {
		for (String target : new String[]{
				"#pragma se target scale=0.5",
				"#pragma se target format=rgba16f"}) {
			var drawing = engine("");
			var made = targets(drawing);
			drawing.draw(0);
			var next = engine(target);
			assertTrue(next.diagnostics.toString(),
					next.diagnostics.isEmpty());
			assertEquals(ShaderSwap.Allocation.MADE, next.runtime.createTargets(
					8, 4, drawing.runtime, new ArrayList<>()));
			drawing.runtime.release();
			for (int texture : made) {
				assertFalse(target, device.live.contains(texture));
			}
			next.runtime.release();
		}
	}

	// sim.frag reads its own previous frame, and main.frag shows it.
	@NonNull
	private FakeEngine engine(@NonNull String simTarget) {
		return new FakeEngine(device, prepare(
				"main.frag", lines(
						"#pragma se input \"sim.frag\"",
						"uniform sampler2D sim;",
						MAIN),
				"sim.frag", lines(
						simTarget,
						"#pragma se input \"sim.frag\" frame=previous",
						"uniform sampler2D sim;",
						MAIN)), formats);
	}

	@NonNull
	private Set<Integer> textures() {
		return new HashSet<>(device.live);
	}

	// What the targets of engine at 8x4 are made of.
	@NonNull
	private Set<Integer> targets(@NonNull FakeEngine engine) {
		var before = textures();
		engine.resize(8, 4);
		var made = textures();
		made.removeAll(before);
		assertFalse(made.isEmpty());
		return made;
	}
}

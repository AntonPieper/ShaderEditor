package de.markusfisch.android.shadereditor.opengl;

import static de.markusfisch.android.shadereditor.opengl.TestProjects.lines;
import static de.markusfisch.android.shadereditor.opengl.TestProjects.prepare;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import androidx.annotation.NonNull;

import org.junit.Test;

// A pass shown full size instead of the output: what runs does not
// change, and the picture taken of the shader stays the output.
public class ShowPassTest {
	private static final String MAIN = lines(
			"#ifdef GL_FRAGMENT_PRECISION_HIGH",
			"precision highp float;",
			"#else",
			"precision mediump float;",
			"#endif",
			"void main(void) {",
			"	gl_FragColor = vec4(1.0);",
			"}");

	@Test
	public void aPassIsPresentedAndThePictureStaysTheOutput() {
		var engine = life();
		engine.draw(0, "life.frag");
		int life = last(engine, "life.frag");
		assertEquals(life, presented(engine));
		assertEquals(last(engine, "main.frag"),
				engine.pipeline.picture().getId());
		// Nothing that runs changed: every pass drew, once each.
		assertEquals(1, engine.draws("main.frag").size());
		assertEquals(1, engine.draws("life.frag").size());
		// Back to the output.
		engine.draw(1, null);
		assertEquals(last(engine, "main.frag"), presented(engine));
	}

	// A first frame keeps the texture it was drawn into, frame after
	// frame, so it can be shown.
	@Test
	public void aFirstFrameShowsItsOneFrame() {
		var engine = life();
		engine.draw(0, "seed.frag");
		int seed = engine.draws("seed.frag").get(0).target();
		assertEquals(seed, presented(engine));
		for (int frame = 1; frame < 4; ++frame) {
			engine.draw(frame, "seed.frag");
			assertEquals(seed, presented(engine));
		}
		assertEquals(1, engine.draws("seed.frag").size());
	}

	// A file the build does not have shows the output.
	@Test
	public void aPassThatIsNotThereShowsTheOutput() {
		var engine = life();
		engine.draw(0, "gone.frag");
		assertEquals(last(engine, "main.frag"), presented(engine));
		assertNotEquals(last(engine, "life.frag"), presented(engine));
	}

	// main.frag shows life.frag, which reads itself and starts as
	// seed.frag.
	@NonNull
	private static FakeEngine life() {
		var engine = new FakeEngine(prepare(
				"main.frag", lines(
						"#pragma se input \"life.frag\"",
						"uniform sampler2D life;",
						MAIN),
				"life.frag", lines(
						"#pragma se target scale=0.5 initial=\"seed.frag\"",
						"#pragma se input \"life.frag\" frame=previous",
						"uniform sampler2D life;",
						MAIN),
				"seed.frag", MAIN), 3);
		engine.resize(8, 4);
		return engine;
	}

	private static int last(@NonNull FakeEngine engine, @NonNull String file) {
		var draws = engine.draws(file);
		return draws.get(draws.size() - 1).target();
	}

	// The texture the surface was drawn from last.
	private static int presented(@NonNull FakeEngine engine) {
		var presented = engine.presented();
		var draw = presented.get(presented.size() - 1);
		return draw.units().get(engine.stretchUnit(draw));
	}
}

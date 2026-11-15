package de.markusfisch.android.shadereditor.opengl;

import static de.markusfisch.android.shadereditor.opengl.TestProjects.lines;
import static de.markusfisch.android.shadereditor.opengl.TestProjects.prepare;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

// Small pictures of the passes for the sheet: every pass that draws, in
// the rows' order, in its own proportions, of what it drew last; made
// in one atlas read at once, and gone when no one watches.
public class PassPicturesTest {
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
	public void everyPassThatDrawsInTheRowsOrder() {
		var engine = life();
		engine.draw(0);
		engine.draw(1);
		var inspector = new PassInspector(engine.device);
		var pictures = inspector.pictures(engine.runtime.lastFrames());
		var files = new ArrayList<String>();
		for (var picture : pictures) {
			files.add(picture.file().toString());
		}
		assertEquals(List.of("seed.frag", "life.frag", "main.frag"), files);
		// What each drew last: life.frag's frame 1, seed.frag's frame.
		assertEquals(last(engine, "life.frag") & 0xff, red(pictures.get(1)));
		assertEquals(engine.draws("seed.frag").get(0).target() & 0xff,
				red(pictures.get(0)));
		assertEquals(last(engine, "main.frag") & 0xff, red(pictures.get(2)));
	}

	// 8 × 4 is 96 × 48, the top row first.
	@Test
	public void inItsOwnProportionsTheRightWayUp() {
		var engine = life();
		engine.draw(0);
		var picture = new PassInspector(engine.device)
				.pictures(engine.runtime.lastFrames()).get(2);
		assertEquals(96, picture.width());
		assertEquals(48, picture.height());
		assertEquals(96 * 48, picture.argb().length);
		assertEquals(47, picture.argb()[0] & 0xff);
		assertEquals(0, picture.argb()[47 * 96] & 0xff);
		assertEquals(0xff, picture.argb()[0] >>> 24);
		assertEquals(10, PassInspector.pictureWidth(100, 1000));
		assertEquals(96, PassInspector.pictureHeight(100, 1000));
		assertEquals(1, PassInspector.pictureHeight(1000, 1));
	}

	// Bytes R, G, B, A in memory are a colour int A, R, G, B.
	@Test
	public void readBytesAreColours() {
		assertEquals(0x80112233, PassInspector.argb(0x80332211));
	}

	// It makes nothing until asked, and release() leaves nothing.
	@Test
	public void nothingIsKeptWhenNoOneWatches() {
		var engine = life();
		engine.draw(0);
		var live = new HashSet<>(engine.device.live);
		var inspector = new PassInspector(engine.device);
		assertEquals(live, engine.device.live);
		inspector.pictures(engine.runtime.lastFrames());
		assertTrue(engine.device.live.size() > live.size());
		inspector.release();
		assertEquals(live, engine.device.live);
	}

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

	private static int red(@NonNull PassPicture picture) {
		return (picture.argb()[0] >> 16) & 0xff;
	}
}

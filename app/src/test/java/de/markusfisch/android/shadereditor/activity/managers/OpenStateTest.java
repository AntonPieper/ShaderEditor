package de.markusfisch.android.shadereditor.activity.managers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class OpenStateTest {
	// Every state a screen can be in is read back as it was written.
	@Test
	public void everyStateComesBackAsItWasKept() {
		for (OpenState state : List.of(
				OpenState.NOTHING,
				OpenState.STARTING,
				OpenState.MOVING,
				OpenState.file("a1b2c3d4", "Plasma.frag", false, .5f),
				OpenState.file("examples", "Ink/main.frag", false, 1f),
				OpenState.file("9z8y7x6w", "a b/\u00e4.frag", true, .25f),
				OpenState.file("a1b2c3d4", "New.frag", false, 1f).undecided(),
				OpenState.asked("a1b2c3d4", "lib/sdf.glsl", false, true),
				OpenState.notMoved(42, "Old one", .125f, true),
				OpenState.notMoved(7, "two\nlines", 1f, false),
				OpenState.notMoved(8, "", 1f, false))) {
			assertEquals(state.kind().name(), state,
					normal(OpenState.decode(state.encode())));
		}
	}

	// A screen whose start had not shown a file yet, or was still
	// moving the shaders, shows what the library shows, as a cold
	// start does. A file or a shader that was open comes back itself,
	// and so does nothing open.
	@Test
	public void onlyAStartNotDoneLetsTheStartShowTheLibrarysFile() {
		assertTrue(OpenState.STARTING.showsLibrary());
		assertTrue(OpenState.MOVING.showsLibrary());
		assertFalse(OpenState.NOTHING.showsLibrary());
		assertFalse(OpenState.file("k", "A.frag", false, 1f).showsLibrary());
		assertFalse(OpenState.asked("k", "A.frag", false, true)
				.showsLibrary());
		assertFalse(OpenState.notMoved(3, "t", 1f, false).showsLibrary());
		assertTrue(OpenState.file("k", "A.frag", false, 1f).isFile());
		assertTrue(OpenState.asked("k", "A.frag", false, true).isFile());
		assertFalse(OpenState.notMoved(3, "t", 1f, false).isFile());
	}

	// A file whose tap was not settled comes back as tapped, so that
	// it is decided again whether it runs.
	@Test
	public void aTapNotSettledComesBackAsATap() {
		OpenState state = OpenState.decode(OpenState.file("k", "A.frag",
				false, .5f).undecided().encode());

		assertEquals(OpenState.Kind.FILE, state.kind());
		assertTrue(state.tapped());
		assertFalse(OpenState.file("k", "A.frag", false, .5f).tapped());
	}

	// What says nothing a screen can come back to starts as a cold
	// start does, never with a shader without a row.
	@Test
	public void whatCannotBeReadStartsAnew() {
		for (String text : new String[]{null, "", "FILE", "nonsense\n\n\n" +
				"false\nfalse\n1.0\n0\nfalse\n",
				"FILE\n\n\nfalse\nfalse\n1.0\n0\nfalse\n",
				"FILE\nkey\n\nfalse\nfalse\n1.0\n0\nfalse\n",
				"NOT_MOVED\n\n\nfalse\nfalse\n1.0\n0\nfalse\nTitle",
				"FILE\nkey\nA.frag\nfalse\nfalse\nfast\n0\nfalse\n"}) {
			assertEquals(String.valueOf(text), OpenState.STARTING,
					OpenState.decode(text));
		}
	}

	// No title and an empty title are the same to the screen.
	private static OpenState normal(OpenState state) {
		return state.title() != null && state.title().isEmpty() &&
				state.kind() != OpenState.Kind.NOT_MOVED
				? new OpenState(state.kind(), state.key(), state.path(),
						state.single(), state.tapped(), state.quality(),
						state.shader(), state.modified(), null)
				: state;
	}
}

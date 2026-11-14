package de.markusfisch.android.shadereditor.activity.managers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;

import org.junit.Test;

public class RunGuardTest {
	private static class Kept implements RunGuard.Store {
		String pending = "";
		String held = "";

		@NonNull
		@Override
		public String pending() {
			return pending;
		}

		@Override
		public void setPending(@NonNull String file) {
			pending = file;
		}

		@NonNull
		@Override
		public String held() {
			return held;
		}

		@Override
		public void setHeld(@NonNull String file) {
			held = file;
		}
	}

	private final Kept kept = new Kept();

	// The app ended between handing a file to the engine and its
	// first frame: the next start holds that file, and no other.
	@Test
	public void aFileTheAppEndedOnIsHeldAtTheNextStart() {
		new RunGuard(kept).running("lib", "Bad.frag");

		RunGuard next = new RunGuard(kept);

		assertTrue(next.holds("lib", "Bad.frag"));
		assertFalse(next.holds("lib", "Good.frag"));
		assertFalse(next.holds("other", "Bad.frag"));
		assertEquals("", kept.pending);
	}

	// A file that drew a frame, or whose screen left the front, is
	// not pending anymore.
	@Test
	public void aFileThatRanIsNotHeld() {
		RunGuard guard = new RunGuard(kept);
		guard.running("lib", "Fine.frag");
		guard.ran();

		assertFalse(new RunGuard(kept).holds("lib", "Fine.frag"));
	}

	// The hold lasts over starts, and while other files run, until
	// the user asks to run the file.
	@Test
	public void theHoldLastsUntilTheUserRunsTheFile() {
		new RunGuard(kept).running("lib", "Bad.frag");
		RunGuard second = new RunGuard(kept);
		second.running("lib", "Good.frag");
		second.ran();

		RunGuard third = new RunGuard(kept);
		assertTrue(third.holds("lib", "Bad.frag"));

		third.release();
		assertFalse(third.holds("lib", "Bad.frag"));
		assertFalse(new RunGuard(kept).holds("lib", "Bad.frag"));
	}

	// Run by the user, a file that ends the app again is held again.
	@Test
	public void aFileRunByTheUserThatEndsTheAppAgainIsHeldAgain() {
		new RunGuard(kept).running("lib", "Bad.frag");
		RunGuard second = new RunGuard(kept);
		second.release();
		second.running("lib", "Bad.frag");

		assertTrue(new RunGuard(kept).holds("lib", "Bad.frag"));
	}

	// The engine is handed the same file many times while it is
	// typed in: it is kept once.
	@Test
	public void theSameFilePendingIsKeptOnce() {
		int[] writes = {0};
		RunGuard guard = new RunGuard(new Kept() {
			@Override
			public void setPending(@NonNull String file) {
				++writes[0];
				super.setPending(file);
			}
		});

		guard.running("lib", "A.frag");
		guard.running("lib", "A.frag");
		guard.ran();
		guard.ran();

		assertEquals(2, writes[0]);
	}
}

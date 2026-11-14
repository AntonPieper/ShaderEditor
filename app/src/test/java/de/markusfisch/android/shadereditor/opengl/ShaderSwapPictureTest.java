package de.markusfisch.android.shadereditor.opengl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;

import org.junit.Test;

import de.markusfisch.android.shadereditor.opengl.ShaderSwapTest.FakeBuild;

// The picture a failed build leaves on screen belongs to what it was
// built from. Here a shader's first letter is its picture: m1 and m2 are
// edits of one file, n1 is another file.
public class ShaderSwapPictureTest {
	private final ShaderSwap<String, FakeBuild> swap = new ShaderSwap<>(
			(a, b) -> a.charAt(0) == b.charAt(0));

	// Run m, then open n, which does not build: black and n's errors,
	// never m's picture under n's name.
	@Test
	public void anotherFileThatFailsEndsThePictureShown() {
		var m = buildAndSwap("m1");
		swap.offer("n1");
		var n = swap.take();
		assertTrue(swap.failed(n));
		assertTrue(swap.report(n));
		assertNull(swap.current());
		assertNull(swap.currentRevision());
		assertTrue(m.released);
		assertFalse(swap.canDraw(1));
		assertNull(swap.take());
	}

	// While m is edited, the last edit that built keeps drawing.
	@Test
	public void anEditThatFailsKeepsThePictureOfItsFile() {
		var m = buildAndSwap("m1");
		swap.offer("m2");
		assertFalse(swap.failed(swap.take()));
		assertSame(m, swap.current());
		assertFalse(m.released);
	}

	// The same when n links but cannot upload or make its targets.
	@Test
	public void anotherFileWhoseBuildCannotCompleteEndsThePictureShown() {
		var m = buildAndSwap("m1");
		build("n1", false);
		var failed = swap.nextFailed();
		assertNotNull(failed);
		assertEquals("n1", failed.shader());
		assertNull(swap.current());
		assertTrue(m.released);
	}

	// A failure that a newer request already superseded decides nothing:
	// m was asked for again.
	@Test
	public void aSupersededFailureKeepsThePictureShown() {
		var m = buildAndSwap("m1");
		swap.offer("n1");
		var n = swap.take();
		swap.offer("m2");
		assertFalse(swap.failed(n));
		assertSame(m, swap.current());
		assertFalse(m.released);
	}

	// After the context was lost, m is not built again when n, asked for
	// since, fails.
	@Test
	public void theLastPictureOfAnotherFileIsNotBuiltAgainAfterTheContextWasLost() {
		buildAndSwap("m1");
		swap.contextLost();
		swap.offer("n1");
		var n = swap.take();
		assertEquals("n1", n.shader());
		assertFalse(swap.failed(n));
		assertNull(swap.take());
		assertNull(swap.current());
	}

	// But an edit of m that fails then still gets m back.
	@Test
	public void theLastPictureOfTheSameFileIsBuiltAgainAfterTheContextWasLost() {
		buildAndSwap("m1");
		swap.contextLost();
		swap.offer("m2");
		swap.failed(swap.take());
		var fallback = swap.take();
		assertNotNull(fallback);
		assertEquals("m1", fallback.shader());
	}

	@NonNull
	private FakeBuild build(@NonNull String shader, boolean complete) {
		swap.offer(shader);
		var revision = swap.take();
		assertNotNull(revision);
		var build = new FakeBuild(shader, complete);
		swap.built(revision, build);
		return build;
	}

	@NonNull
	private FakeBuild buildAndSwap(@NonNull String shader) {
		var build = build(shader, true);
		assertSame(build, swap.swapIfComplete());
		return build;
	}
}

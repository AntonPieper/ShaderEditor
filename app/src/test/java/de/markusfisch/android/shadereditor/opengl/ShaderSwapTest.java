package de.markusfisch.android.shadereditor.opengl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;

import org.junit.Test;

public class ShaderSwapTest {
	private static final class FakeBuild implements ShaderSwap.Build {
		final String shader;
		boolean complete;
		boolean released;
		boolean discarded;

		FakeBuild(@NonNull String shader, boolean complete) {
			this.shader = shader;
			this.complete = complete;
		}

		@Override
		public boolean isComplete() {
			return complete;
		}

		@Override
		public void release() {
			released = true;
		}

		@Override
		public void discard() {
			discarded = true;
		}
	}

	private final ShaderSwap<String, FakeBuild> swap = new ShaderSwap<>();

	@Test
	public void buildsOnlyTheNewestShader() {
		swap.offer("a");
		swap.offer("b");
		swap.offer("c");
		assertEquals("c", swap.take());
		assertNull(swap.take());
		assertEquals("c", swap.latest());
	}

	@Test
	public void swapsInACompleteBuild() {
		var a = buildAndSwap("a");
		var b = buildAndSwap("b");
		assertSame(b, swap.current());
		assertTrue(a.released);
		assertFalse(b.released);
	}

	@Test
	public void failedBuildKeepsTheCurrentOneDrawing() {
		var a = buildAndSwap("a");
		swap.offer("broken");
		assertEquals("broken", swap.take());
		swap.failed();
		assertNull(swap.swapIfComplete());
		assertSame(a, swap.current());
		assertFalse(a.released);
	}

	@Test
	public void currentDrawsUntilTheNextBuildIsComplete() {
		var a = buildAndSwap("a");
		swap.offer("b");
		swap.take();
		var b = new FakeBuild("b", false);
		swap.built(b);
		assertNull(swap.swapIfComplete());
		assertSame(a, swap.current());
		assertSame(b, swap.next());

		b.complete = true;
		assertSame(b, swap.swapIfComplete());
		assertTrue(a.released);
		assertNull(swap.next());
	}

	@Test
	public void supersededBuildIsReleasedWithItsTextures() {
		var a = buildAndSwap("a");
		swap.offer("b");
		swap.take();
		var b = new FakeBuild("b", false);
		swap.built(b);
		swap.offer("c");
		swap.take();
		var c = new FakeBuild("c", true);
		swap.built(c);
		assertTrue(b.released);
		assertSame(c, swap.swapIfComplete());
		assertTrue(a.released);
		assertFalse(c.released);
	}

	@Test
	public void waitingBuildSurvivesANewerShaderThatFails() {
		buildAndSwap("a");
		swap.offer("b");
		swap.take();
		var b = new FakeBuild("b", false);
		swap.built(b);
		swap.offer("broken");
		swap.take();
		swap.failed();
		b.complete = true;
		assertSame(b, swap.swapIfComplete());
		assertFalse(b.released);
	}

	@Test
	public void contextLossDiscardsBuildsAndBuildsTheNewestAgain() {
		var a = buildAndSwap("a");
		swap.offer("b");
		swap.take();
		var b = new FakeBuild("b", false);
		swap.built(b);
		swap.contextLost();
		assertTrue(a.discarded);
		assertTrue(b.discarded);
		assertFalse(a.released);
		assertNull(swap.current());
		assertNull(swap.next());
		assertEquals("b", swap.take());
	}

	@Test
	public void contextLossKeepsANewerPendingShader() {
		buildAndSwap("a");
		swap.offer("b");
		swap.contextLost();
		assertEquals("b", swap.take());
		assertNull(swap.take());
	}

	@Test
	public void rebuildsTheCurrentShaderUnlessANewerOneWaits() {
		buildAndSwap("a");
		swap.offer("broken");
		swap.take();
		swap.rebuildCurrent();
		assertEquals("a", swap.take());

		swap.offer("b");
		swap.rebuildCurrent();
		assertEquals("b", swap.take());
	}

	@Test
	public void numbersShadersInTheOrderTheyWereOffered() {
		assertEquals(0, swap.offered());
		swap.offer("a");
		swap.offer("b");
		assertEquals(2, swap.offered());
	}

	@Test
	public void showsTheNewestShaderThatDrawsOrFailed() {
		buildAndSwap("a");
		assertEquals(1, swap.shown());
		swap.offer("broken");
		swap.take();
		swap.failed();
		assertEquals(2, swap.shown());

		swap.offer("c");
		swap.take();
		var c = new FakeBuild("c", false);
		swap.built(c);
		assertEquals(2, swap.shown());
		c.complete = true;
		swap.swapIfComplete();
		assertEquals(3, swap.shown());
	}

	@Test
	public void rebuildingAnOlderShaderDoesNotLowerWhatIsShown() {
		buildAndSwap("a");
		swap.offer("broken");
		swap.take();
		swap.failed();
		swap.rebuildCurrent();
		swap.take();
		swap.built(new FakeBuild("a", true));
		swap.swapIfComplete();
		assertEquals(2, swap.shown());
	}

	@NonNull
	private FakeBuild buildAndSwap(@NonNull String shader) {
		swap.offer(shader);
		assertEquals(shader, swap.take());
		var build = new FakeBuild(shader, true);
		swap.built(build);
		assertSame(build, swap.swapIfComplete());
		return build;
	}
}

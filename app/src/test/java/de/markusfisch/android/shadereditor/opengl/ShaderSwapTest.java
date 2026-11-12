package de.markusfisch.android.shadereditor.opengl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;

import org.junit.Test;

public class ShaderSwapTest {
	static final class FakeBuild implements ShaderSwap.Build {
		final String shader;
		boolean complete;
		boolean fits = true;
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
		public boolean fits(int width, int height) {
			return fits;
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
		var revision = swap.take();
		assertNotNull(revision);
		assertEquals("c", revision.shader());
		assertEquals(3, revision.number());
		assertNull(swap.take());
	}

	@Test
	public void swapsInACompleteBuild() {
		var a = buildAndSwap("a");
		var b = buildAndSwap("b");
		assertSame(b, swap.current());
		assertEquals(2, swap.currentRevision().number());
		assertTrue(a.released);
		assertFalse(b.released);
	}

	@Test
	public void failedBuildKeepsTheCurrentOneDrawing() {
		var a = buildAndSwap("a");
		swap.offer("broken");
		assertEquals("broken", swap.take().shader());
		assertNull(swap.swapIfComplete());
		assertSame(a, swap.current());
		assertFalse(a.released);
	}

	@Test
	public void currentDrawsUntilTheNextBuildIsComplete() {
		var a = buildAndSwap("a");
		var b = build("b", false);
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
		var b = build("b", false);
		var c = build("c", true);
		assertTrue(b.released);
		assertSame(c, swap.swapIfComplete());
		assertTrue(a.released);
		assertFalse(c.released);
	}

	@Test
	public void waitingBuildSurvivesANewerShaderThatFails() {
		buildAndSwap("a");
		var b = build("b", false);
		swap.offer("broken");
		swap.take();
		b.complete = true;
		assertSame(b, swap.swapIfComplete());
		assertFalse(b.released);
	}

	// A plan fails as a whole when one of its images cannot be uploaded.
	@Test
	public void waitingBuildThatFailsIsReleasedAndTheCurrentKeepsDrawing() {
		var a = buildAndSwap("a");
		var plan = build("plan", false);
		var failed = swap.nextFailed();
		assertNotNull(failed);
		assertEquals("plan", failed.shader());
		assertTrue(plan.released);
		assertNull(swap.next());
		assertNull(swap.swapIfComplete());
		assertSame(a, swap.current());
		assertFalse(a.released);
		assertFalse(swap.canDraw(failed.number()));
		assertTrue(swap.report(failed));
		assertNull(swap.nextFailed());
	}

	@Test
	public void olderPlanThatFailsLaterDoesNotReportOverANewerFailure() {
		buildAndSwap("a");
		build("plan", false);
		swap.offer("broken");
		assertTrue(swap.report(swap.take()));
		assertFalse(swap.report(swap.nextFailed()));
	}

	@Test
	public void olderBuildDoesNotTakeANewerFailureOffTheScreen() {
		var a = buildAndSwap("a");
		assertTrue(swap.report(swap.currentRevision()));
		var b = build("b", false);
		swap.offer("c");
		var c = swap.take();
		assertTrue(swap.report(c));

		b.complete = true;
		assertSame(b, swap.swapIfComplete());
		assertTrue(a.released);
		assertFalse(swap.report(swap.currentRevision()));

		buildAndSwap("d");
		assertTrue(swap.report(swap.currentRevision()));
	}

	@Test
	public void resizeKeepsTheNewerShaderThatDecodes() {
		var a = buildAndSwap("preset");
		a.fits = false;
		var b = build("b", false);
		assertNull(swap.resized(1, 2));
		assertNull(swap.take());
		assertSame(b, swap.next());
		assertFalse(b.released);
		b.complete = true;
		assertSame(b, swap.swapIfComplete());
	}

	// A plan's images do not depend on the render size.
	@Test
	public void resizeKeepsAPlanThatDecodesItsImages() {
		buildAndSwap("plan");
		var plan = build("plan, edited", false);
		assertNull(swap.resized(1, 2));
		assertNull(swap.take());
		assertSame(plan, swap.next());
	}

	@Test
	public void resizeDecodesTheNextBuildAgainForTheNewSize() {
		buildAndSwap("a");
		var b = build("preset", false);
		b.fits = false;
		assertSame(b, swap.resized(1, 2));
		assertNull(swap.take());
		assertFalse(b.released);
	}

	@Test
	public void resizeBuildsTheCurrentShaderAgainUnlessANewerOneWaits() {
		var a = buildAndSwap("preset");
		swap.offer("broken");
		swap.take();
		assertNull(swap.resized(1, 2));
		assertNull(swap.take());

		a.fits = false;
		assertNull(swap.resized(1, 2));
		var again = swap.take();
		assertNotNull(again);
		assertEquals("preset", again.shader());
		assertEquals(1, again.number());

		swap.offer("b");
		swap.resized(1, 2);
		assertEquals("b", swap.take().shader());
	}

	@Test
	public void rebuildingAnOlderShaderDoesNotReportOverANewerFailure() {
		var a = buildAndSwap("preset");
		swap.offer("broken");
		assertTrue(swap.report(swap.take()));
		a.fits = false;
		swap.resized(1, 2);
		var again = swap.take();
		swap.built(again, new FakeBuild("preset", true));
		swap.swapIfComplete();
		assertFalse(swap.report(swap.currentRevision()));
	}

	@Test
	public void contextLossDiscardsBuildsAndBuildsTheNewestAgain() {
		var a = buildAndSwap("a");
		var b = build("b", false);
		swap.contextLost();
		assertTrue(a.discarded);
		assertTrue(b.discarded);
		assertFalse(a.released);
		assertNull(swap.current());
		assertNull(swap.next());
		assertEquals("b", swap.take().shader());
	}

	@Test
	public void contextLossKeepsANewerPendingShader() {
		buildAndSwap("a");
		swap.offer("b");
		swap.contextLost();
		var b = swap.take();
		assertEquals("b", b.shader());
		swap.built(b, new FakeBuild("b", false));
		assertNull(swap.take());
	}

	// Run A, edit it into broken B, leave the app and come back.
	@Test
	public void contextLossBuildsTheLastWorkingShaderWhenTheNewestFails() {
		buildAndSwap("a");
		swap.offer("broken");
		var broken = swap.take();
		swap.failed(broken);
		assertTrue(swap.report(broken));
		swap.contextLost();

		assertEquals("broken", swap.take().shader());
		swap.failed(broken);
		assertTrue(swap.report(broken));
		assertTrue(swap.canDraw(1));
		var fallback = swap.take();
		assertNotNull(fallback);
		assertEquals("a", fallback.shader());
		assertEquals(1, fallback.number());
		var a = new FakeBuild("a", true);
		swap.built(fallback, a);
		assertNull(swap.take());
		assertSame(a, swap.swapIfComplete());
		assertFalse(swap.report(swap.currentRevision()));
		assertNull(swap.take());
	}

	@Test
	public void contextLossBuildsOnlyTheNewestWhenItBuilds() {
		buildAndSwap("a");
		swap.offer("b");
		swap.contextLost();
		var b = swap.take();
		assertEquals("b", b.shader());
		swap.built(b, new FakeBuild("b", false));
		assertNull(swap.take());
		swap.next().complete = true;
		swap.swapIfComplete();
		assertFalse(swap.canDraw(1));
		swap.contextLost();
		b = swap.take();
		assertEquals("b", b.shader());
		swap.built(b, new FakeBuild("b", false));
		assertNull(swap.take());
	}

	// The wallpaper draws A and is hidden; a shader that does not compile
	// is chosen before it shows again.
	@Test
	public void shaderOfferedWhileTheContextIsLostDoesNotLoseTheLastWorkingOne() {
		buildAndSwap("a");
		swap.contextLost();
		swap.offer("broken");
		var broken = swap.take();
		assertEquals("broken", broken.shader());
		swap.failed(broken);
		var fallback = swap.take();
		assertNotNull(fallback);
		assertEquals("a", fallback.shader());
		var a = new FakeBuild("a", true);
		swap.built(fallback, a);
		assertNull(swap.take());
		assertSame(a, swap.swapIfComplete());
		assertNull(swap.take());
	}

	@Test
	public void newestThatDrewLastAndFailsAgainIsNotBuiltOnceMore() {
		buildAndSwap("a");
		swap.contextLost();
		var a = swap.take();
		assertEquals("a", a.shader());
		swap.failed(a);
		assertNull(swap.take());
	}

	@Test
	public void lastWorkingShaderThatFailsToBuildIsNotBuiltAgain() {
		buildAndSwap("a");
		swap.offer("broken");
		swap.failed(swap.take());
		swap.contextLost();
		swap.failed(swap.take());
		var fallback = swap.take();
		swap.failed(fallback);
		assertNull(swap.take());
		assertFalse(swap.canDraw(fallback.number()));
	}

	// The last working shader is a plan whose images no longer load, so
	// it fails as it loads, and nothing is built again.
	@Test
	public void lastWorkingPlanThatFailsWhileItLoadsIsNotBuiltAgain() {
		buildAndSwap("plan");
		swap.offer("broken");
		var broken = swap.take();
		swap.failed(broken);
		assertTrue(swap.report(broken));
		swap.contextLost();
		swap.failed(swap.take());
		var fallback = swap.take();
		assertEquals("plan", fallback.shader());
		swap.built(fallback, new FakeBuild("plan", false));
		var failed = swap.nextFailed();
		assertSame(fallback, failed);
		assertFalse(swap.report(failed));
		assertNull(swap.take());
		assertFalse(swap.canDraw(fallback.number()));
	}

	@Test
	public void lastWorkingShaderSurvivesAnotherContextLossWhileItLoads() {
		buildAndSwap("a");
		swap.offer("broken");
		swap.failed(swap.take());
		swap.contextLost();
		swap.failed(swap.take());
		swap.built(swap.take(), new FakeBuild("a", false));
		swap.contextLost();
		assertEquals("broken", swap.take().shader());
		assertEquals("a", swap.take().shader());
	}

	@Test
	public void numbersShadersInTheOrderTheyWereOffered() {
		assertEquals(0, swap.offered());
		swap.offer("a");
		swap.offer("b");
		assertEquals(2, swap.offered());
	}

	@Test
	public void offersAChangeOfTheNewestShader() {
		swap.offerChanged(shader -> shader + "!");
		assertEquals(0, swap.offered());
		swap.offer("a");
		swap.offer("b");
		swap.offerChanged(shader -> shader + "!");
		var revision = swap.take();
		assertEquals("b!", revision.shader());
		assertEquals(3, revision.number());
	}

	@Test
	public void canDrawWhileWaitingBuiltOrDrawing() {
		assertFalse(swap.canDraw(1));
		swap.offer("a");
		assertTrue(swap.canDraw(1));
		var a = swap.take();
		assertFalse(swap.canDraw(1));
		swap.built(a, new FakeBuild("a", false));
		assertTrue(swap.canDraw(1));
		swap.next().complete = true;
		swap.swapIfComplete();
		assertTrue(swap.canDraw(1));

		swap.offer("broken");
		swap.take();
		assertFalse(swap.canDraw(2));
		assertTrue(swap.canDraw(1));

		swap.offer("superseded");
		swap.offer("c");
		assertFalse(swap.canDraw(3));
		assertTrue(swap.canDraw(4));
	}

	@NonNull
	private FakeBuild build(@NonNull String shader, boolean complete) {
		swap.offer(shader);
		var revision = swap.take();
		assertNotNull(revision);
		assertEquals(shader, revision.shader());
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

package de.markusfisch.android.shadereditor.opengl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.junit.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ShaderSwapTest {
	static final class FakeBuild implements ShaderSwap.Build {
		final String shader;
		boolean complete;
		boolean released;
		boolean discarded;
		// Runs while the build is released.
		@Nullable
		Runnable onRelease;

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
			if (onRelease != null) {
				onRelease.run();
			}
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

	// The build that becomes current is the newest requested revision
	// at the moment it becomes current: a request that arrives while the
	// build it replaces is released comes after that moment.
	@Test
	public void aRequestWhileTheOldBuildIsReleasedComesAfterTheSwap() {
		var a = buildAndSwap("a");
		swap.offer("b");
		var b = new FakeBuild("b", true);
		swap.built(swap.take(), b);
		var currentAtRequest = new ArrayList<FakeBuild>();
		a.onRelease = () -> {
			currentAtRequest.add(swap.current());
			swap.request();
		};
		assertSame(b, swap.swapIfComplete());
		assertEquals(List.of(b), currentAtRequest);
		assertEquals(2, swap.currentRevision().number());
		assertEquals(3, swap.requested());
	}

	// A request between taking the build and swapping it in supersedes
	// it: nothing changes.
	@Test
	public void aBuildSupersededBeforeTheSwapNeverBecomesCurrent() {
		var a = buildAndSwap("a");
		swap.offer("b");
		var b = new FakeBuild("b", true);
		swap.built(swap.take(), b);
		swap.request();
		assertNull(swap.swapIfComplete());
		assertSame(a, swap.current());
		assertFalse(a.released);
		assertTrue(b.released);
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

	// R02: B waits for its images when a newer C fails to compile.
	@Test
	public void waitingBuildDoesNotSurviveANewerShaderThatFails() {
		var a = buildAndSwap("a");
		var b = build("b", false);
		swap.offer("broken");
		swap.failed(swap.take());
		b.complete = true;
		assertNull(swap.swapIfComplete());
		assertTrue(b.released);
		assertSame(a, swap.current());
		assertFalse(a.released);
	}

	// R02: a request made while B uploads supersedes B before it swaps.
	@Test
	public void requestWhileTheNextBuildUploadsKeepsItFromSwapping() {
		var a = buildAndSwap("a");
		var b = build("b", true);
		int c = swap.request();
		assertNull(swap.swapIfComplete());
		assertTrue(b.released);
		assertSame(a, swap.current());
		assertTrue(swap.offer(c, "c"));
		assertEquals("c", swap.take().shader());
	}

	// R02: A is still being prepared when B is requested.
	@Test
	public void preparationThatFinishesAfterANewerRequestIsDropped() {
		buildAndSwap("shown");
		int a = swap.request();
		int b = swap.request();
		assertFalse(swap.offer(a, "a"));
		assertNull(swap.take());
		assertFalse(swap.canDraw(a));
		assertTrue(swap.canDraw(b));
		assertTrue(swap.offer(b, "b"));
		assertEquals("b", swap.take().shader());
	}

	@Test
	public void olderBuildDoesNotReportWhileANewerOneIsPrepared() {
		buildAndSwap("a");
		swap.offer("b");
		var b = swap.take();
		swap.request();
		assertFalse(swap.report(b));
	}

	// Quality changes while A is prepared apply to A, but not to B
	// requested after them.
	@Test
	public void changeWhileTheNewestShaderIsPreparedAppliesOnceItIsOffered() {
		int a = swap.request();
		swap.offerChanged(shader -> shader + " at q2");
		assertTrue(swap.offer(a, "a"));
		assertEquals("a at q2", swap.take().shader());

		int c = swap.request();
		swap.offerChanged(shader -> shader + " at q2");
		int d = swap.request();
		assertFalse(swap.offer(c, "c"));
		assertTrue(swap.offer(d, "d"));
		var revision = swap.take();
		assertEquals("d", revision.shader());
		assertEquals(d, revision.number());
	}

	// The view is detached while A is still prepared.
	@Test
	public void cancelDropsTheShaderStillBeingPrepared() {
		var shown = buildAndSwap("shown");
		int a = swap.request();
		swap.cancel();
		assertFalse(swap.offer(a, "a"));
		assertFalse(swap.canDraw(a));
		assertSame(shown, swap.current());
	}

	@Test
	public void cancelKeepsTheNewestShaderOncePrepared() {
		swap.offer("a");
		swap.cancel();
		assertEquals("a", swap.take().shader());
	}

	@Test
	public void currentThatLostItsTargetsCannotDraw() {
		var a = buildAndSwap("a");
		int number = swap.currentRevision().number();
		swap.currentFailed();
		assertTrue(a.released);
		assertNull(swap.current());
		assertFalse(swap.canDraw(number));
		assertNull(swap.take());
	}

	// Context loss while the newest shader is still being prepared.
	@Test
	public void contextLossBuildsTheShownShaderWhileANewerOneIsPrepared() {
		buildAndSwap("a");
		int b = swap.request();
		swap.contextLost();
		var again = swap.take();
		assertEquals("a", again.shader());
		var a = new FakeBuild("a", true);
		swap.built(again, a);
		assertSame(a, swap.swapIfComplete());
		assertTrue(swap.offer(b, "b"));
		assertEquals("b", swap.take().shader());
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
		assertNull(swap.swapIfComplete());
		assertTrue(b.released);
		assertSame(a, swap.current());
		assertFalse(swap.report(swap.currentRevision()));

		buildAndSwap("d");
		assertTrue(swap.report(swap.currentRevision()));
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
	public void numbersShadersInTheOrderTheyWereRequested() {
		assertEquals(0, swap.requested());
		swap.offer("a");
		swap.offer("b");
		assertEquals(2, swap.requested());
	}

	@Test
	public void offersAChangeOfTheNewestShader() {
		swap.offerChanged(shader -> shader + "!");
		assertEquals(0, swap.requested());
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
		assertTrue(swap.canDraw(1));
		swap.built(a, new FakeBuild("a", false));
		assertTrue(swap.canDraw(1));
		swap.next().complete = true;
		swap.swapIfComplete();
		assertTrue(swap.canDraw(1));

		swap.offer("broken");
		swap.failed(swap.take());
		assertFalse(swap.canDraw(2));
		assertTrue(swap.canDraw(1));

		swap.offer("superseded");
		swap.offer("c");
		assertFalse(swap.canDraw(3));
		assertTrue(swap.canDraw(4));
	}

	// R12
	@Test
	public void targetsAreMadeBesideTheCurrentBuildsWhenTheyFit() {
		var a = buildAndSwap("a");
		var targets = new FakeTargets(a, ShaderSwap.Allocation.MADE);
		build("b", true);
		assertTrue(swap.makeNextTargets(targets));
		assertEquals(List.of("b"), targets.made);
		assertTrue(targets.released.isEmpty());
		assertTrue(targets.holds.contains(a));
	}

	// R12
	@Test
	public void targetsThatDoNotFitBesideTheCurrentBuildsAreMadeAfterThoseGo() {
		var a = buildAndSwap("a");
		var targets = new FakeTargets(a, ShaderSwap.Allocation.FAILED,
				ShaderSwap.Allocation.MADE);
		build("b", true);
		assertTrue(swap.makeNextTargets(targets));
		assertEquals(List.of("b", "b"), targets.made);
		assertEquals(List.of("a"), targets.released);
		assertSame(a, swap.current());
	}

	// R12
	@Test
	public void targetsThatDoNotFitEvenAloneLeaveNothingToDraw() {
		var a = buildAndSwap("a");
		var targets = new FakeTargets(a, ShaderSwap.Allocation.FAILED,
				ShaderSwap.Allocation.FAILED);
		build("b", true);
		assertFalse(swap.makeNextTargets(targets));
		assertEquals(List.of("b", "b"), targets.made);
		assertEquals(List.of("a"), targets.released);
		assertFalse(targets.holds.contains(a));
	}

	// R12
	@Test
	public void invalidTargetsLeaveTheCurrentBuildDrawing() {
		var a = buildAndSwap("a");
		var targets = new FakeTargets(a, ShaderSwap.Allocation.INVALID);
		build("b", true);
		assertFalse(swap.makeNextTargets(targets));
		assertEquals(List.of("b"), targets.made);
		assertTrue(targets.released.isEmpty());
		assertTrue(targets.holds.contains(a));
	}

	// R12
	@Test
	public void targetsAreMadeOnceWhenNoBuildHoldsAny() {
		var targets = new FakeTargets(null, ShaderSwap.Allocation.FAILED,
				ShaderSwap.Allocation.MADE);
		build("a", true);
		assertFalse(swap.makeNextTargets(targets));
		assertEquals(List.of("a"), targets.made);

		var b = buildAndSwap("b");
		targets = new FakeTargets(null, ShaderSwap.Allocation.FAILED,
				ShaderSwap.Allocation.MADE);
		build("c", true);
		assertFalse(swap.makeNextTargets(targets));
		assertEquals(List.of("c"), targets.made);
		assertEquals(List.of("b"), targets.released);
		assertSame(b, swap.current());
	}

	@Test
	public void withoutANextBuildNoTargetsAreMade() {
		buildAndSwap("a");
		var targets = new FakeTargets(null, ShaderSwap.Allocation.MADE);
		assertFalse(swap.makeNextTargets(targets));
		assertTrue(targets.made.isEmpty());
	}

	// Answers make() with the outcomes in order, then with FAILED; holds
	// the targets of the builds it made them for.
	private static final class FakeTargets
			implements ShaderSwap.Targets<FakeBuild> {
		final List<String> made = new ArrayList<>();
		final List<String> released = new ArrayList<>();
		final Set<FakeBuild> holds = new HashSet<>();
		private final ArrayDeque<ShaderSwap.Allocation> outcomes;

		FakeTargets(@Nullable FakeBuild holding,
				@NonNull ShaderSwap.Allocation... outcomes) {
			if (holding != null) {
				holds.add(holding);
			}
			this.outcomes = new ArrayDeque<>(List.of(outcomes));
		}

		@NonNull
		@Override
		public ShaderSwap.Allocation make(@NonNull FakeBuild build) {
			made.add(build.shader);
			var outcome = outcomes.isEmpty()
					? ShaderSwap.Allocation.FAILED
					: outcomes.poll();
			if (outcome == ShaderSwap.Allocation.MADE) {
				holds.add(build);
			}
			return outcome;
		}

		@Override
		public boolean release(@NonNull FakeBuild build) {
			released.add(build.shader);
			return holds.remove(build);
		}
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

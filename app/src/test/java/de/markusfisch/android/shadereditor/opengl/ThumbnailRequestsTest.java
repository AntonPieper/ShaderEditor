package de.markusfisch.android.shadereditor.opengl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class ThumbnailRequestsTest {
	private final ThumbnailRequests<String> requests = new ThumbnailRequests<>();
	private final ShaderSwap<String, ShaderSwapTest.FakeBuild> swap =
			new ShaderSwap<>();

	@Test
	public void askedBeforeTheFirstFrameWaitsForIt() {
		requests.add(1, "a");
		assertEquals(List.of(), requests.take());
		requests.onFrame(1);
		assertEquals(List.of("a"), requests.take());
		assertEquals(List.of(), requests.take());
	}

	@Test
	public void askedTwiceIsAnsweredByTheSameFrame() {
		requests.onFrame(1);
		requests.add(1, "a");
		requests.add(1, "b");
		assertEquals(List.of("a", "b"), requests.take());
	}

	@Test
	public void contextLostWhilePendingWaitsForTheNextFrame() {
		requests.add(1, "a");
		requests.onFramesLost();
		assertEquals(List.of(), requests.take());
		requests.onFrame(1);
		requests.onFramesLost();
		assertEquals(List.of(), requests.take());
		requests.onFrame(1);
		assertEquals(List.of("a"), requests.take());
	}

	@Test
	public void eachRevisionIsAnsweredOnlyByItsOwnFrame() {
		requests.add(2, "new");
		requests.add(1, "old");
		requests.onFrame(1);
		assertEquals(List.of("old"), requests.take());
		requests.onFrame(3);
		assertEquals(List.of(), requests.take());
		requests.onFrame(2);
		assertEquals(List.of("new"), requests.take());
	}

	// Save A while its newest edit waits for its textures, then select B.
	@Test
	public void savedShaderNeverGetsTheNextShadersPicture() {
		var a = build("a");
		a.complete = true;
		swap.swapIfComplete();
		build("a, edited");
		requests.add(swap.requested(), "save a");

		var b = build("b");
		assertEquals(List.of("save a"), requests.drop(swap::canDraw));
		b.complete = true;
		swap.swapIfComplete();
		requests.onFrame(swap.currentRevision().number());
		assertEquals(List.of(), requests.take());
	}

	@Test
	public void requestOfTheShaderDrawingSurvivesANewerOneThatWaits() {
		var a = build("a");
		a.complete = true;
		swap.swapIfComplete();
		requests.add(swap.requested(), "save a");
		build("b");
		assertEquals(List.of(), requests.drop(swap::canDraw));
		requests.onFrame(swap.currentRevision().number());
		assertEquals(List.of("save a"), requests.take());
	}

	@Test
	public void shaderThatFailsIsAnsweredWithNothing() {
		swap.offer("broken");
		requests.add(swap.requested(), "save");
		assertEquals(List.of(), requests.drop(swap::canDraw));
		swap.failed(swap.take());
		requests.add(swap.requested(), "save again");
		assertEquals(List.of("save", "save again"), requests.drop(swap::canDraw));
	}

	@Test
	public void planWhoseImagesFailIsAnsweredWithNothing() {
		var a = build("a");
		a.complete = true;
		swap.swapIfComplete();
		requests.add(swap.requested(), "save a");
		build("plan");
		requests.add(swap.requested(), "save plan");
		swap.nextFailed();
		assertEquals(List.of("save plan"), requests.drop(swap::canDraw));
		requests.onFrame(swap.currentRevision().number());
		assertEquals(List.of("save a"), requests.take());
	}

	@Test
	public void askedBeforeAnyShaderIsAnsweredWithNothing() {
		requests.add(swap.requested(), "save");
		assertEquals(List.of("save"), requests.drop(swap::canDraw));
	}

	@Test
	public void lostContextKeepsRequestsAndDetachingAnswersThem() {
		var a = build("a");
		a.complete = true;
		swap.swapIfComplete();
		requests.add(swap.requested(), "save");
		swap.contextLost();
		requests.onFramesLost();
		assertEquals(List.of(), requests.drop(swap::canDraw));
		assertEquals(List.of("save"), requests.close());
		assertEquals(List.of(), requests.close());
	}

	// Set A, ask for its thumbnail, set B before A is prepared: A's
	// request names A and is never answered with B's picture.
	@Test
	public void requestNamesTheShaderAskedForBeforeItIsPrepared() {
		var shown = build("shown");
		shown.complete = true;
		swap.swapIfComplete();
		int a = swap.request();
		requests.add(swap.requested(), "save a");
		int b = swap.request();
		assertFalse(swap.offer(a, "a"));
		assertTrue(swap.offer(b, "b"));
		assertEquals(List.of("save a"), requests.drop(swap::canDraw));
		var built = new ShaderSwapTest.FakeBuild("b", true);
		swap.built(swap.take(), built);
		swap.swapIfComplete();
		requests.onFrame(swap.currentRevision().number());
		assertEquals(List.of(), requests.take());
	}

	@Test
	public void requestForAShaderStillBeingPreparedWaitsForIt() {
		int a = swap.request();
		requests.add(swap.requested(), "save a");
		assertEquals(List.of(), requests.drop(swap::canDraw));
		swap.offer(a, "a");
		var built = new ShaderSwapTest.FakeBuild("a", true);
		swap.built(swap.take(), built);
		swap.swapIfComplete();
		assertEquals(List.of(), requests.drop(swap::canDraw));
		requests.onFrame(swap.currentRevision().number());
		assertEquals(List.of("save a"), requests.take());
	}

	// The view is detached while a thumbnail request is still on its way.
	@Test
	public void requestAfterClosingIsRefused() {
		requests.add(1, "before");
		assertEquals(List.of("before"), requests.close());
		assertFalse(requests.add(1, "late"));
		assertEquals(List.of(), requests.close());
		requests.open();
		assertTrue(requests.add(1, "attached again"));
	}

	// A resize after which the shader drawing gets no targets.
	@Test
	public void shaderThatLostItsTargetsIsAnsweredWithNothing() {
		var a = build("a");
		a.complete = true;
		swap.swapIfComplete();
		requests.onFrame(swap.currentRevision().number());
		requests.onFramesLost();
		requests.add(swap.requested(), "save a");
		assertEquals(List.of(), requests.drop(swap::canDraw));
		swap.currentFailed();
		assertEquals(List.of("save a"), requests.drop(swap::canDraw));
	}

	private ShaderSwapTest.FakeBuild build(String shader) {
		swap.offer(shader);
		var build = new ShaderSwapTest.FakeBuild(shader, false);
		swap.built(swap.take(), build);
		return build;
	}
}

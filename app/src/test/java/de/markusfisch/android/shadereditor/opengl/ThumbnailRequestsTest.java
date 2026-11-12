package de.markusfisch.android.shadereditor.opengl;

import static org.junit.Assert.assertEquals;

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
		requests.add(swap.offered(), "save a");

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
		requests.add(swap.offered(), "save a");
		build("b");
		assertEquals(List.of(), requests.drop(swap::canDraw));
		requests.onFrame(swap.currentRevision().number());
		assertEquals(List.of("save a"), requests.take());
	}

	@Test
	public void shaderThatFailsIsAnsweredWithNothing() {
		swap.offer("broken");
		requests.add(swap.offered(), "save");
		assertEquals(List.of(), requests.drop(swap::canDraw));
		swap.take();
		requests.add(swap.offered(), "save again");
		assertEquals(List.of("save", "save again"), requests.drop(swap::canDraw));
	}

	@Test
	public void planWhoseImagesFailIsAnsweredWithNothing() {
		var a = build("a");
		a.complete = true;
		swap.swapIfComplete();
		requests.add(swap.offered(), "save a");
		build("plan");
		requests.add(swap.offered(), "save plan");
		swap.nextFailed();
		assertEquals(List.of("save plan"), requests.drop(swap::canDraw));
		requests.onFrame(swap.currentRevision().number());
		assertEquals(List.of("save a"), requests.take());
	}

	@Test
	public void askedBeforeAnyShaderIsAnsweredWithNothing() {
		requests.add(swap.offered(), "save");
		assertEquals(List.of("save"), requests.drop(swap::canDraw));
	}

	@Test
	public void lostContextKeepsRequestsAndDetachingAnswersThem() {
		var a = build("a");
		a.complete = true;
		swap.swapIfComplete();
		requests.add(swap.offered(), "save");
		swap.contextLost();
		requests.onFramesLost();
		assertEquals(List.of(), requests.drop(swap::canDraw));
		assertEquals(List.of("save"), requests.takeAll());
		assertEquals(List.of(), requests.takeAll());
	}

	private ShaderSwapTest.FakeBuild build(String shader) {
		swap.offer(shader);
		var build = new ShaderSwapTest.FakeBuild(shader, false);
		swap.built(swap.take(), build);
		return build;
	}
}

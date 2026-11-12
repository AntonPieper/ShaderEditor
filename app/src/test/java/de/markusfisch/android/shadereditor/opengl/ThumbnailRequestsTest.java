package de.markusfisch.android.shadereditor.opengl;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.List;

public class ThumbnailRequestsTest {
	private final ThumbnailRequests<String> requests = new ThumbnailRequests<>();

	@Test
	public void askedBeforeTheFirstFrameWaitsForIt() {
		requests.add(1, "a");
		assertEquals(List.of(), requests.take(1));
		requests.onFrame();
		assertEquals(List.of("a"), requests.take(1));
		assertEquals(List.of(), requests.take(1));
	}

	@Test
	public void askedTwiceIsAnsweredByTheSameFrame() {
		requests.onFrame();
		requests.add(1, "a");
		requests.add(1, "b");
		assertEquals(List.of("a", "b"), requests.take(1));
	}

	@Test
	public void contextLostWhilePendingWaitsForTheNextFrame() {
		requests.add(1, "a");
		requests.onFramesLost();
		assertEquals(List.of(), requests.take(1));
		requests.onFrame();
		requests.onFramesLost();
		assertEquals(List.of(), requests.take(1));
		requests.onFrame();
		assertEquals(List.of("a"), requests.take(1));
	}

	@Test
	public void waitsUntilTheShaderSetBeforeItIsShown() {
		requests.onFrame();
		requests.add(2, "new");
		requests.add(1, "old");
		assertEquals(List.of("old"), requests.take(1));
		assertEquals(List.of("new"), requests.take(2));
	}

	@Test
	public void aNewerShaderAnswersAnOlderRequest() {
		requests.add(1, "a");
		requests.onFrame();
		assertEquals(List.of("a"), requests.take(3));
	}
}

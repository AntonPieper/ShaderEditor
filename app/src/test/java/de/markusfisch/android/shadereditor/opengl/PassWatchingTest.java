package de.markusfisch.android.shadereditor.opengl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

// F2: no call to a watcher starts once it was replaced, from another
// thread or from inside one of its own calls.
public class PassWatchingTest {
	private final List<String> heard = new ArrayList<>();

	// Clearing it while it hears its pictures stops the facts after them.
	@Test
	public void clearedInsideItsPicturesItHearsNoFacts() {
		var watching = new PassWatching();
		var watcher = new ShaderEngine.PassWatcher() {
			@Override
			public void onPictures(@NonNull List<PassPicture> pictures) {
				heard.add("pictures");
				watching.set(null);
			}

			@Override
			public void onFacts(@NonNull List<PassFacts> facts) {
				heard.add("facts");
			}
		};
		watching.set(watcher);
		assertTrue(watching.pictures(watcher, List.of()));
		assertFalse(watching.facts(watcher, List.of()));
		assertEquals(List.of("pictures"), heard);
	}

	// The frame comes before the pictures, under the same rule.
	@Test
	public void theFrameIsToldOnlyWhileItWatches() {
		var watching = new PassWatching();
		var watcher = new ShaderEngine.PassWatcher() {
			@Override
			public void onFrame(int frame) {
				heard.add("frame " + frame);
			}

			@Override
			public void onPictures(@NonNull List<PassPicture> pictures) {
				heard.add("pictures");
			}
		};
		watching.set(watcher);
		assertTrue(watching.frame(watcher, 3));
		watching.set(null);
		assertFalse(watching.frame(watcher, 4));
		assertEquals(List.of("frame 3"), heard);
	}

	// Replaced from another thread while a call is under way: the
	// replacing call waits for it, and none starts after.
	@Test
	public void replacedWhileItHearsItWaitsForTheCallToEnd()
			throws InterruptedException {
		var watching = new PassWatching();
		var inCall = new CountDownLatch(1);
		var release = new CountDownLatch(1);
		var watcher = new ShaderEngine.PassWatcher() {
			@Override
			public void onPictures(@NonNull List<PassPicture> pictures) {
				inCall.countDown();
				try {
					release.await(5, TimeUnit.SECONDS);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				synchronized (heard) {
					heard.add("pictures ended");
				}
			}
		};
		watching.set(watcher);
		var gl = new Thread(() -> watching.pictures(watcher, List.of()));
		gl.start();
		assertTrue(inCall.await(5, TimeUnit.SECONDS));
		var main = new Thread(() -> {
			watching.set(null);
			synchronized (heard) {
				heard.add("replaced");
			}
		});
		main.start();
		// It is still in the call: set() has not returned.
		Thread.sleep(100);
		synchronized (heard) {
			assertEquals(List.of(), heard);
		}
		release.countDown();
		gl.join(5000);
		main.join(5000);
		assertEquals(List.of("pictures ended", "replaced"), heard);
		assertFalse(watching.pictures(watcher, List.of()));
	}
}

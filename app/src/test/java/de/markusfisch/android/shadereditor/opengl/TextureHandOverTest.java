package de.markusfisch.android.shadereditor.opengl;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TextureHandOverTest {
	private static final class Result implements TextureHandOver.Recyclable {
		private boolean recycled;

		@Override
		public void recycle() {
			recycled = true;
		}
	}

	private final TextureHandOver<Result> handOver = new TextureHandOver<>();

	@Test
	public void handsOverTheCurrentLoadOnce() {
		assertNull(handOver.take());
		int generation = handOver.begin();
		var result = new Result();
		handOver.deliver(generation, result);
		assertSame(result, handOver.take());
		assertNull(handOver.take());
		assertFalse(result.recycled);
	}

	@Test
	public void recyclesASupersededLoad() {
		int first = handOver.begin();
		int second = handOver.begin();
		var stale = new Result();
		handOver.deliver(first, stale);
		assertTrue(stale.recycled);
		assertNull(handOver.take());
		var current = new Result();
		handOver.deliver(second, current);
		assertSame(current, handOver.take());
		assertFalse(current.recycled);
	}

	@Test
	public void recyclesWhatNobodyTookWhenALoadBegins() {
		var untaken = new Result();
		handOver.deliver(handOver.begin(), untaken);
		int generation = handOver.begin();
		assertTrue(untaken.recycled);
		assertNull(handOver.take());
		var current = new Result();
		handOver.deliver(generation, current);
		assertSame(current, handOver.take());
	}

	@Test
	public void cancelRecyclesWhatIsReadyAndWhatIsStillDecoding() {
		var ready = new Result();
		handOver.deliver(handOver.begin(), ready);
		handOver.cancel();
		assertTrue(ready.recycled);
		assertNull(handOver.take());

		int generation = handOver.begin();
		handOver.cancel();
		var late = new Result();
		handOver.deliver(generation, late);
		assertTrue(late.recycled);
		assertNull(handOver.take());
	}
}

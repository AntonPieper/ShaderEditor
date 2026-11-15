package de.markusfisch.android.shadereditor.widget;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Random;

import de.markusfisch.android.shadereditor.widget.PassRail.Form;
import de.markusfisch.android.shadereditor.widget.PassRail.Layout;
import de.markusfisch.android.shadereditor.widget.PassRail.Line;
import de.markusfisch.android.shadereditor.widget.PassRail.Read;

public class PassRailTest {
	@Test
	public void inkHasABadgeAndArrowsOnly() {
		// seed.frag (first frame), ink.frag, main.frag
		var layout = check(rows(3, 0), List.of(
				read(0, 1), last(1, 1), read(1, 2)), 2);
		assertEquals(0, layout.lanes());
		assertEquals(PassRail.MARGIN, layout.pictureLeft(), 0);
		assertTrue(layout.badge(1));
		assertFalse(layout.badge(2));
		assertEquals(2, count(layout, Form.ARROW));
	}

	@Test
	public void aSkipTakesOneLane() {
		var layout = check(rows(3), List.of(
				read(0, 1), read(1, 2), read(0, 2)), 2);
		assertEquals(1, layout.lanes());
		assertEquals(32, layout.pictureLeft(), 0);
		assertEquals(88, layout.textLeft(), 0);
	}

	@Test
	public void glowTrailsCrossOnceAsNoOrderAvoids() {
		// empty.frag (first frame), bufferA..D, main.frag
		var layout = check(rows(6, 0), List.of(
				read(0, 1), read(0, 2), last(1, 1), read(1, 2), last(2, 2),
				read(2, 3), read(3, 4), read(4, 5), last(4, 1)), 5);
		assertEquals(2, layout.lanes());
		assertEquals(1, layout.crossings());
		assertEquals(40, layout.pictureLeft(), 0);
	}

	@Test
	public void eightNestedSkipsDoNotCross() {
		var layout = check(rows(8), eight(), 7);
		assertEquals(3, layout.lanes());
		assertEquals(0, layout.crossings());
	}

	@Test
	public void eightInterleavedCrossOnce() {
		var reads = new ArrayList<Read>();
		for (int k = 0; k < 7; ++k) {
			reads.add(read(k, k + 1));
		}
		reads.add(read(0, 7));
		reads.add(read(1, 4));
		reads.add(read(3, 6));
		var layout = check(rows(8), reads, 7);
		assertEquals(3, layout.lanes());
		assertEquals(1, layout.crossings());
	}

	@Test
	public void hardGraphs() {
		// A pass reads itself.
		assertLanes(0, 0, rows(2), List.of(last(0, 0), read(0, 1)));
		// Two passes read each other's last frame.
		assertLanes(2, 0, rows(3), List.of(
				last(1, 0), last(0, 1), read(1, 2)));
		// A diamond.
		assertLanes(2, 1, rows(4), List.of(
				read(0, 1), read(0, 2), read(1, 3), read(2, 3)));
		// Fan-out of one to three.
		assertLanes(2, 0, rows(4), List.of(
				read(0, 1), read(0, 2), read(0, 3)));
		// Fan-in of three to one.
		assertLanes(2, 0, rows(4), List.of(
				read(0, 3), read(1, 3), read(2, 3)));
		// A skip over five rows next to a short one.
		var skip = new ArrayList<Read>();
		for (int k = 0; k < 6; ++k) {
			skip.add(read(k, k + 1));
		}
		skip.add(read(0, 6));
		skip.add(read(2, 4));
		assertLanes(2, 0, rows(7), skip);
		// A first frame seeding two passes.
		assertLanes(1, 0, rows(4, 0), List.of(read(0, 1), read(0, 2),
				last(1, 1), last(2, 2), read(1, 2), read(2, 3)));
	}

	@Test
	public void beyondFourLanesOnlyTheShownRowsLinesStay() {
		var reads = fiveLanes();
		var output = check(rows(9), reads, 8);
		assertTrue(output.collapsed());
		assertEquals(PassRail.MAX_LANES, output.lanes());
		assertEquals(3, output.used());
		assertEquals(0, output.crossings());
		var up2 = check(rows(9), reads, 6);
		assertEquals(PassRail.MAX_LANES, up2.lanes());
		assertEquals(1, up2.used());
		// The list does not move.
		assertEquals(output.pictureLeft(), up2.pictureLeft(), 0);
		for (var line : up2.lines()) {
			if (line.form() == Form.LANE || line.form() == Form.ARROW) {
				assertTrue(line.read().producer() == 6 ||
						line.read().reader() == 6);
			}
		}
		assertFalse(up2.touches(0));
		assertTrue(up2.touches(5));
	}

	@Test
	public void aRowThatAloneNeedsTooManyLanesDrawsNone() {
		var reads = new ArrayList<Read>();
		for (int k = 0; k < 6; ++k) {
			reads.add(read(k, 7));
		}
		reads.add(read(6, 7));
		var layout = check(rows(8), reads, 7);
		assertTrue(layout.listed());
		assertEquals(0, count(layout, Form.LANE));
		assertEquals(PassRail.MAX_LANES, layout.lanes());
	}

	@Test
	public void aReadListedTwiceIsOneLine() {
		var layout = check(rows(3), List.of(
				read(0, 2), read(0, 2), read(0, 1)), 2);
		assertEquals(2, layout.lines().size());
		assertEquals(1, layout.lanes());
	}

	@Test
	public void lastFrameFromBelowIsALaneEvenNextToIt() {
		var layout = check(rows(2), List.of(last(1, 0), read(0, 1)), 1);
		assertEquals(1, layout.lanes());
		assertEquals(Form.LANE, layout.lines().get(0).form());
	}

	// rail.py's fuzz: random graphs far denser than real shaders; every
	// layout passes the checks, crosses at least as often as no order
	// avoids, rarely more, and is quick.
	@Test
	public void randomGraphs() {
		var random = new Random(7);
		int graphs = 0;
		int extra = 0;
		long slowest = 0;
		for (int n = 2; n <= 12; ++n) {
			for (int trial = 0; trial < 400; ++trial) {
				var reads = new ArrayList<Read>();
				for (int j = 1; j < n; ++j) {
					if (random.nextDouble() < .8) {
						reads.add(read(j - 1, j));
					}
					for (int i = 0; i < j - 1; ++i) {
						if (random.nextDouble() < .15) {
							reads.add(read(i, j));
						}
					}
				}
				for (int i = 0; i < n; ++i) {
					if (random.nextDouble() < .3) {
						reads.add(last(i, i));
					}
					for (int j = 0; j < i; ++j) {
						if (random.nextDouble() < .05) {
							reads.add(last(i, j));
						}
					}
				}
				var first = new boolean[n];
				first[0] = random.nextDouble() < .2;
				int selected = random.nextInt(n);
				long start = System.nanoTime();
				var layout = check(first, reads, selected);
				slowest = Math.max(slowest, System.nanoTime() - start);
				int unavoidable = unavoidable(layout);
				assertTrue(layout.crossings() >= unavoidable);
				if (layout.crossings() > unavoidable) {
					++extra;
				}
				++graphs;
			}
		}
		assertEquals(4400, graphs);
		assertTrue("extra crossings in " + extra + " graphs", extra <= 22);
		assertTrue("slowest " + slowest / 1000000 + " ms",
				slowest < 500_000_000L);
	}

	private static void assertLanes(int lanes, int crossings,
			boolean[] first, List<Read> reads) {
		var layout = check(first, reads, first.length - 1);
		assertEquals(lanes, layout.lanes());
		assertEquals(crossings, layout.crossings());
	}

	// Lays out and checks the drawing, as rail.py's verify() does, with
	// the selected row grown as the sheet grows it.
	private static Layout check(boolean[] first, List<Read> reads,
			int selected) {
		var layout = PassRail.layout(first, reads, selected);
		verify(layout, first, selected, 0);
		verify(layout, first, selected, 68);
		return layout;
	}

	private static void verify(Layout layout, boolean[] first, int grown,
			float growth) {
		int rows = first.length;
		var tops = new float[rows];
		float y = 0;
		for (int r = 0; r < rows; ++r) {
			tops[r] = y;
			y += PassRail.ROW + (r == grown ? growth : 0);
		}
		float left = layout.pictureLeft();
		var paths = new ArrayList<float[]>();
		var ends = new HashMap<Integer, List<Float>>();
		int lanes = 0;
		for (Line line : layout.lines()) {
			var read = line.read();
			if (line.form() == Form.ARROW) {
				assertEquals(read.producer() + 1, read.reader());
				float gap = box(read.reader(), first, tops, left)[1] -
						box(read.producer(), first, tops, left)[3];
				assertTrue("no room for an arrow", gap >= 8);
				continue;
			} else if (line.form() != Form.LANE) {
				continue;
			}
			++lanes;
			float x = layout.laneX(line.lane());
			assertTrue("a lane inside the pictures",
					x <= left - PassRail.STUB);
			assertTrue("a lane outside the margin", x >= PassRail.MARGIN);
			float y1 = port(read.producer(), line.producerPort(),
					line.producerPorts(), first, tops, left);
			float y2 = port(read.reader(), line.readerPort(),
					line.readerPorts(), first, tops, left);
			ends.computeIfAbsent(read.producer(), k -> new ArrayList<>()).add(y1);
			ends.computeIfAbsent(read.reader(), k -> new ArrayList<>()).add(y2);
			paths.add(new float[]{x, y1, y2,
					box(read.producer(), first, tops, left)[0],
					box(read.reader(), first, tops, left)[0]});
		}
		assertTrue(lanes == 0 || layout.used() > 0);
		for (var ys : ends.values()) {
			ys.sort(Float::compare);
			for (int k = 1; k < ys.size(); ++k) {
				assertTrue("ports too close", ys.get(k) - ys.get(k - 1) >= 8);
			}
		}
		// Nothing right of the arrows' heads reaches the text.
		assertTrue(left + 24 < layout.textLeft() - 12);
		for (int a = 0; a < paths.size(); ++a) {
			for (int b = a + 1; b < paths.size(); ++b) {
				float[] pa = paths.get(a);
				float[] pb = paths.get(b);
				if (pa[0] == pb[0]) {
					float la = Math.min(pa[1], pa[2]);
					float ha = Math.max(pa[1], pa[2]);
					float lb = Math.min(pb[1], pb[2]);
					float hb = Math.max(pb[1], pb[2]);
					assertTrue("two reads share a lane", ha < lb || hb < la);
				}
				float[][] sa = {{pa[1], pa[0], pa[3]}, {pa[2], pa[0], pa[4]}};
				float[][] sb = {{pb[1], pb[0], pb[3]}, {pb[2], pb[0], pb[4]}};
				for (float[] u : sa) {
					for (float[] v : sb) {
						if (Math.abs(u[0] - v[0]) < 2) {
							assertTrue("two stubs overlap",
									u[2] <= v[1] || v[2] <= u[1]);
						}
					}
				}
			}
		}
	}

	private static float port(int row, int port, int ports, boolean[] first,
			float[] tops, float left) {
		float[] box = box(row, first, tops, left);
		float y = PassRail.portY(port, ports, box[1], box[3]);
		assertTrue("a stub misses its picture", box[1] < y && y < box[3]);
		return y;
	}

	private static float[] box(int row, boolean[] first, float[] tops,
			float left) {
		float size = first[row] ? PassRail.FIRST_PICTURE : PassRail.PICTURE;
		float top = tops[row] + (PassRail.ROW - size) / 2;
		float l = left + (PassRail.PICTURE - size) / 2;
		return new float[]{l, top, l + size, top + size};
	}

	// Pairs of drawn lane reads whose spans interleave: each crosses
	// once in any drawing of lanes on one side.
	private static int unavoidable(Layout layout) {
		var spans = new ArrayList<int[]>();
		for (var line : layout.lines()) {
			if (line.form() == Form.LANE) {
				var read = line.read();
				spans.add(new int[]{Math.min(read.producer(), read.reader()),
						Math.max(read.producer(), read.reader())});
			}
		}
		int count = 0;
		for (int a = 0; a < spans.size(); ++a) {
			for (int b = a + 1; b < spans.size(); ++b) {
				int[] s = spans.get(a);
				int[] t = spans.get(b);
				if ((s[0] < t[0] && t[0] < s[1] && s[1] < t[1]) ||
						(t[0] < s[0] && s[0] < t[1] && t[1] < s[1])) {
					++count;
				}
			}
		}
		return count;
	}

	private static List<Read> eight() {
		var reads = new ArrayList<Read>();
		for (int k = 0; k < 7; ++k) {
			reads.add(read(k, k + 1));
		}
		reads.add(read(3, 5));
		reads.add(read(2, 6));
		reads.add(read(0, 7));
		return reads;
	}

	private static List<Read> fiveLanes() {
		// scene, bright, down1, down2, down3, up3, up2, up1, main
		var reads = new ArrayList<Read>();
		for (int k = 0; k < 8; ++k) {
			reads.add(read(k, k + 1));
		}
		reads.add(read(4, 6));
		reads.add(read(3, 7));
		reads.add(read(2, 8));
		reads.add(read(0, 8));
		reads.add(read(1, 5));
		reads.add(last(8, 1));
		return reads;
	}

	private static int count(Layout layout, Form form) {
		int n = 0;
		for (var line : layout.lines()) {
			if (line.form() == form) {
				++n;
			}
		}
		return n;
	}

	private static boolean[] rows(int n, int... firstFrames) {
		var first = new boolean[n];
		for (int r : firstFrames) {
			first[r] = true;
		}
		return first;
	}

	private static Read read(int producer, int reader) {
		return new Read(producer, reader, false);
	}

	private static Read last(int producer, int reader) {
		return new Read(producer, reader, true);
	}
}

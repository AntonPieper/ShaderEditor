package de.markusfisch.android.shadereditor.widget;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Where the passes sheet draws which pass reads which, as the lanes of
 * a commit graph: the pictures of the rows are the nodes, in drawing
 * order. A pass that reads its own last frame gets a badge on its
 * picture; a read of this frame or of a first frame from the row
 * directly above is a short arrow between the two pictures; every
 * other read runs in a vertical lane left of the pictures, 8 dp apart,
 * at most four. The fewest lanes are used, ordered so that lines cross
 * only where no order avoids it, always at a right angle. Beyond four
 * lanes only the lines of the shown row are drawn, with four lanes
 * reserved so that showing another row never moves the list; beyond
 * that no lane is drawn.
 *
 * <p>Pure: rows and reads in, a layout out. Sizes are in dp.
 */
public final class PassRail {
	public static final int MAX_LANES = 4;
	public static final float ROW = 56;
	public static final float PICTURE = 40;
	public static final float FIRST_PICTURE = 32;
	public static final float MARGIN = 16;
	public static final float LANE = 8;
	// From the innermost lane to the pictures.
	public static final float STUB = 16;
	// From the pictures' left to the text.
	public static final float TEXT = 56;
	// A search that tries more complete assignments stops with the best
	// so far; real shaders stay far below.
	private static final int MOST_TRIES = 50000;

	/**
	 * A read: reader reads producer, its last frame or not. A first
	 * frame is read like this frame.
	 */
	public record Read(int producer, int reader, boolean last) {
	}

	public enum Form {
		/** The row reads its own last frame: a badge, no line. */
		BADGE,
		/** From the picture directly above, in the pictures' column. */
		ARROW,
		/** In a lane. */
		LANE,
		/** Not drawn: more lanes would be needed than there are. */
		HIDDEN
	}

	/**
	 * How one read is drawn. A lane's ends meet the pictures at ports,
	 * port of ports along the left edge, see {@link #portY}.
	 */
	public record Line(@NonNull Read read, @NonNull Form form, int lane,
			int producerPort, int producerPorts,
			int readerPort, int readerPorts) {
	}

	/**
	 * @param lanes     the lanes the pictures leave room for
	 * @param used      the lanes drawn, numbered from the inside
	 * @param collapsed only the shown row's lines are drawn
	 * @param listed    collapsed, and even those need too many lanes
	 * @param lines     one for every distinct read, in the order given
	 * @param crossings where a line's end crosses another's lane
	 */
	public record Layout(int lanes, int used, boolean collapsed,
			boolean listed, @NonNull List<Line> lines, int crossings) {
		/** The left of a pass's picture. */
		public float pictureLeft() {
			return PassRail.pictureLeft(lanes);
		}

		/** The x of lane, 0 the innermost. */
		public float laneX(int lane) {
			return pictureLeft() - STUB - LANE * lane;
		}

		public float textLeft() {
			return pictureLeft() + TEXT;
		}

		/** Whether row reads its own last frame. */
		public boolean badge(int row) {
			for (var line : lines) {
				if (line.form == Form.BADGE && line.read.reader == row) {
					return true;
				}
			}
			return false;
		}

		/** Whether a line that is drawn ends at row. */
		public boolean touches(int row) {
			for (var line : lines) {
				if ((line.form == Form.ARROW || line.form == Form.LANE) &&
						(line.read.reader == row ||
								line.read.producer == row)) {
					return true;
				}
			}
			return false;
		}
	}

	private PassRail() {
	}

	/** The left of a pass's picture with room for lanes lanes. */
	public static float pictureLeft(int lanes) {
		return lanes == 0 ? MARGIN : MARGIN + LANE * (lanes - 1) + STUB;
	}

	/**
	 * The y of port of ports on a picture from top to bottom: up to
	 * three at even divisions, four from 4 dp under the top to 4 dp
	 * over the bottom, so that 8 dp arrowheads never meet.
	 */
	public static float portY(int port, int ports, float top, float bottom) {
		float height = bottom - top;
		return ports <= 3
				? top + (port + 1) * height / (ports + 1)
				: top + 4 + port * (height - 8) / (ports - 1);
	}

	/**
	 * Lay out reads between rows, top to bottom.
	 *
	 * @param first    which rows are first frames, drawn smaller
	 * @param reads    what reads what, by row
	 * @param selected the shown row, whose lines stay when not all fit
	 */
	@NonNull
	public static Layout layout(@NonNull boolean[] first,
			@NonNull List<Read> reads, int selected) {
		int rows = first.length;
		var distinct = new ArrayList<Read>();
		for (var read : reads) {
			if (!distinct.contains(read)) {
				distinct.add(read);
			}
		}
		int count = distinct.size();
		var forms = new Form[count];
		var laneReads = new ArrayList<Integer>();
		for (int m = 0; m < count; ++m) {
			var read = distinct.get(m);
			if (read.producer == read.reader) {
				forms[m] = Form.BADGE;
			} else if (!read.last && read.reader == read.producer + 1) {
				forms[m] = Form.ARROW;
			} else {
				forms[m] = Form.LANE;
				laneReads.add(m);
			}
		}
		boolean collapsed = false;
		boolean listed = false;
		if (fewest(distinct, laneReads, rows) > MAX_LANES) {
			collapsed = true;
			int keep = selected >= 0 && selected < rows ? selected : rows - 1;
			var kept = new ArrayList<Integer>();
			for (int m = 0; m < count; ++m) {
				var read = distinct.get(m);
				boolean touches = read.producer == keep || read.reader == keep;
				if (forms[m] == Form.BADGE) {
					continue;
				} else if (!touches) {
					forms[m] = Form.HIDDEN;
				} else if (forms[m] == Form.LANE) {
					kept.add(m);
				}
			}
			laneReads = kept;
			if (fewest(distinct, laneReads, rows) > MAX_LANES) {
				for (int m : laneReads) {
					forms[m] = Form.HIDDEN;
				}
				laneReads = new ArrayList<>();
				listed = true;
			}
		}
		var search = new Search(first, distinct, laneReads);
		int[] lanes = search.best();
		// Renumbered from the inside, with no empty lane between.
		int used = 0;
		var renumbered = new int[lanes.length];
		for (int lane = 0; lane < MAX_LANES; ++lane) {
			boolean any = false;
			for (int e = 0; e < lanes.length; ++e) {
				if (lanes[e] == lane) {
					renumbered[e] = used;
					any = true;
				}
			}
			if (any) {
				++used;
			}
		}
		int reserved = collapsed ? MAX_LANES : used;
		var ports = search.ports(renumbered);
		var lines = new ArrayList<Line>();
		for (int m = 0; m < count; ++m) {
			int e = laneReads.indexOf(m);
			if (e < 0) {
				lines.add(new Line(distinct.get(m), forms[m], -1,
						-1, 0, -1, 0));
			} else {
				lines.add(new Line(distinct.get(m), Form.LANE,
						renumbered[e], ports[e][0], ports[e][1],
						ports[e][2], ports[e][3]));
			}
		}
		return new Layout(reserved, used, collapsed, listed, lines,
				search.crossings(renumbered));
	}

	// The most lane reads that cover one row: the fewest lanes they
	// need, as spans are intervals.
	private static int fewest(@NonNull List<Read> reads,
			@NonNull List<Integer> laneReads, int rows) {
		int most = 0;
		for (int row = 0; row < rows; ++row) {
			int covering = 0;
			for (int m : laneReads) {
				var read = reads.get(m);
				if (Math.min(read.producer, read.reader) <= row &&
						row <= Math.max(read.producer, read.reader)) {
					++covering;
				}
			}
			most = Math.max(most, covering);
		}
		return most;
	}

	// Every assignment of lanes to the lane reads, by backtracking, and
	// the best by: crossings, lanes used, last-frame reads inside, long
	// reads inside, each fewer first.
	private static final class Search {
		private final boolean[] first;
		private final int[] lo;
		private final int[] hi;
		private final int[] producer;
		private final int[] reader;
		private final boolean[] last;
		private final int[] order;
		private final int[] lanes;
		private int[] best;
		private long[] bestKey;
		private int lanesNow;
		private int tries;

		Search(@NonNull boolean[] first, @NonNull List<Read> reads,
				@NonNull List<Integer> laneReads) {
			this.first = first;
			int n = laneReads.size();
			lo = new int[n];
			hi = new int[n];
			producer = new int[n];
			reader = new int[n];
			last = new boolean[n];
			for (int e = 0; e < n; ++e) {
				var read = reads.get(laneReads.get(e));
				producer[e] = read.producer;
				reader[e] = read.reader;
				last[e] = read.last;
				lo[e] = Math.min(read.producer, read.reader);
				hi[e] = Math.max(read.producer, read.reader);
			}
			var sorted = new Integer[n];
			for (int e = 0; e < n; ++e) {
				sorted[e] = e;
			}
			Arrays.sort(sorted, (a, b) -> lo[a] != lo[b]
					? Integer.compare(lo[a], lo[b])
					: Integer.compare(hi[a], hi[b]));
			order = new int[n];
			for (int e = 0; e < n; ++e) {
				order[e] = sorted[e];
			}
			lanes = new int[n];
		}

		@NonNull
		int[] best() {
			int n = lo.length;
			if (n == 0) {
				return new int[0];
			}
			int need = 0;
			for (int e = 0; e < n; ++e) {
				int covering = 0;
				for (int f = 0; f < n; ++f) {
					if (lo[f] <= lo[e] && lo[e] <= hi[f]) {
						++covering;
					}
				}
				need = Math.max(need, covering);
			}
			for (lanesNow = need; lanesNow <= MAX_LANES; ++lanesNow) {
				place(0);
			}
			return best;
		}

		private void place(int position) {
			if (tries >= MOST_TRIES && best != null) {
				return;
			}
			if (position == order.length) {
				++tries;
				score();
				return;
			}
			int e = order[position];
			for (int lane = 0; lane < lanesNow; ++lane) {
				if (free(position, e, lane)) {
					lanes[e] = lane;
					place(position + 1);
				}
			}
		}

		// Whether lane is free over e's span from the reads placed.
		private boolean free(int position, int e, int lane) {
			for (int p = 0; p < position; ++p) {
				int q = order[p];
				if (lanes[q] == lane && hi[q] >= lo[e] && lo[q] <= hi[e]) {
					return false;
				}
			}
			return true;
		}

		private void score() {
			int n = lanes.length;
			long used = 0;
			long lastInside = 0;
			long longInside = 0;
			boolean[] taken = new boolean[MAX_LANES];
			for (int e = 0; e < n; ++e) {
				if (!taken[lanes[e]]) {
					taken[lanes[e]] = true;
					++used;
				}
				int inside = lanesNow - 1 - lanes[e];
				if (last[e]) {
					lastInside += inside;
				}
				longInside += (long) (hi[e] - lo[e]) * inside;
			}
			long[] key = {crossings(lanes), used, lastInside, longInside};
			if (bestKey == null || less(key, bestKey)) {
				bestKey = key;
				best = lanes.clone();
			}
		}

		private static boolean less(@NonNull long[] a, @NonNull long[] b) {
			for (int i = 0; i < a.length; ++i) {
				if (a[i] != b[i]) {
					return a[i] < b[i];
				}
			}
			return false;
		}

		// For every lane read: its port on the producer, the ports
		// there, its port on the reader, the ports there.
		@NonNull
		int[][] ports(@NonNull int[] lanes) {
			int n = lanes.length;
			var ports = new int[n][4];
			var rows = new ArrayList<Integer>();
			for (int e = 0; e < n; ++e) {
				if (!rows.contains(producer[e])) {
					rows.add(producer[e]);
				}
				if (!rows.contains(reader[e])) {
					rows.add(reader[e]);
				}
			}
			for (int row : rows) {
				// Ends whose other end lies above, the inner lane
				// highest; then those whose other end lies below, the
				// outer lane highest.
				var ends = new ArrayList<int[]>();
				for (int e = 0; e < n; ++e) {
					if (producer[e] == row) {
						ends.add(new int[]{e, 0, reader[e] < row ? 0 : 1});
					}
					if (reader[e] == row) {
						ends.add(new int[]{e, 2, producer[e] < row ? 0 : 1});
					}
				}
				var sorted = ends.toArray(new int[0][]);
				Arrays.sort(sorted, (a, b) -> a[2] != b[2]
						? Integer.compare(a[2], b[2])
						: a[2] == 0
								? Integer.compare(lanes[a[0]], lanes[b[0]])
								: Integer.compare(lanes[b[0]], lanes[a[0]]));
				for (int k = 0; k < sorted.length; ++k) {
					ports[sorted[k][0]][sorted[k][1]] = k;
					ports[sorted[k][0]][sorted[k][1] + 1] = sorted.length;
				}
			}
			return ports;
		}

		// Where an end of one read crosses another's lane, counted in a
		// model of rows of one height: the order of ends does not
		// depend on the heights.
		int crossings(@NonNull int[] lanes) {
			int n = lanes.length;
			var ports = ports(lanes);
			var y1 = new float[n];
			var y2 = new float[n];
			for (int e = 0; e < n; ++e) {
				y1[e] = modelPort(producer[e], ports[e][0], ports[e][1]);
				y2[e] = modelPort(reader[e], ports[e][2], ports[e][3]);
			}
			int crossings = 0;
			for (int a = 0; a < n; ++a) {
				for (int b = 0; b < n; ++b) {
					// An end of a runs from its lane to the pictures,
					// over the lanes inside it.
					if (a == b || lanes[b] >= lanes[a]) {
						continue;
					}
					float low = Math.min(y1[b], y2[b]);
					float high = Math.max(y1[b], y2[b]);
					if (low < y1[a] && y1[a] < high) {
						++crossings;
					}
					if (low < y2[a] && y2[a] < high) {
						++crossings;
					}
				}
			}
			return crossings;
		}

		private float modelPort(int row, int port, int ports) {
			float size = first[row] ? FIRST_PICTURE : PICTURE;
			float top = row * ROW + (ROW - size) / 2;
			return portY(port, ports, top, top + size);
		}
	}
}

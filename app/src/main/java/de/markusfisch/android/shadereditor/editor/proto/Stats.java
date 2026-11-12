package de.markusfisch.android.shadereditor.editor.proto;

import android.util.Log;
import android.view.FrameMetrics;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * THROWAWAY PROTOTYPE (proto/editor-core). Collects the time of every
 * keystroke and every frame and writes them to the log.
 */
final class Stats {
	private static final String TAG = ProtoEditorView.TAG;

	private final List<long[]> keys = new ArrayList<>();
	private final List<long[]> keyFrames = new ArrayList<>();
	private final List<long[]> otherFrames = new ArrayList<>();
	private final List<Long> keyDrawEnds = new ArrayList<>();
	private final List<Long> draws = new ArrayList<>();

	synchronized void note(String text) {
		Log.i(TAG, "note " + text);
	}

	synchronized void draw(long nanos, int lines) {
		draws.add(nanos);
	}

	/** One keystroke: edit, the draw that showed it, and key to draw end. */
	synchronized void key(String label, long edit, long draw, long toDrawEnd,
			int lines) {
		keys.add(new long[]{edit, draw, toDrawEnd});
		keyDrawEnds.add(System.nanoTime());
		Log.i(TAG, "key " + label + " edit=" + edit / 1000 + "us draw=" +
				draw / 1000 + "us keyToDrawEnd=" + toDrawEnd / 1000 +
				"us lines=" + lines);
	}

	synchronized void frame(FrameMetrics m) {
		long input = m.getMetric(FrameMetrics.INPUT_HANDLING_DURATION);
		long anim = m.getMetric(FrameMetrics.ANIMATION_DURATION);
		long layout = m.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION);
		long draw = m.getMetric(FrameMetrics.DRAW_DURATION);
		long sync = m.getMetric(FrameMetrics.SYNC_DURATION);
		long total = m.getMetric(FrameMetrics.TOTAL_DURATION);
		long vsync = m.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP);
		long main = input + anim + layout + draw + sync;
		boolean ofKey = false;
		for (int i = 0; i < keyDrawEnds.size(); ++i) {
			long end = keyDrawEnds.get(i);
			if (end >= vsync && end <= vsync + total + 1_000_000) {
				keyDrawEnds.remove(i);
				ofKey = true;
				break;
			}
		}
		(ofKey ? keyFrames : otherFrames).add(new long[]{main, total});
		if (ProtoActivity.trace) {
			Log.i(TAG, "frame " + (ofKey ? "key" : "other") + " main=" +
					main / 1000 + "us (input=" + input / 1000 + " anim=" +
					anim / 1000 + " layout=" + layout / 1000 + " draw=" +
					draw / 1000 + " sync=" + sync / 1000 + ") total=" +
					total / 1000 + "us");
		}
	}

	synchronized void reset() {
		keys.clear();
		keyFrames.clear();
		otherFrames.clear();
		keyDrawEnds.clear();
		draws.clear();
	}

	synchronized void summary(String name) {
		Log.i(TAG, "summary " + name + " keys=" + keys.size() +
				" edit " + of(keys, 0) + " | onDraw " + of(keys, 1) +
				" | keyToDrawEnd " + of(keys, 2));
		Log.i(TAG, "summary " + name + " keyFrames=" + keyFrames.size() +
				" mainThread " + of(keyFrames, 0) + " | total " +
				of(keyFrames, 1));
		Log.i(TAG, "summary " + name + " otherFrames=" + otherFrames.size() +
				" mainThread " + of(otherFrames, 0) + " | total " +
				of(otherFrames, 1));
		long[] d = new long[draws.size()];
		for (int i = 0; i < d.length; ++i) {
			d[i] = draws.get(i);
		}
		Log.i(TAG, "summary " + name + " onDraw calls=" + d.length + " " +
				percentiles(d));
	}

	private static String of(List<long[]> rows, int column) {
		long[] values = new long[rows.size()];
		for (int i = 0; i < values.length; ++i) {
			values[i] = rows.get(i)[column];
		}
		return percentiles(values);
	}

	private static String percentiles(long[] values) {
		if (values.length == 0) {
			return "n/a";
		}
		Arrays.sort(values);
		return "p50=" + values[values.length / 2] / 1000 + "us p90=" +
				values[Math.min(values.length - 1, values.length * 9 / 10)] /
						1000 + "us max=" + values[values.length - 1] / 1000 +
				"us";
	}
}

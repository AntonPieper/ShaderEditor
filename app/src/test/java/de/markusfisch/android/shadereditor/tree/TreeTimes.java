package de.markusfisch.android.shadereditor.tree;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.Random;

/**
 * Times the reading of texts and the re-reading after one key, on
 * this machine's JVM. Not a test: tools/tree/times.sh runs it with
 * the files to read.
 */
public final class TreeTimes {
	private TreeTimes() {
	}

	public static void main(String[] args) throws Exception {
		for (String arg : args) {
			String text = Files.readString(Path.of(arg),
					StandardCharsets.ISO_8859_1).replace("\r\n", "\n");
			int runs = text.length() > 300_000 ? 20 : 300;
			for (int i = 0; i < runs; ++i) {
				ShaderTree.read(text);
			}
			long[] whole = new long[runs];
			ShaderTree tree = null;
			for (int i = 0; i < runs; ++i) {
				long start = System.nanoTime();
				tree = ShaderTree.read(text);
				whole[i] = System.nanoTime() - start;
			}
			Arrays.sort(whole);
			int tokens = 0;
			int largest = 0;
			for (Piece piece : tree.pieces) {
				tokens += piece.raw.size;
				largest = Math.max(largest, piece.length);
			}
			System.out.printf(Locale.ROOT, "%s: %d chars, %d tokens, %d pieces " +
							"(largest %d chars)%n  read whole: %.3f ms%n",
					Path.of(arg).getFileName(), text.length(), tokens,
					tree.pieces.length, largest, whole[runs / 2] / 1e6);
			keys("one key anywhere", text, tree);
			// A block that is being typed: a closing brace is missing
			// from a tenth of the text on.
			int brace = text.indexOf('}', text.length() / 10);
			if (brace >= 0) {
				String open = text.substring(0, brace) + text.substring(brace + 1);
				keys("one key while a brace is open", open, ShaderTree.read(open));
			}
		}
	}

	private static void keys(String what, String text, ShaderTree tree) {
		Random random = new Random(1);
		int keys = 400;
		long[] times = new long[keys];
		long reused = 0;
		long pieces = 0;
		for (int round = 0; round < 3; ++round) {
			for (int i = 0; i < keys; ++i) {
				int at = random.nextInt(text.length());
				String typed = text.substring(0, at) + "x" + text.substring(at);
				long start = System.nanoTime();
				ShaderTree after = tree.edit(typed, at, at, at + 1);
				times[i] = System.nanoTime() - start;
				reused += after.reused;
				pieces += after.pieces.length;
			}
		}
		Arrays.sort(times);
		System.out.printf(Locale.ROOT, "  %s: median %.3f ms, 90%% %.3f ms, 99%% " +
						"%.3f ms, largest %.3f ms; %.1f%% of the pieces taken over%n",
				what, times[keys / 2] / 1e6, times[keys * 9 / 10] / 1e6,
				times[keys * 99 / 100] / 1e6, times[keys - 1] / 1e6,
				100.0 * reused / pieces);
	}
}

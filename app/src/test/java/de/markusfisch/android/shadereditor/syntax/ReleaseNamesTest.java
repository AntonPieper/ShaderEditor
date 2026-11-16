package de.markusfisch.android.shadereditor.syntax;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

/**
 * The editor that release builds ship completes the names of what a
 * text declares and colours its struct names, read from the tokens of
 * the whole text. The reader behind that is shared with everything
 * that is built on it since, so a change to it reaches release.
 *
 * This compares the names it reads now with the names it read at a
 * commit before such changes, kept in release-names/names.txt, for
 * the shaders release bundles and for damaged variants of them: no
 * name that was read then may be lost now. Names may be gained.
 *
 * The file is written by {@link #main}, run on the classes of the
 * commit to compare with; its first line says which that was.
 */
public class ReleaseNamesTest {
	private static final String NAMES = "/release-names/names.txt";
	private static final int DAMAGED = 3000;

	@Test
	public void noNameReadBeforeIsLost() throws IOException {
		List<String> kept = new ArrayList<>();
		try (InputStream in = ReleaseNamesTest.class.getResourceAsStream(
				NAMES); BufferedReader reader = new BufferedReader(
				new InputStreamReader(in, StandardCharsets.UTF_8))) {
			// The first line says where the names are from.
			reader.readLine();
			for (String line; (line = reader.readLine()) != null; ) {
				kept.add(line);
			}
		}
		List<String> texts = texts(samples());
		int compared = 0;
		int gained = 0;
		List<String> lost = new ArrayList<>();
		for (int i = 0, n = Math.min(kept.size(), texts.size()); i < n;
				++i) {
			String[] line = kept.get(i).split("\t", -1);
			String text = texts.get(i);
			if (!line[0].equals(Integer.toHexString(text.hashCode()))) {
				// Not the text the names were read from: a shader was
				// changed since.
				continue;
			}
			++compared;
			Set<String> now = names(text);
			int before = 0;
			for (String name : line[1].split(" ")) {
				if (name.isEmpty()) {
					continue;
				}
				++before;
				if (!now.contains(name)) {
					lost.add(name + " in text " + i + ":\n" + text);
				}
			}
			gained += now.size() - before;
		}
		assertEquals(lost.size() + " names lost, the first: " +
				(lost.isEmpty() ? "" : lost.get(0)), 0, lost.size());
		// Most texts are still the ones the names were read from; if
		// not, the file is to be written again, see the class.
		assertTrue(compared + " of " + texts.size() + " compared",
				compared * 10 >= texts.size() * 9);
		// What was gained is no error; it is told so that a change
		// that gains or stops gaining is seen.
		System.out.println("release names: " + compared +
				" texts compared, " + gained + " names gained");
	}

	/**
	 * Write the names read by the classes this runs on, for the
	 * comparison: the root of the repository, the file to write, and
	 * what the classes are of.
	 */
	public static void main(String[] args) throws IOException {
		List<String> lines = new ArrayList<>();
		lines.add("# names read at " + args[2]);
		for (String text : texts(samples(new File(args[0],
				"app/src/main/res/raw")))) {
			lines.add(Integer.toHexString(text.hashCode()) + "\t" +
					String.join(" ", names(text)));
		}
		Files.write(new File(args[1]).toPath(), lines,
				StandardCharsets.UTF_8);
	}

	// The names the release editor has of a text.
	private static Set<String> names(String text) {
		Set<String> names = new TreeSet<>();
		for (Knowledge.Symbol symbol : GlslKnowledge.of(GlslLexer.lex(text),
				text).symbols()) {
			if (!symbol.name().isEmpty()) {
				names.add(symbol.name());
			}
		}
		return names;
	}

	private static List<String> samples() throws IOException {
		for (String base : new String[]{".", "app"}) {
			File dir = new File(base, "src/main/res/raw");
			if (dir.isDirectory()) {
				return samples(dir);
			}
		}
		throw new AssertionError("No bundled shaders");
	}

	// The shaders release bundles, in the order of their texts.
	private static List<String> samples(File dir) throws IOException {
		List<String> shaders = new ArrayList<>();
		File[] files = dir.listFiles((d, name) -> name.endsWith(".glsl"));
		if (files == null || files.length == 0) {
			throw new AssertionError("No bundled shaders in " + dir);
		}
		for (File file : files) {
			shaders.add(new String(Files.readAllBytes(file.toPath()),
					StandardCharsets.UTF_8));
		}
		Collections.sort(shaders);
		return shaders;
	}

	// The shaders and seeded damaged variants of them: one to four
	// times, something is cut out or something is put in that opens,
	// closes or starts what a declaration is made of.
	private static List<String> texts(List<String> shaders) {
		String[] junk = {"{", "}", "(", ")", "[", "]", ";", ",", ".", "/*",
				"*/", "//", "#", "#if", "#else", "#endif", "\"", "\\\n",
				"struct", "for", "if", "uniform", "vec3", "=", "\n",
				"float y = 2.0;\n", "struct S;\n", "float f(", "float, "};
		Random random = new Random(42);
		List<String> texts = new ArrayList<>(shaders);
		for (int n = 0; n < DAMAGED; ++n) {
			StringBuilder sb = new StringBuilder(shaders.get(
					random.nextInt(shaders.size())));
			for (int k = 1 + random.nextInt(4); k-- > 0 &&
					sb.length() > 2; ) {
				int at = random.nextInt(sb.length());
				if (random.nextBoolean()) {
					sb.delete(at, Math.min(sb.length(), at + 1 +
							random.nextInt(40)));
				} else {
					sb.insert(at, junk[random.nextInt(junk.length)]);
				}
			}
			texts.add(sb.toString());
		}
		return texts;
	}
}

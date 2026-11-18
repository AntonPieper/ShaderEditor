package exact;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import exact.Exact.Classes;
import exact.Exact.Reading;
import exact.Pre.Tok;

/** Drivers of the spike: review cases, Mesa differential, statistics. */
final class Main {
	static int failed, passed;

	public static void main(String[] args) throws Exception {
		switch (args[0]) {
			case "cases" -> Cases.run();
			case "diff" -> {
				Diff diff = new Diff();
				for (int i = 1; i < args.length; ++i) {
					diff.file(Path.of(args[i]));
				}
				diff.report();
			}
			default -> throw new IllegalArgumentException(args[0]);
		}
	}

	static String clean(String text) {
		return text.replace("\r\n", "\n").replace('\r', '\n')
				.replaceAll("[^\\x0A\\x09\\x20-\\x7E]", " ");
	}

	static int version(String text) {
		Matcher m = Pattern.compile("^\\s*#\\s*version\\s+(\\d+)",
				Pattern.MULTILINE).matcher(text);
		return m.find() ? Integer.parseInt(m.group(1)) : 100;
	}

	/**
	 * What every writing of word is, asked about its k-th: T this, O
	 * something else, - no reading compiles it, ? a member that cannot
	 * be told; or why the text was not read.
	 */
	static String status(String text, String word, int k) {
		text = clean(text);
		List<Reading> readings = Exact.readings(text, version(text));
		Classes classes = new Classes();
		for (Reading r : readings) {
			if (r.fail != null) {
				return "FAIL " + r.fail;
			}
			classes.add(r, at -> at);
		}
		List<Integer> places = new ArrayList<>();
		for (Tok t : Pre.words(text)) {
			if (t.text.equals(word)) {
				places.add(t.at);
			}
		}
		StringBuilder sb = new StringBuilder();
		long asked = places.get(k);
		for (int at : places) {
			sb.append(sb.length() > 0 ? " " : "");
			sb.append(!classes.has(at) ? '-'
					: classes.unknown.contains((long) at) ? '?'
					: classes.same(asked, at) ? 'T' : 'O');
		}
		return sb.toString();
	}

	/** Rename the class of the k-th word; null, or why it is refused. */
	static String rename(String text, String word, int k, String name) {
		text = clean(text);
		int version = version(text);
		List<Reading> readings = Exact.readings(text, version);
		Classes classes = new Classes();
		for (Reading r : readings) {
			if (r.fail != null) {
				return "FAIL " + r.fail;
			}
			classes.add(r, at -> at);
		}
		List<Integer> places = new ArrayList<>();
		for (Tok t : Pre.words(text)) {
			if (t.text.equals(word)) {
				places.add(t.at);
			}
		}
		Map<Integer, String> names = new HashMap<>();
		Map<Integer, Integer> lengths = new HashMap<>();
		Set<Integer> renamed = new HashSet<>();
		for (long at : classes.of(places.get(k))) {
			if (at == Exact.LANGUAGE) {
				return "a name of the language";
			}
			names.put((int) at, name);
			lengths.put((int) at, word.length());
			renamed.add((int) at);
		}
		String edited = Exact.replace(text, names, lengths);
		for (Reading before : readings) {
			Reading after = Exact.read(edited, version, before.config);
			String why = Exact.check(before, after, names, lengths);
			if (why != null) {
				return why;
			}
		}
		return null;
	}

	static void expect(String id, String expected, String actual) {
		boolean ok = expected.equals(String.valueOf(actual)) ||
				expected.endsWith("*") && String.valueOf(actual).startsWith(
						expected.substring(0, expected.length() - 1));
		if (ok) {
			++passed;
		} else {
			++failed;
			System.out.println("WRONG " + id + ": expected " + expected +
					", got " + actual);
		}
	}

	/** Mesa as the judge: rename every class at once, compare its IR. */
	static final class Diff {
		final Map<String, Integer> counts = new TreeMap<>();
		final Map<String, Integer> fails = new TreeMap<>();
		int classes, tokens, mutations, caught;
		final int[] declared = new int[11];
		// Classes whose word is written nowhere else; all; other writings.
		int alone, named, others, compileOnly;
		int extra, extraSeen, extraCompile, missed, stricter;

		// Why our check refuses an edit, or null if it lets it pass.
		static String refuses(List<Reading> readings, String text,
				int version, Map<Integer, String> names,
				Map<Integer, Integer> lengths) {
			String edited = Exact.replace(text, names, lengths);
			for (Reading before : readings) {
				String why = Exact.check(before, Exact.read(edited, version,
						before.config), names, lengths);
				if (why != null) {
					return why;
				}
			}
			return null;
		}
		long nanos, chars;

		void count(String what) {
			counts.merge(what, 1, Integer::sum);
		}

		void file(Path path) throws Exception {
			String text = clean(Files.readString(path,
					StandardCharsets.ISO_8859_1));
			String define = System.getProperty("define");
			if (define != null) {
				Matcher v = Pattern.compile("^\\s*#\\s*version.*\n",
						Pattern.MULTILINE).matcher(text);
				int at = v.find() ? v.end() : 0;
				text = text.substring(0, at) + "#define " + define + " 1\n" +
						text.substring(at);
			}
			int version = version(text);
			if (version != 100 && version != 300) {
				count("not GLSL ES 1.00 or 3.00");
				return;
			}
			long start = System.nanoTime();
			List<Reading> readings = Exact.readings(text, version);
			nanos += System.nanoTime() - start;
			chars += text.length();
			Classes classes = new Classes();
			for (Reading r : readings) {
				if (r.fail != null) {
					count("not read exactly");
					fails.merge(r.fail.replaceAll("^[a-zA-Z_0-9]+ is", "X is"),
							1, Integer::sum);
					if (System.getProperty("why") != null) {
						System.out.println(path + ": " + r.fail + " at " +
								line(text, r.failAt));
					}
					return;
				}
				classes.add(r, at -> at);
			}
			count("read exactly");
			int[] counts = readings.get(0).counts();
			for (int i = 0; i < counts.length; ++i) {
				declared[i] += counts[i];
			}
			Map<String, Integer> written = new HashMap<>();
			for (Tok t : Pre.words(text)) {
				written.merge(t.text, 1, Integer::sum);
			}
			for (Set<Long> c : classes.all().values()) {
				long first = c.iterator().next();
				if (first < 0) {
					continue;
				}
				int end = (int) first;
				while (end < text.length() && (Character.isLetterOrDigit(
						text.charAt(end)) || text.charAt(end) == '_')) {
					++end;
				}
				int all = written.getOrDefault(text.substring((int) first,
						end), 0);
				++named;
				alone += all == c.size() ? 1 : 0;
				others += all - c.size();
			}
			if (System.getProperty("nomesa") != null) {
				return;
			}
			if (readings.size() > 1) {
				count("read exactly, in more than one configuration");
			}
			Map<Integer, Integer> lengths = new HashMap<>();
			for (Tok t : Pre.words(text)) {
				lengths.put(t.at, t.text.length());
			}
			Map<Integer, String> names = new HashMap<>();
			Map<String, String> back = new HashMap<>();
			List<Set<Long>> renamedClasses = new ArrayList<>();
			int n = 0;
			for (Set<Long> c : classes.all().values()) {
				if (c.contains(Exact.LANGUAGE)) {
					continue;
				}
				String old = null;
				for (long at : c) {
					String s = text.substring((int) at,
							(int) at + lengths.get((int) at));
					if (old != null && !old.equals(s)) {
						throw new IllegalStateException("class of two names: " +
								old + " " + s + " in " + path);
					}
					old = s;
				}
				if (old.equals("main")) {
					continue;
				}
				String fresh = "zq" + n++ + "q";
				back.put(fresh, old);
				for (long at : c) {
					names.put((int) at, fresh);
				}
				renamedClasses.add(c);
				++this.classes;
				tokens += c.size();
			}
			String edited = Exact.replace(text, names, lengths);
			// Our own reading of the result must be the same reading.
			if (refuses(readings, text, version, names, lengths) != null) {
				count("OWN CHECK FAILS");
				System.out.println("OWN CHECK " + path + ": " + refuses(
						readings, text, version, names, lengths));
				return;
			}
			String before = mesa(text, version);
			if (before == null) {
				count("Mesa does not compile the original");
				return;
			}
			String after = mesa(edited, version);
			if (after == null) {
				count("MISMATCH");
				System.out.println("MISMATCH " + path +
						": the renamed text does not compile");
				Files.writeString(Path.of("out/mismatch.frag"), edited);
				return;
			}
			String a = canon(before, Map.of()), b = canon(after, back);
			if (!a.equals(b)) {
				count("MISMATCH");
				String[] x = a.split("\n"), y = b.split("\n");
				for (int i = 0; i < Math.min(x.length, y.length); ++i) {
					if (!x[i].equals(y[i])) {
						System.out.println("MISMATCH " + path + "\n  " +
								x[i].trim() + "\n  " + y[i].trim());
						break;
					}
				}
				return;
			}
			count("Mesa agrees");
			extras(readings, text, version, names, lengths, back, a);
			// The judge must see a wrong rename: leave one token of a
			// class of several as it was, in a few classes.
			int tried = 0;
			for (Set<Long> c : renamedClasses) {
				if (c.size() < 2 || tried == 3) {
					continue;
				}
				++tried;
				Map<Integer, String> wrong = new HashMap<>(names);
				int left = (int) (long) c.stream().skip(c.size() / 2)
						.findFirst().get();
				wrong.remove(left);
				String mutant = mesa(Exact.replace(text, wrong, lengths),
						version);
				++mutations;
				compileOnly += mutant == null ? 1 : 0;
				boolean refused = refuses(readings, text, version, wrong,
						lengths) != null;
				missed += !refused && (mutant == null ||
						!canon(mutant, back).equals(a)) ? 1 : 0;
				stricter += refused && mutant != null &&
						canon(mutant, back).equals(a) ? 1 : 0;
				if (mutant == null || !canon(mutant, back).equals(a)) {
					++caught;
				} else if (System.getProperty("why") != null) {
					System.out.println("NOT SEEN by Mesa: " + path + " " +
							line(text, left));
				}
			}
		}

		// The other wrong rename: one writing of the same word that is
		// something else gets the name too.
		void extras(List<Reading> readings, String text, int version,
				Map<Integer, String> names,
				Map<Integer, Integer> lengths, Map<String, String> back,
				String a) throws Exception {
			Map<String, String> other = new HashMap<>();
			int tried = 0;
			for (Map.Entry<Integer, String> e : names.entrySet()) {
				String old = back.get(e.getValue());
				String seen = other.putIfAbsent(old, e.getValue());
				if (seen == null || seen.equals(e.getValue()) || tried == 3) {
					continue;
				}
				++tried;
				Map<Integer, String> wrong = new HashMap<>(names);
				wrong.put(e.getKey(), seen);
				String mutant = mesa(Exact.replace(text, wrong, lengths),
						version);
				++extra;
				extraCompile += mutant == null ? 1 : 0;
				boolean saw = mutant == null ||
						!canon(mutant, back).equals(a);
				boolean refused = refuses(readings, text, version, wrong,
						lengths) != null;
				missed += saw && !refused ? 1 : 0;
				stricter += refused && !saw ? 1 : 0;
				extraSeen += saw ? 1 : 0;
				if (!saw && System.getProperty("why") != null) {
					long uses = names.values().stream().filter(
							e.getValue()::equals).count();
					System.out.println("EXTRA NOT SEEN (" + uses +
							" writings in its class): " + line(text,
							e.getKey()));
				}
			}
		}

		void report() {
			System.out.println(counts);
			System.out.println("declared: globals, functions, structs, " +
					"fields, macros, parameters, locals; parameters and " +
					"locals in functions free of macros and directives; " +
					"functions, free ones: " +
					java.util.Arrays.toString(declared));
			System.out.println(extraSeen + " of " + extra + " renames of " +
					"one writing too many seen by Mesa (" + extraCompile +
					" because the text no longer compiles)");
			System.out.println("wrong renames Mesa saw and our check let " +
					"pass: " + missed + "; refused by our check and not " +
					"seen by Mesa: " + stricter);
			System.out.println("of " + named + " classes, " + alone +
					" have every writing of their word; the others have " +
					others + " writings that are something else");
			System.out.println("why not read: " + fails);
			System.out.println(classes + " classes of " + tokens +
					" tokens renamed; " + caught + " of " + mutations +
					" wrong renames seen by Mesa (" + compileOnly +
					" of them because the text no longer compiles); reading " + chars +
					" chars took " + nanos / 1_000_000 + " ms");
		}
	}

	static String line(String text, int at) {
		if (at < 0 || at > text.length()) {
			return "?";
		}
		int line = 1;
		for (int i = 0; i < at; ++i) {
			line += text.charAt(i) == '\n' ? 1 : 0;
		}
		int end = text.indexOf('\n', at);
		return "line " + line + ": " + text.substring(
				text.lastIndexOf('\n', Math.max(0, at - 1)) + 1,
				end < 0 ? text.length() : end).trim();
	}

	/** Mesa's IR of a fragment shader, or null if it does not compile. */
	static String mesa(String text, int version) throws Exception {
		File file = File.createTempFile("exact", ".frag", new File("out"));
		Files.writeString(file.toPath(), text);
		Process p = new ProcessBuilder("/opt/homebrew/bin/glsl_compiler",
				"--version", String.valueOf(version), "--dump-hir",
				file.getPath()).redirectError(new File("out/stderr.txt"))
				.start();
		String out = new String(p.getInputStream().readAllBytes(),
				StandardCharsets.UTF_8);
		p.waitFor();
		file.delete();
		return out.contains("error:") || !out.contains("( function main")
				? null : out;
	}

	private static final Pattern DECLARE = Pattern.compile(
			"\\(declare \\([^)]*\\) \\S+ ([A-Za-z_0-9@]+)\\)");
	private static final Pattern NAME = Pattern.compile(
			"[A-Za-z_][A-Za-z_0-9]*(@\\d+)?");

	/**
	 * The IR with the names put back and every variable of a function
	 * numbered by its declaration, so that two variables of one name
	 * stay two and their names do not matter.
	 */
	static String canon(String dump, Map<String, String> back) {
		StringBuilder sb = new StringBuilder();
		Map<String, String> local = null;
		int locals = 0;
		for (String line : dump.split("\n")) {
			// The name Mesa prints for a struct ends in its address.
			line = line.replaceAll("(?<=[A-Za-z_0-9])x[0-9a-f]{10}\\b", "");
			if (line.startsWith("(")) {
				local = line.startsWith("( function") ? new HashMap<>() : null;
			}
			if (local != null && line.trim().startsWith("(signature")) {
				// Mesa names the variables of each form anew.
				local = new HashMap<>();
			}
			if (local != null) {
				Matcher d = DECLARE.matcher(line);
				while (d.find()) {
					local.put(d.group(1), "L" + locals++);
				}
			}
			Matcher m = NAME.matcher(line);
			int last = 0;
			while (m.find()) {
				String name = m.group();
				// A variable is named behind var_ref and where it is
				// declared; the same letters elsewhere are a mask.
				boolean variable = line.startsWith("(var_ref ",
						m.start() - 9) || line.startsWith(")",
						m.end()) && line.lastIndexOf("(declare ",
						m.start()) >= 0 && line.indexOf('(',
						line.lastIndexOf("(declare ", m.start()) + 10) <
						m.start() && line.charAt(m.start() - 1) == ' ' &&
						DECLARE.matcher(line).find();
				String to = local != null && variable ? local.get(name)
						: null;
				if (to == null) {
					to = name.replaceAll("@\\d+$", "");
					to = back.getOrDefault(to, to);
				}
				sb.append(line, last, m.start()).append(to);
				last = m.end();
			}
			sb.append(line.substring(last)).append('\n');
		}
		return sb.toString();
	}
}

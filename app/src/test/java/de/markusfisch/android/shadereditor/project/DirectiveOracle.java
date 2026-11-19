package de.markusfisch.android.shadereditor.project;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Asks Mesa's compiler what it compiles of every case of
 * {@link DirectiveCases} and records it for
 * {@link DirectiveRunOracleTest}; run by tools/directives/oracle.sh,
 * not by the unit tests, which need no Mesa.
 * <p>
 * Which lines Mesa compiles: a line "#error M<n>" is put in front of
 * every line n where it is in the arm of n, and Mesa names every marker
 * its preprocessor reaches. What an expansion is: the use is replaced by
 * the expansion, and Mesa's IR must stay the same; it must change when a
 * number of the expansion is changed, or a syntax error added to one
 * without numbers, or the use is not seen at all.
 */
public final class DirectiveOracle {
	private static final String MESA = "/opt/homebrew/bin/glsl_compiler";
	private static final Pattern MARKER = Pattern.compile(
			"#error M(\\d+)\\b");
	private static final Pattern NUMBER = Pattern.compile(
			"(?<![A-Za-z0-9_.])\\d+(\\.\\d*)?");

	private DirectiveOracle() {
	}

	public static void main(String[] args) throws Exception {
		File out = new File(args[0]);
		File work = Files.createTempDirectory("directives").toFile();
		List<DirectiveCases.Case> cases = DirectiveCases.all();
		ExecutorService pool = Executors.newFixedThreadPool(8);
		List<Future<String>> records = new ArrayList<>();
		for (DirectiveCases.Case c : cases) {
			records.add(pool.submit(() -> judge(c, work)));
		}
		int[] totals = new int[12];
		try (PrintWriter writer = new PrintWriter(out,
				StandardCharsets.UTF_8)) {
			writer.println("# Written by tools/directives/oracle.sh from " +
					"Mesa's glsl_compiler; do not edit.");
			for (Future<String> record : records) {
				String text = record.get();
				writer.print(text);
				count(text, totals);
			}
		} finally {
			pool.shutdownNow();
		}
		System.out.printf("cases %d (texts that compile in Mesa's " +
						"preprocessor without error %d)%n" +
						"lines called off %d, of them checked by Mesa %d, " +
						"disagreements %d%n" +
						"lines called on, checked %d, Mesa disagrees %d%n" +
						"lines that cannot be told %d%n" +
						"expansions shown %d: same IR %d, differs %d, " +
						"not seen by Mesa %d%n",
				cases.size(), totals[0], totals[1], totals[2], totals[3],
				totals[4], totals[5], totals[6], totals[7], totals[8],
				totals[9], totals[10]);
	}

	private static void count(String record, int[] totals) {
		for (String line : record.split("\n")) {
			String[] f = line.split(" ", 2);
			switch (f[0]) {
				case "clean" -> ++totals[0];
				case "off" -> {
					totals[1] += Integer.parseInt(f[1].split(" ")[0]);
					totals[2] += Integer.parseInt(f[1].split(" ")[1]);
					totals[3] += Integer.parseInt(f[1].split(" ")[2]);
				}
				case "on" -> {
					totals[4] += Integer.parseInt(f[1].split(" ")[0]);
					totals[5] += Integer.parseInt(f[1].split(" ")[1]);
				}
				case "unknown" -> totals[6] += Integer.parseInt(f[1]);
				case "expansion" -> {
					++totals[7];
					String verdict = f[1].split(" ")[3];
					totals[verdict.equals("same") ? 8
							: verdict.equals("differs") ? 9 : 10]++;
				}
				default -> {
				}
			}
		}
	}

	// The record of one case.
	private static String judge(DirectiveCases.Case c, File work)
			throws IOException, InterruptedException {
		String[] lines = c.text().split("\n", -1);
		PreprocessorScan scan = PreprocessorScan.scan(Arrays.asList(lines),
				null);
		int version = -1;
		for (PreprocessorScan.Directive d : scan.directives()) {
			if (d.kind() == PreprocessorScan.Kind.VERSION) {
				version = d.line() - 1;
			}
		}
		TreeSet<Integer> marked = new TreeSet<>();
		StringBuilder text = new StringBuilder();
		// Not inside parens, which may be the arguments of a macro.
		int depth = 0;
		for (int n = 0; n < lines.length; ++n) {
			if (DirectiveCases.markable(lines, scan, n, version) &&
					!bounds(scan, n) && depth == 0) {
				marked.add(n);
				text.append("#error M").append(n).append('\n');
			}
			text.append(lines[n]).append(n + 1 < lines.length ? "\n" : "");
			if (!scan.code().get(n).stripLeading().startsWith("#") &&
					(n == 0 || !scan.code().get(n - 1).endsWith("\\"))) {
				for (char ch : scan.code().get(n).toCharArray()) {
					depth += ch == '(' ? 1 : ch == ')' ? -1 : 0;
				}
			}
		}
		String log = mesa(work, text.toString(), c.version(), false);
		TreeSet<Integer> active = new TreeSet<>();
		Matcher m = MARKER.matcher(log);
		while (m.find()) {
			active.add(Integer.parseInt(m.group(1)));
		}
		String base = mesa(work, c.text(), c.version(), false);
		StringBuilder record = new StringBuilder();
		record.append("case ").append(c.name()).append(' ')
				.append(sha(c.text())).append('\n');
		if (!base.contains("preprocessor error")) {
			record.append("clean\n");
		}
		record.append("active ").append(ranges(active)).append('\n');
		TreeSet<Integer> inactive = new TreeSet<>(marked);
		inactive.removeAll(active);
		record.append("inactive ").append(ranges(inactive)).append('\n');
		int checked = 0;
		List<Integer> wrong = new ArrayList<>();
		for (int n : c.off()) {
			checked += marked.contains(n) ? 1 : 0;
			if (active.contains(n)) {
				wrong.add(n);
			}
		}
		record.append("off ").append(c.off().size()).append(' ')
				.append(checked).append(' ').append(wrong.size())
				.append(wrong.isEmpty() ? "" : " lines " + wrong)
				.append('\n');
		int on = 0;
		int onWrong = 0;
		int unknown = 0;
		for (int n = 0; n < lines.length; ++n) {
			DirectiveRun.State state = c.states().get(n);
			if (state == DirectiveRun.State.UNKNOWN) {
				++unknown;
			} else if (state == DirectiveRun.State.ON && marked.contains(n)) {
				++on;
				onWrong += inactive.contains(n) ? 1 : 0;
			}
		}
		record.append("on ").append(on).append(' ').append(onWrong)
				.append('\n');
		record.append("unknown ").append(unknown).append('\n');
		String hir = c.expansions().isEmpty()
				? null
				: hir(mesa(work, c.text(), c.version(), true));
		for (DirectiveCases.Expansion e : c.expansions()) {
			record.append("expansion ").append(e.line()).append(' ')
					.append(e.column()).append(' ').append(e.name())
					.append(' ').append(verdict(work, c, lines, scan, e, hir))
					.append(' ').append(e.text()).append('\n');
		}
		return record.append("end\n").toString();
	}

	// Whether line n is an #elif, #else or #endif: a marker in front of
	// it is in the arm before it.
	private static boolean bounds(PreprocessorScan scan, int n) {
		for (PreprocessorScan.Directive d : scan.directives()) {
			if (d.line() == n + 1) {
				return d.kind() == PreprocessorScan.Kind.ELIF ||
						d.kind() == PreprocessorScan.Kind.ELSE ||
						d.kind() == PreprocessorScan.Kind.ENDIF;
			}
		}
		return false;
	}

	private static String verdict(File work, DirectiveCases.Case c,
			String[] lines, PreprocessorScan scan,
			DirectiveCases.Expansion e, String hir)
			throws IOException, InterruptedException {
		if (hir == null) {
			return "unseen";
		}
		int[] end = end(lines, scan, e, c.states());
		if (end == null) {
			return "differs";
		}
		String replaced = replace(lines, e, end, e.text());
		String other = hir(mesa(work, replaced, c.version(), true));
		if (!hir.equals(other)) {
			return "differs";
		}
		// A number changed, or else a syntax error, must change what
		// Mesa makes of it, or the use is not seen at all.
		Matcher number = NUMBER.matcher(e.text());
		String changed = number.find()
				? e.text().substring(0, number.start()) + "(" +
				number.group() + "+7)" + e.text().substring(number.end())
				: e.text() + " )(";
		String blind = hir(mesa(work, replace(lines, e, end, changed),
				c.version(), true));
		return hir.equals(blind) ? "unseen" : "same";
	}

	// Where the use ends, by the #define in force there: the last one
	// of its name on a line that is on, unless an #undef followed.
	private static int[] end(String[] lines, PreprocessorScan scan,
			DirectiveCases.Expansion e, List<DirectiveRun.State> states) {
		Boolean function = null;
		Pattern define = Pattern.compile("^\\s*#\\s*(define|undef)\\s+" +
				e.name() + "\\b(\\()?");
		for (int n = 0; n < e.line(); ++n) {
			Matcher m = define.matcher(scan.code().get(n));
			if (m.find() && states.get(n) == DirectiveRun.State.ON) {
				function = m.group(1).equals("undef") ? null
						: m.group(2) != null;
			}
		}
		if (function == null) {
			return null;
		} else if (!function) {
			return new int[]{e.line(), e.column() + e.name().length()};
		}
		int depth = 0;
		boolean opened = false;
		for (int n = e.line(), col = e.column() + e.name().length();
				n < lines.length; ++n, col = 0) {
			String code = scan.code().get(n);
			for (; col < code.length(); ++col) {
				char ch = code.charAt(col);
				if (ch == '(') {
					++depth;
					opened = true;
				} else if (ch == ')' && --depth == 0 && opened) {
					return new int[]{n, col + 1};
				}
			}
		}
		return null;
	}

	// The lines with the use replaced by text, and as many lines.
	private static String replace(String[] lines,
			DirectiveCases.Expansion e, int[] end, String text) {
		StringBuilder sb = new StringBuilder();
		for (int n = 0; n < lines.length; ++n) {
			if (n == e.line()) {
				sb.append(lines[n], 0, e.column()).append(text);
				if (end[0] == n) {
					sb.append(lines[n].substring(end[1]));
				}
			} else if (n > e.line() && n <= end[0]) {
				if (n == end[0]) {
					sb.append(lines[n].substring(end[1]));
				}
			} else {
				sb.append(lines[n]);
			}
			if (n + 1 < lines.length) {
				sb.append('\n');
			}
		}
		return sb.toString();
	}

	// The IR of a dump, with the addresses Mesa puts in names cut; null
	// when it did not compile.
	private static String hir(String dump) {
		return dump.contains("error") || !dump.contains("( function main")
				? null
				: dump.replaceAll("(?<=[A-Za-z_0-9])x[0-9a-f]{10}\\b", "")
						.replaceAll("Info log for \\S+", "");
	}

	private static String mesa(File work, String text, int version,
			boolean hir) throws IOException, InterruptedException {
		File file = File.createTempFile("case", ".frag", work);
		Files.writeString(file.toPath(), text);
		List<String> command = new ArrayList<>(List.of(MESA, "--version",
				String.valueOf(version)));
		if (hir) {
			command.add("--dump-hir");
		}
		command.add(file.getPath());
		ProcessBuilder builder = new ProcessBuilder(command);
		if (hir) {
			builder.redirectError(new File("/dev/null"));
		} else {
			builder.redirectErrorStream(true);
		}
		Process process = builder.start();
		String out = new String(process.getInputStream().readAllBytes(),
				StandardCharsets.UTF_8);
		process.waitFor();
		Files.delete(file.toPath());
		return out;
	}

	static String ranges(TreeSet<Integer> lines) {
		StringBuilder sb = new StringBuilder();
		Integer from = null;
		Integer last = null;
		for (int n : lines) {
			if (last != null && n == last + 1) {
				last = n;
				continue;
			}
			if (from != null) {
				sb.append(sb.length() > 0 ? "," : "").append(from)
						.append(from.equals(last) ? "" : "-" + last);
			}
			from = last = n;
		}
		if (from != null) {
			sb.append(sb.length() > 0 ? "," : "").append(from)
					.append(from.equals(last) ? "" : "-" + last);
		}
		return sb.length() > 0 ? sb.toString() : "-";
	}

	static TreeSet<Integer> lines(String ranges) {
		TreeSet<Integer> lines = new TreeSet<>();
		if (ranges.equals("-")) {
			return lines;
		}
		for (String range : ranges.split(",")) {
			String[] ends = range.split("-");
			int from = Integer.parseInt(ends[0]);
			int to = Integer.parseInt(ends[ends.length - 1]);
			for (int n = from; n <= to; ++n) {
				lines.add(n);
			}
		}
		return lines;
	}

	static String sha(String text) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-1").digest(
					text.getBytes(StandardCharsets.UTF_8));
			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < 8; ++i) {
				sb.append(String.format("%02x", digest[i]));
			}
			return sb.toString();
		} catch (NoSuchAlgorithmException e) {
			throw new AssertionError(e);
		}
	}

	/** The records of a file written by main, by case name. */
	static Map<String, List<String>> read(File file) throws IOException {
		Map<String, List<String>> records = new TreeMap<>();
		List<String> current = null;
		for (String line : Files.readAllLines(file.toPath())) {
			if (line.startsWith("case ")) {
				current = new ArrayList<>();
				records.put(line.split(" ")[1], current);
			}
			if (current != null) {
				current.add(line);
			}
		}
		return records;
	}
}

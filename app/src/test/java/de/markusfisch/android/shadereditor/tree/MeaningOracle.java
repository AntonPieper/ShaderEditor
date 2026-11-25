package de.markusfisch.android.shadereditor.tree;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.markusfisch.android.shadereditor.project.DirectiveCases;
import de.markusfisch.android.shadereditor.project.DirectiveOracle;

/**
 * Asks Mesa's compiler whether the tree is right about what names
 * mean. In each case every declaration the tree found gets a new
 * name, at its declaration and at every place the tree says means it,
 * all at once. If the tree is right, Mesa compiles the renamed text
 * to the same program as the original, but for the names; if one
 * place is in the wrong class, it does not.
 *
 * tools/tree/oracle.sh runs this where glsl_compiler is and records
 * the verdicts; {@link MeaningsOracleTest} checks without Mesa that
 * the tree still reads every case as it did when Mesa judged it.
 */
public final class MeaningOracle {
	private static final String MESA = "/opt/homebrew/bin/glsl_compiler";
	private static final Pattern DECLARE = Pattern.compile(
			"\\(declare \\([^)]*\\) \\S+ ([A-Za-z_0-9@]+)\\)");
	private static final Pattern NAME = Pattern.compile(
			"[A-Za-z_][A-Za-z_0-9]*(@\\d+)?");

	/** A text and what it is called. */
	record Case(String name, String text) {
	}

	private MeaningOracle() {
	}

	/**
	 * The programs the engine compiles for the examples, the bundled
	 * shaders, the ShaderToy fixtures and the directive probes, the
	 * generated texts, and the probes of the reviews of Go to
	 * definition.
	 */
	static List<Case> cases() {
		List<Case> cases = new ArrayList<>();
		for (DirectiveCases.Case c : DirectiveCases.all()) {
			cases.add(new Case(c.name(), c.text()));
		}
		File[] probes = new File(DirectiveCases.probes().getParentFile()
				.getParentFile(), "tree/probes").listFiles();
		java.util.Arrays.sort(probes);
		for (File probe : probes) {
			try {
				cases.add(new Case("definition/" + probe.getName(),
						Files.readString(probe.toPath())));
			} catch (IOException e) {
				throw new AssertionError(e);
			}
		}
		return cases;
	}

	/**
	 * What the tree says every name of a text means, as one line: the
	 * place of each name and the place of what it means, or ? where
	 * it says it cannot tell.
	 */
	static String reading(Whole whole) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < whole.means.length; ++i) {
			Meanings.Declared d = whole.means[i];
			if (d != null && d != Meanings.LANGUAGE) {
				sb.append(whole.at(i)).append(isDeclared(d)
						? (d.orLanguage ? "~" : ">") + whole.at(d.token) : "?")
						.append(' ');
			}
		}
		return sb.append(whole.piece.errors).append(' ')
				.append(whole.piece.missing).toString();
	}

	private static boolean isDeclared(Meanings.Declared d) {
		return d != null && d.piece != null;
	}

	public static void main(String[] args) throws Exception {
		File work = Files.createTempDirectory("meanings").toFile();
		Map<String, Integer> totals = new TreeMap<>();
		try (PrintWriter writer = new PrintWriter(new File(args[0]),
				StandardCharsets.UTF_8)) {
			writer.println("# Written by tools/tree/oracle.sh from Mesa's " +
					"glsl_compiler; do not edit.");
			for (Case c : cases()) {
				Whole whole = new Whole(c.text());
				String verdict = judge(whole, work, totals);
				totals.merge(verdict.split(":")[0], 1, Integer::sum);
				writer.println("case " + c.name() + " " +
						DirectiveOracle.sha(c.text()) + " " +
						DirectiveOracle.sha(reading(whole)) + " " + verdict);
				if (verdict.startsWith("WRONG")) {
					System.out.println(c.name() + ": " + verdict);
				}
			}
		}
		// Other texts, judged and not recorded: files and folders.
		for (int i = 1; i < args.length; ++i) {
			for (File file : files(new File(args[i]), new ArrayList<>())) {
				String text = Files.readString(file.toPath(),
						StandardCharsets.ISO_8859_1).replace("\r\n", "\n");
				if (System.getProperty("define") != null) {
					int at = text.startsWith("#version")
							? text.indexOf('\n') + 1 : 0;
					text = text.substring(0, at) + "#define " +
							System.getProperty("define") + " 1\n" +
							text.substring(at);
				}
				Whole whole = new Whole(text);
				int version = whole.compiled.env.version;
				if (version != 100 && version != 300) {
					totals.merge("other not GLSL ES 1.00 or 3.00", 1, Integer::sum);
					continue;
				}
				String verdict = judge(whole, work, totals);
				totals.merge("other " + verdict.split(":")[0], 1, Integer::sum);
				if (verdict.startsWith("WRONG")) {
					System.out.println(file + ": " + verdict);
				}
			}
		}
		System.out.println(totals);
	}

	private static List<File> files(File file, List<File> files) {
		if (file.isDirectory()) {
			for (File child : file.listFiles()) {
				files(child, files);
			}
		} else if (file.getName().endsWith(".glsl") ||
				file.getName().endsWith(".frag")) {
			files.add(file);
		}
		return files;
	}

	// "agrees", "refused" if Mesa does not compile the text as it is,
	// or "WRONG" and why.
	private static String judge(Whole whole, File work,
			Map<String, Integer> totals) throws Exception {
		int version = whole.compiled.env.version;
		String before = mesa(work, whole.text, version);
		if (before == null) {
			return "refused";
		}
		if (whole.piece.errors + whole.piece.missing > 0) {
			return "WRONG: an ERROR or MISSING node in a text Mesa compiles";
		}
		// A class is what must be renamed together: a declaration and
		// what means it. Where one writing in a macro's text means two
		// declarations, the two are one class.
		Map<Integer, Meanings.Declared> places = new TreeMap<>();
		Map<Integer, Integer> ends = new HashMap<>();
		IdentityHashMap<Meanings.Declared, Meanings.Declared> same =
				new IdentityHashMap<>();
		// Places that are not renamed: one writing in a macro's text
		// that means the language where it is compiled once and a
		// declaration where it is compiled again; and a call that
		// means a function of the shader or one of the language.
		java.util.Set<Integer> fixed = new java.util.HashSet<>();
		for (int i = 0; i < whole.means.length; ++i) {
			Meanings.Declared d = whole.means[i];
			String spelling = whole.piece.spelling(i);
			if (d == null) {
				continue;
			} else if (!isDeclared(d)) {
				if (d != Meanings.LANGUAGE) {
					return "WRONG: " + spelling + " on line " +
							whole.line(whole.at(i)) + " has no declaration, " +
							"and Mesa compiles the text";
				}
				fixed.add(whole.at(i));
				continue;
			} else if (d.orLanguage) {
				fixed.add(whole.at(i));
				fixed.add(whole.at(d.token));
			}
			ends.put(whole.at(i), whole.end(i));
			Meanings.Declared other = places.put(whole.at(i), d);
			if (other != null && root(same, other) != root(same, d)) {
				same.put(root(same, other), root(same, d));
			}
		}
		// A class with a place that is not renamed is not renamed.
		java.util.Set<Meanings.Declared> kept = java.util.Collections.newSetFromMap(
				new IdentityHashMap<>());
		for (Map.Entry<Integer, Meanings.Declared> place : places.entrySet()) {
			Meanings.Declared d = place.getValue();
			if (fixed.contains(place.getKey()) || fixed.contains(whole.at(
					d.token))) {
				kept.add(root(same, d));
			}
		}
		Map<Meanings.Declared, String> names = new IdentityHashMap<>();
		Map<String, String> back = new HashMap<>();
		Map<Integer, String> edits = new TreeMap<>();
		for (Map.Entry<Integer, Meanings.Declared> place : places.entrySet()) {
			Meanings.Declared d = root(same, place.getValue());
			String old = whole.piece.symbols.name(whole.piece.symbols.of(
					whole.text, place.getKey(), ends.get(place.getKey())))
					.replaceAll("\\\\\\s+", "");
			if (old.equals("main") || kept.contains(d)) {
				continue;
			}
			String name = names.get(d);
			if (name == null) {
				name = old + "_Q" + names.size();
				names.put(d, name);
				back.put(name, old);
			}
			edits.put(place.getKey(), name);
		}
		totals.merge("classes renamed", names.size(), Integer::sum);
		totals.merge("names renamed", edits.size(), Integer::sum);
		String renamed = mesa(work, replace(whole.text, edits, ends), version);
		String original = canon(before, Map.of());
		if (renamed == null) {
			return "WRONG: the renamed text does not compile";
		} else if (!canon(renamed, back).equals(original)) {
			String[] a = original.split("\n");
			String[] b = canon(renamed, back).split("\n");
			for (int i = 0; i < Math.min(a.length, b.length); ++i) {
				if (!a[i].equals(b[i])) {
					return "WRONG: the renamed text is another program: " +
							a[i].trim() + " | " + b[i].trim();
				}
			}
			return "WRONG: the renamed text is another program";
		}
		// The judge must see a wrong class: leave one name of a class
		// of several as it was, in up to three classes.
		Map<String, Integer> sizes = new LinkedHashMap<>();
		for (String name : edits.values()) {
			sizes.merge(name, 1, Integer::sum);
		}
		int tried = 0;
		for (Map.Entry<Integer, String> edit : edits.entrySet()) {
			if (sizes.get(edit.getValue()) < 2 || tried == 3) {
				continue;
			}
			sizes.put(edit.getValue(), 0);
			++tried;
			Map<Integer, String> wrong = new TreeMap<>(edits);
			wrong.remove(edit.getKey());
			String mutant = mesa(work, replace(whole.text, wrong, ends), version);
			totals.merge("wrong renames tried", 1, Integer::sum);
			totals.merge(mutant == null || !canon(mutant, back).equals(original)
					? "wrong renames Mesa saw" : "wrong renames Mesa did not see",
					1, Integer::sum);
		}
		return "agrees";
	}

	private static Meanings.Declared root(
			IdentityHashMap<Meanings.Declared, Meanings.Declared> same,
			Meanings.Declared d) {
		while (same.containsKey(d)) {
			d = same.get(d);
		}
		return d;
	}

	private static String replace(String text, Map<Integer, String> edits,
			Map<Integer, Integer> ends) {
		StringBuilder sb = new StringBuilder();
		int last = 0;
		for (Map.Entry<Integer, String> edit : edits.entrySet()) {
			sb.append(text, last, edit.getKey()).append(edit.getValue());
			last = ends.get(edit.getKey());
		}
		return sb.append(text.substring(last)).toString();
	}

	// Mesa's IR of a fragment shader, or null if it does not compile.
	private static String mesa(File work, String text, int version)
			throws IOException, InterruptedException {
		File file = File.createTempFile("case", ".frag", work);
		Files.writeString(file.toPath(), text);
		Process process = new ProcessBuilder(MESA, "--version",
				String.valueOf(version), "--dump-hir", file.getPath())
				.redirectError(new File("/dev/null")).start();
		String out = new String(process.getInputStream().readAllBytes(),
				StandardCharsets.UTF_8);
		process.waitFor();
		Files.delete(file.toPath());
		return out.contains("error:") || !out.contains("( function main")
				? null : out;
	}

	// The IR with the names put back and every variable of a function
	// numbered by its declaration, so that two variables of one name
	// stay two and their names do not matter.
	private static String canon(String dump, Map<String, String> back) {
		StringBuilder sb = new StringBuilder();
		Map<String, String> local = null;
		int locals = 0;
		// Around the program Mesa prints its warnings, with the file's name.
		int log = dump.indexOf("Info log for ");
		int program = dump.indexOf("\n(", Math.max(0, log));
		if (log >= 0) {
			dump = dump.substring(0, log) + (program < 0 ? ""
					: dump.substring(program));
		}
		for (String line : dump.split("\n")) {
			// The name Mesa prints for a struct ends in its address.
			line = line.replaceAll("(?<=[A-Za-z_0-9])x[0-9a-f]{8,16}\\b", "");
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
				boolean variable = line.startsWith("(var_ref ", m.start() - 9) ||
						line.startsWith(")", m.end()) &&
								line.lastIndexOf("(declare ", m.start()) >= 0 &&
								line.indexOf('(', line.lastIndexOf("(declare ",
										m.start()) + 10) < m.start() &&
								line.charAt(m.start() - 1) == ' ' &&
								DECLARE.matcher(line).find();
				String to = local != null && variable ? local.get(name) : null;
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

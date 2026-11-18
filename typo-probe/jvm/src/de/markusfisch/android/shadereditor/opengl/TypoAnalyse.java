package de.markusfisch.android.shadereditor.opengl;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.markusfisch.android.shadereditor.project.ProjectDiagnostic;
import de.markusfisch.android.shadereditor.project.ProjectPath;
import de.markusfisch.android.shadereditor.project.SourceLocation;
import de.markusfisch.android.shadereditor.project.SourceMap;

/**
 * The voices of the typo experiment for every text of the corpus:
 * per device file the driver (a) and what the app shows from its log
 * (b); once, the strict reader of the spike (c) and Mesa's offline
 * compiler (d); and what the app would show if a driver wrote Mesa's
 * log format. One JSON line per (text, voice), with the line mapped to
 * file and line through the program's source map and the line score.
 *
 * Usage: TypoAnalyse <corpus dir> <out file> <results.jsonl>...
 */
final class TypoAnalyse {
	// Formats a driver may write: ANGLE / glslang ("ERROR: 0:12: msg"),
	// Mesa ("0:12(5): error: msg"), NVIDIA ("0(12) : error C1008: msg").
	private static final Pattern[] LOCATED = {
			Pattern.compile("^\\s*ERROR:\\s*\\d+:(\\d+):\\s*(.*)$",
					Pattern.CASE_INSENSITIVE),
			Pattern.compile("^\\s*\\d+:(\\d+)\\(\\d+\\):\\s*(?:preprocessor )?error:\\s*(.*)$"),
			Pattern.compile("^\\s*\\d+\\((\\d+)\\)\\s*:\\s*error\\s*\\w*:\\s*(.*)$",
					Pattern.CASE_INSENSITIVE),
	};
	private static final Pattern APP_OWN = Pattern.compile(
			"^(.+?):(\\d+): (.*)$");

	public static void main(String[] args) throws Exception {
		File corpus = new File(args[0]);
		File out = new File(args[1]);
		File scratch = Files.createTempDirectory("typo-analyse").toFile();
		Map<String, JSONObject> index = new LinkedHashMap<>();
		for (String l : Files.readAllLines(new File(corpus, "index.jsonl")
				.toPath())) {
			JSONObject o = new JSONObject(l);
			index.put(o.getString("id"), o);
		}
		StringBuilder sb = new StringBuilder();
		// Device-independent voices.
		for (JSONObject m : index.values()) {
			String id = m.getString("id");
			File frag = new File(corpus, "texts/" + id + ".frag");
			if (!frag.exists()) {
				continue;
			}
			String text = Files.readString(frag.toPath());
			SourceMap map = map(m);
			// (c) the strict reader.
			JSONObject reader = voice(m, "reader", null);
			try {
				reader(text, reader, m);
			} catch (Throwable t) {
				reader.put("status", "crashed").put("message", t.toString());
			}
			score(reader, m);
			sb.append(reader).append('\n');
			// (d) Mesa.
			String log = mesa(text, m.getInt("version"), scratch);
			JSONObject mesa = voice(m, "mesa", null);
			driverVoice(mesa, log, !log.contains("error:"), m);
			score(mesa, m);
			sb.append(mesa).append('\n');
			// The app, had the driver written Mesa's format.
			if (!log.contains("error:")) {
				continue;
			}
			JSONObject appMesa = voice(m, "app-on-mesa-log", null);
			appVoice(appMesa, log.replaceFirst("^Info log for [^\\n]*\\n", ""),
					map, m);
			score(appMesa, m);
			sb.append(appMesa).append('\n');
		}
		// Per device: (a) and (b).
		for (int i = 2; i < args.length; ++i) {
			File results = new File(args[i]);
			String device = results.getName().replace(".jsonl", "");
			List<String> lines = Files.readAllLines(results.toPath(),
					StandardCharsets.UTF_8);
			JSONObject header = new JSONObject(lines.get(0));
			int context = header.getInt("contextVersion");
			for (String l : lines.subList(1, lines.size())) {
				JSONObject r = new JSONObject(l);
				JSONObject m = index.get(r.getString("id"));
				SourceMap map = map(m);
				boolean ok = r.getBoolean("vertexOk") &&
						r.getBoolean("fragmentOk") &&
						r.optBoolean("linkOk", false);
				String log = !r.getBoolean("vertexOk") ? r.getString("vertexLog")
						: !r.getBoolean("fragmentOk") ? r.getString("fragmentLog")
						: r.optString("linkLog", "");
				JSONObject driver = voice(m, "driver", device);
				boolean es2Refusal = context < 3 && m.getInt("version") >= 300;
				driverVoice(driver, log, ok, m);
				if (es2Refusal) {
					driver.put("status", "n/a: ES 2 context");
				}
				score(driver, m);
				sb.append(driver).append('\n');
				JSONObject app = voice(m, "app", device);
				if (!m.getBoolean("compiled")) {
					appOwn(app, m.getJSONArray("appErrors").getString(0), m);
				} else if (es2Refusal) {
					app.put("status", "app stops: needs ES 3");
				} else if (ok) {
					app.put("status", "accepted");
				} else {
					appVoice(app, log, r.getBoolean("vertexOk") ? map : null, m);
				}
				score(app, m);
				sb.append(app).append('\n');
			}
		}
		Files.writeString(out.toPath(), sb.toString());
		TypoCorpus.deleteTree(scratch);
	}

	static JSONObject voice(JSONObject m, String voice, String device) {
		JSONObject v = new JSONObject();
		v.put("id", m.getString("id"));
		v.put("voice", voice);
		v.put("device", device == null ? JSONObject.NULL : device);
		v.put("kind", m.optString("kind", "none"));
		return v;
	}

	static SourceMap map(JSONObject m) {
		JSONArray a = m.getJSONArray("map");
		List<SourceLocation> lines = new ArrayList<>();
		for (int i = 0; i < a.length(); ++i) {
			String at = a.getString(i);
			if (at.isEmpty()) {
				lines.add(SourceLocation.GENERATED);
			} else {
				int colon = at.lastIndexOf(':');
				lines.add(new SourceLocation(ProjectPath.parse(at.substring(0,
						colon)).value(), Integer.parseInt(at.substring(colon + 1)),
						null));
			}
		}
		return new SourceMap(lines);
	}

	// Where line n of the assembled text is written: "file:line", or "".
	static String place(JSONObject m, int line) {
		JSONArray a = m.getJSONArray("map");
		if (line > a.length()) {
			// The end of the text: its last line that a file has.
			for (int i = a.length() - 1; i >= 0; --i) {
				if (!a.getString(i).isEmpty()) {
					return a.getString(i);
				}
			}
		}
		return line >= 1 && line <= a.length() ? a.getString(line - 1) : "";
	}

	static void setPlace(JSONObject v, String place) {
		if (place == null || place.isEmpty()) {
			v.put("file", JSONObject.NULL).put("line", 0);
			return;
		}
		int colon = place.lastIndexOf(':');
		v.put("file", place.substring(0, colon));
		v.put("line", Integer.parseInt(place.substring(colon + 1)));
	}

	// The first error line of a raw log, as a person reading the log
	// would use it: its line mapped to file and line, and its words.
	static void driverVoice(JSONObject v, String log, boolean ok,
			JSONObject m) {
		v.put("log", log);
		if (ok) {
			v.put("status", "accepted");
			return;
		}
		v.put("status", "rejected");
		for (String line : log.split("\n")) {
			if (line.trim().isEmpty() || line.trim().toUpperCase()
					.startsWith("WARNING") || line.startsWith("Info log for ") ||
					line.contains(": warning:")) {
				continue;
			}
			for (Pattern p : LOCATED) {
				Matcher x = p.matcher(line);
				if (x.matches()) {
					int n = Integer.parseInt(x.group(1));
					v.put("rawLine", n);
					setPlace(v, place(m, n));
					v.put("message", x.group(2).trim());
					return;
				}
			}
			v.put("message", line.trim());
			setPlace(v, null);
			return;
		}
		v.put("message", "");
		setPlace(v, null);
	}

	// What the app shows: the first diagnostic of severity ERROR that
	// DriverLog makes of the log.
	static void appVoice(JSONObject v, String log, SourceMap map,
			JSONObject m) {
		v.put("status", "rejected");
		for (ProjectDiagnostic d : DriverLog.parse(log, map)) {
			if (d.severity() == ProjectDiagnostic.Severity.ERROR) {
				v.put("shown", d.toString());
				v.put("message", d.message());
				if (d.file() == null || d.line() <= 0) {
					setPlace(v, null);
				} else {
					v.put("file", d.file().toString()).put("line", d.line());
				}
				return;
			}
		}
		v.put("shown", "").put("message", "");
		setPlace(v, null);
	}

	static void appOwn(JSONObject v, String error, JSONObject m) {
		v.put("status", "app stops before the driver");
		v.put("shown", error);
		Matcher x = APP_OWN.matcher(error);
		if (x.matches()) {
			v.put("file", x.group(1)).put("line", Integer.parseInt(x.group(2)));
			v.put("message", x.group(3));
		} else {
			setPlace(v, null);
			v.put("message", error);
		}
	}

	static void reader(String text, JSONObject v, JSONObject m) {
		exact.Verdict verdict = exact.Verdict.of(text);
		if (verdict.status.equals("reads")) {
			v.put("status", "reads");
			v.put("message", "");
			setPlace(v, null);
			return;
		}
		if (verdict.status.equals("refuses")) {
			// A limit of the reader shows nothing.
			v.put("status", "refuses (shows nothing)");
			v.put("refusal", verdict.why);
			v.put("message", "");
			setPlace(v, null);
			return;
		}
		v.put("status", verdict.status);
		int line = 1;
		for (int i = 0; i < Math.min(verdict.at, text.length()); ++i) {
			line += text.charAt(i) == '\n' ? 1 : 0;
		}
		v.put("rawLine", line);
		setPlace(v, place(m, line));
		v.put("message", verdict.why);
	}

	static String mesa(String text, int version, File scratch)
			throws Exception {
		File file = File.createTempFile("typo", ".frag", scratch);
		Files.writeString(file.toPath(), text);
		Process p = new ProcessBuilder("/opt/homebrew/bin/glsl_compiler",
				"--version", String.valueOf(version), file.getPath())
				.redirectError(ProcessBuilder.Redirect.DISCARD).start();
		String out = new String(p.getInputStream().readAllBytes(),
				StandardCharsets.UTF_8);
		p.waitFor();
		file.delete();
		return out;
	}

	// LINE: exact / ±1 / elsewhere / none, against the nearest target in
	// the mutated file; "-" where nothing is scored.
	static void score(JSONObject v, JSONObject m) {
		if (!m.has("kind")) {
			return;
		}
		String status = v.getString("status");
		if (status.startsWith("accepted") || status.startsWith("n/a") ||
				status.startsWith("app stops: needs")) {
			v.put("lineScore", "-");
			return;
		}
		if (status.equals("reads") || status.startsWith("refuses") ||
				status.equals("crashed")) {
			v.put("lineScore", "none");
			return;
		}
		if (v.isNull("file") || v.getInt("line") <= 0) {
			v.put("lineScore", "none");
			return;
		}
		if (!v.getString("file").equals(m.getString("file"))) {
			v.put("lineScore", "elsewhere");
			return;
		}
		int line = v.getInt("line"), best = Integer.MAX_VALUE;
		JSONArray t = m.getJSONArray("targets");
		for (int i = 0; i < t.length(); ++i) {
			best = Math.min(best, Math.abs(line - t.getInt(i)));
		}
		v.put("lineScore", best == 0 ? "exact" : best == 1 ? "±1" : "elsewhere");
		v.put("distance", best);
	}
}

package de.markusfisch.android.shadereditor.opengl;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import de.markusfisch.android.shadereditor.project.DirectoryProjectFiles;
import de.markusfisch.android.shadereditor.project.Preparation;
import de.markusfisch.android.shadereditor.project.ProjectDiagnostic;
import de.markusfisch.android.shadereditor.project.ProjectLimits;
import de.markusfisch.android.shadereditor.project.ProjectPath;
import de.markusfisch.android.shadereditor.project.RenderPlan;
import de.markusfisch.android.shadereditor.project.SourceLocation;
import de.markusfisch.android.shadereditor.project.shadertoy.ShaderToyProject;

/**
 * The projects of the experiment as folders on disk, and a program
 * assembled exactly as the engine compiles it: Preparation.prepare,
 * then PlanSource.of, the engine's own last step before glShaderSource.
 */
final class TypoCorpus {
	/** One project: its files by path, and the file that runs. */
	static final class Project {
		final String name;
		final Map<String, String> files = new TreeMap<>();
		final Map<String, byte[]> other = new TreeMap<>(); // pictures
		final String entry;

		Project(String name, String entry) {
			this.name = name;
			this.entry = entry;
		}
	}

	/** What the engine would compile, or why the app stops before. */
	static final class Assembled {
		String fragment, vertex;
		List<String> map = new ArrayList<>(); // "file:line" or "" per line
		int version;
		List<String> appErrors = new ArrayList<>(); // the app's own, before the driver
		String programFile;
		Assembled probeOnly; // what the driver gets when the app stops before it
	}

	static List<Project> projects(File examples, File fixtures)
			throws IOException {
		List<Project> projects = new ArrayList<>();
		Map<String, String> all = new TreeMap<>();
		Map<String, byte[]> other = new TreeMap<>();
		Path root = examples.toPath();
		try (Stream<Path> walk = Files.walk(root)) {
			for (Path p : (Iterable<Path>) walk.sorted()::iterator) {
				String rel = root.relativize(p).toString();
				if (Files.isRegularFile(p) && (rel.endsWith(".frag") ||
						rel.endsWith(".glsl"))) {
					all.put(rel, Files.readString(p, StandardCharsets.UTF_8));
				} else if (Files.isRegularFile(p)) {
					other.put(rel, Files.readAllBytes(p));
				}
			}
		}
		// The examples are one folder the app opens as one project; each
		// .frag at the top runs as itself, a subfolder runs its main.frag.
		Project examplesProject = new Project("examples", null);
		examplesProject.files.putAll(all);
		examplesProject.other.putAll(other);
		projects.add(examplesProject);
		File[] jsons = fixtures.listFiles((d, n) -> n.endsWith(".json"));
		java.util.Arrays.sort(jsons, Comparator.comparing(File::getName));
		for (File json : jsons) {
			ShaderToyProject.Converted c = ShaderToyProject.ofJson(
					Files.readString(json.toPath(), StandardCharsets.UTF_8));
			if (c.refusal() != null) {
				throw new IllegalStateException(json + ": " + c.refusal());
			}
			Project p = new Project("shadertoy-" +
					json.getName().replace(".json", ""),
					ShaderToyProject.ENTRY);
			p.files.putAll(c.files());
			projects.add(p);
		}
		return projects;
	}

	/** The file the app runs to compile file of project. */
	static String entryFor(Project project, String file) {
		if (project.entry != null) {
			return project.entry;
		}
		int slash = file.indexOf('/');
		if (slash < 0) {
			return file;
		}
		String dir = file.substring(0, slash);
		return dir.equals("lib") ? null : dir + "/main.frag";
	}

	/**
	 * Assemble the program of file (or of the program that includes
	 * it, for a library file: host) with files as they are now.
	 */
	static Assembled assemble(Project project, Map<String, String> files,
			String entry,
			String programFile, int glesVersion, File scratch)
			throws IOException {
		File dir = Files.createTempDirectory(scratch.toPath(), "p").toFile();
		try {
			for (Map.Entry<String, String> e : files.entrySet()) {
				File f = new File(dir, e.getKey());
				f.getParentFile().mkdirs();
				Files.writeString(f.toPath(), e.getValue());
			}
			writeOther(project, dir);
			Preparation prep = Preparation.prepare(new DirectoryProjectFiles(
							dir), ProjectPath.parse(entry).value(),
					ProjectLimits.DEFAULT, UniformTable.builtins());
			Assembled a = new Assembled();
			a.programFile = programFile;
			for (ProjectDiagnostic d : prep.diagnostics()) {
				if (d.severity() == ProjectDiagnostic.Severity.ERROR) {
					a.appErrors.add(d.toString());
				}
			}
			if (prep.plan() == null) {
				return a;
			}
			RenderPlan.Program program = null;
			for (RenderPlan.Program p : prep.plan().programs()) {
				if (p.file().toString().equals(programFile)) {
					program = p;
				}
			}
			if (program == null) {
				a.appErrors.add("no program " + programFile + " in the plan");
				return a;
			}
			List<ProjectDiagnostic> errors = new ArrayList<>();
			PlanSource source = PlanSource.of(program, glesVersion, errors);
			for (ProjectDiagnostic d : errors) {
				a.appErrors.add(d.toString());
			}
			if (source == null) {
				return a;
			}
			a.fragment = source.fragment();
			a.vertex = source.vertex();
			for (SourceLocation at : source.sourceMap().lines()) {
				a.map.add(at == null || at.isGenerated() ? ""
						: at.file() + ":" + at.line());
			}
			a.version = de.markusfisch.android.shadereditor.project
					.CompiledSource.version(
							de.markusfisch.android.shadereditor.project
									.PreprocessorScan.scan(program.lines(), null));
			return a;
		} finally {
			deleteTree(dir);
		}
	}

	static void writeOther(Project project, File dir) throws IOException {
		for (Map.Entry<String, byte[]> e : project.other.entrySet()) {
			File f = new File(dir, e.getKey());
			f.getParentFile().mkdirs();
			Files.write(f.toPath(), e.getValue());
		}
	}

	static void deleteTree(File f) {
		File[] children = f.listFiles();
		if (children != null) {
			for (File c : children) {
				deleteTree(c);
			}
		}
		f.delete();
	}
}

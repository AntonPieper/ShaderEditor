package de.markusfisch.android.shadereditor.project;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import de.markusfisch.android.shadereditor.project.HostDirective.Frame;
import de.markusfisch.android.shadereditor.project.RenderPlan.Sampling;
import de.markusfisch.android.shadereditor.project.RenderPlan.Source;

/**
 * A running shader prepared for rendering. Rendering takes the plan,
 * the editor the diagnostics, and sharing and wallpaper snapshots the
 * dependencies.
 *
 * @param plan         what to render, or null when diagnostics contain
 *                     an error
 * @param diagnostics  problems with file and line
 * @param dependencies every file read, in order, with the revision
 *                     used; a null revision means the file was absent
 *                     or could not be read. Complete also when there is
 *                     no plan.
 * @param uses         the dependencies the running shader is made of,
 *                     in order: its passes, includes and images, not
 *                     the manifest nor a file read only to check that
 *                     the manifest's entry exists
 * @param files        the content of every file read, by path in the
 *                     order of reading: later stages use these bytes
 *                     and never read a file again
 */
public record Preparation(
		RenderPlan plan,
		List<ProjectDiagnostic> diagnostics,
		List<SourceAssembly.Dependency> dependencies,
		List<SourceAssembly.Dependency> uses,
		Map<ProjectPath, ProjectFiles.Content> files) {
	public Preparation {
		diagnostics = List.copyOf(diagnostics);
		dependencies = List.copyOf(dependencies);
		uses = List.copyOf(uses);
		files = Collections.unmodifiableMap(new LinkedHashMap<>(files));
	}

	/**
	 * Prepare the running shader entry of a project, a text file of any
	 * name. Every file is read once, and only the manifest, its entry
	 * and files the running shader depends on are read. What a file an
	 * input names is, a picture or a pass, is told from those bytes;
	 * images are not decoded. builtins are the uniforms the engine
	 * provides.
	 */
	public static Preparation prepare(
			ProjectFiles files,
			ProjectPath entry,
			ProjectLimits limits,
			Builtins builtins) {
		CapturedFiles captured = new CapturedFiles(files, limits);
		List<ProjectDiagnostic> diagnostics = new ArrayList<>();
		ProjectParseResult<ProjectManifest> manifest = readManifest(captured);
		if (manifest != null && !manifest.isSuccess()) {
			diagnostics.add(manifest.diagnostic());
		}
		int before = captured.dependencies().size();
		PassGraph graph = PassGraph.discover(captured, entry, limits,
				builtins);
		List<SourceAssembly.Dependency> read = captured.dependencies();
		List<SourceAssembly.Dependency> uses = read.subList(before,
				read.size());
		diagnostics.addAll(graph.diagnostics);
		if (manifest != null && manifest.isSuccess() &&
				!manifest.value().entry().equals(entry)) {
			ProjectDiagnostic missing = checkEntry(captured,
					manifest.value().entry());
			if (missing != null) {
				diagnostics.add(missing);
			}
		}
		RenderPlan plan = hasErrors(diagnostics)
				? null
				: plan(graph, diagnostics);
		return new Preparation(plan, diagnostics, captured.dependencies(),
				uses, captured.contents());
	}

	/**
	 * The warning for initial content of the pass in file that nothing
	 * reads, at its target directive. Preparation gives it when the text
	 * cannot read the previous frame, linking when its backbuffer turns
	 * out to be unused.
	 */
	public static ProjectDiagnostic initialWithoutEffect(ProjectPath pass,
			ProjectPath file, int line) {
		return new ProjectDiagnostic(ProjectDiagnostic.Severity.WARNING,
				"initial has no effect because nothing reads the previous " +
						"frame of " + pass + ", read it with frame=previous " +
						"or remove initial", file, line);
	}

	/**
	 * Return what is wrong with the manifest of files, for a folder in
	 * which nothing runs; nothing when it is valid or there is none.
	 */
	public static List<ProjectDiagnostic> checkManifest(ProjectFiles files) {
		ProjectParseResult<ProjectManifest> manifest = readManifest(files);
		return manifest != null && !manifest.isSuccess()
				? List.of(manifest.diagnostic())
				: List.of();
	}

	// Return null when there is no manifest.
	private static ProjectParseResult<ProjectManifest> readManifest(
			ProjectFiles files) {
		ProjectFiles.Read read = files.read(ProjectManifest.FILE);
		if (read == null) {
			return null;
		}
		if (!(read instanceof ProjectFiles.Content content)) {
			return ProjectParseResult.error(CapturedFiles.problem(
					ProjectManifest.FILE, read), ProjectManifest.FILE, 0);
		}
		String text = SourceText.decode(content.bytes());
		if (text == null) {
			return ProjectParseResult.error(ProjectManifest.FILE +
					" is not UTF-8 text", ProjectManifest.FILE, 0);
		}
		return ProjectManifest.parse(text);
	}

	// A configured entry that is not the running shader must exist too.
	private static ProjectDiagnostic checkEntry(ProjectFiles files,
			ProjectPath configured) {
		ProjectFiles.Read read = files.read(configured);
		if (read == null) {
			return error("Entry " + configured + " in " +
					ProjectManifest.FILE + " does not exist",
					ProjectManifest.FILE);
		} else if (!(read instanceof ProjectFiles.Content)) {
			return error(CapturedFiles.problem(configured, read),
					ProjectManifest.FILE);
		}
		return null;
	}

	// Return null after adding the errors when an input is invalid.
	private static RenderPlan plan(
			PassGraph graph,
			List<ProjectDiagnostic> diagnostics) {
		Map<PassGraph.Node, Integer> index = new HashMap<>();
		for (PassGraph.Node node : graph.nodes) {
			index.put(node, index.size());
		}
		List<RenderPlan.Program> programs = new ArrayList<>();
		List<RenderPlan.Pass> passes = new ArrayList<>();
		for (PassGraph.Node node : graph.nodes) {
			int self = index.get(node);
			PassSource source = node.source;
			programs.add(new RenderPlan.Program(node.file, source.lines(),
					source.sourceMap(), source.head()));
			List<RenderPlan.Input> inputs = new ArrayList<>();
			for (PassGraph.Edge edge : node.inputs) {
				HostDirective.Input input = edge.input();
				ProjectParseResult<Sampling> sampling = Sampling.of(input,
						edge.producer() == null);
				if (!sampling.isSuccess()) {
					diagnostics.add(sampling.diagnostic());
					continue;
				}
				Source from = Source.ENGINE;
				Frame frame = Frame.CURRENT;
				if (edge.producer() != null) {
					from = Source.pass(index.get(edge.producer()));
					frame = input.frame() != null ? input.frame() : frame;
				} else if (input.source() != null) {
					from = Source.image(input.source());
				} else if ("backbuffer".equals(input.uniform())) {
					from = Source.pass(self);
					frame = Frame.PREVIOUS;
				}
				inputs.add(new RenderPlan.Input(input.uniform(),
						input.texture(), from, frame, sampling.value(),
						input.file(), input.line()));
			}
			Source initial = null;
			if (node.initialPass != null) {
				initial = Source.pass(index.get(node.initialPass));
			} else if (node.initialImage != null) {
				initial = Source.image(node.initialImage);
			}
			passes.add(new RenderPlan.Pass(self, inputs,
					RenderPlan.Target.of(source.target(), node.file),
					initial, node.history, node.perFrame));
		}
		List<Integer> reset = new ArrayList<>();
		for (PassGraph.Node node : graph.reset) {
			reset.add(index.get(node));
		}
		return hasErrors(diagnostics)
				? null
				: new RenderPlan(programs, passes, index.get(graph.entry),
						reset);
	}

	private static boolean hasErrors(List<ProjectDiagnostic> diagnostics) {
		for (ProjectDiagnostic diagnostic : diagnostics) {
			if (diagnostic.severity() == ProjectDiagnostic.Severity.ERROR) {
				return true;
			}
		}
		return false;
	}

	private static ProjectDiagnostic error(String message, ProjectPath file) {
		return new ProjectDiagnostic(ProjectDiagnostic.Severity.ERROR,
				message, file, 0);
	}
}

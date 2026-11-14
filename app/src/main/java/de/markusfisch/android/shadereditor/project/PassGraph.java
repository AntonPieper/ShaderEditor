package de.markusfisch.android.shadereditor.project;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import de.markusfisch.android.shadereditor.project.HostDirective.Frame;
import de.markusfisch.android.shadereditor.project.HostDirective.TextureKind;

/**
 * The pass files reachable from the running shader through shader
 * inputs, current and previous, and initial sources. One normalized
 * path is one pass. Only reachable files are read.
 */
final class PassGraph {
	/**
	 * One pass file. {@code source} is null when the file cannot be
	 * read, and {@code problem} says why.
	 */
	static final class Node {
		final ProjectPath file;
		// What the file is written in, by its name.
		final ShaderLanguage language;
		final List<Edge> inputs = new ArrayList<>();
		String problem;
		PassSource source;
		Node initialPass;
		ProjectPath initialImage;
		boolean history;
		boolean perFrame;
		// Whether the source, with its includes, has an entry point, as
		// far as the pass graph tells.
		boolean entryPoint;

		private Node(ProjectPath file) {
			this.file = file;
			language = ShaderLanguage.of(file.toString());
		}

		boolean hasInitial() {
			return initialPass != null || initialImage != null;
		}
	}

	/**
	 * An input of a pass; producer is the pass it reads, or null for an
	 * image or an engine sampler.
	 */
	record Edge(HostDirective.Input input, Node producer) {
	}

	private record Link(Node from, Node to, ProjectPath file, int line,
			String verb) {
	}

	private static final Comparator<Node> BY_PATH = (a, b) ->
			a.file.toString().compareTo(b.file.toString());
	private static final Pattern BACKBUFFER = Pattern.compile(
			"\\bbackbuffer\\b");

	/** Passes in execution order, or in discovery order on errors. */
	final List<Node> nodes = new ArrayList<>();
	/** Passes with an initial source, in the order of reset. */
	final List<Node> reset = new ArrayList<>();
	final List<ProjectDiagnostic> diagnostics = new ArrayList<>();
	/** The running shader's pass, or null when the pass limit is 0. */
	final Node entry;

	private final Map<ProjectPath, Node> byPath = new HashMap<>();
	// The files inputs name that are pictures, by their bytes.
	private final Set<ProjectPath> pictures = new HashSet<>();
	private final CapturedFiles files;
	private final ProjectLimits limits;
	private final Builtins builtins;
	private boolean stopped;

	private PassGraph(CapturedFiles files, ProjectPath entry,
			ProjectLimits limits, Builtins builtins) {
		this.files = files;
		this.limits = limits;
		this.builtins = builtins;
		this.entry = node(entry, entry, 0);
	}

	static PassGraph discover(
			CapturedFiles files,
			ProjectPath entry,
			ProjectLimits limits,
			Builtins builtins) {
		PassGraph graph = new PassGraph(files, entry, limits, builtins);
		if (graph.entry == null) {
			return graph;
		} else if (graph.entry.source == null) {
			graph.error(entry, 0, graph.entry.problem);
		} else if (!graph.entry.entryPoint) {
			graph.error(entry, 0, entry + " defines no " +
					graph.entry.language.entryPoint() + ", so it cannot run");
		}
		graph.validate();
		return graph;
	}

	private Node node(ProjectPath file, ProjectPath from, int line) {
		Node node = byPath.get(file);
		if (node != null || stopped) {
			return node;
		} else if (byPath.size() >= limits.passes()) {
			stopped = true;
			error(from, line, "Project has more than the limit of " +
					limits.passes() + " passes");
			return null;
		}
		node = new Node(file);
		byPath.put(file, node);
		nodes.add(node);
		ProjectFiles.Read read = files.read(file);
		node.problem = CapturedFiles.problem(file, read);
		if (node.problem == null) {
			// A pass is text, whatever the file is called.
			FileKind kind = FileKind.of(
					((ProjectFiles.Content) read).bytes());
			node.problem = kind == FileKind.TEXT ? null
					: kind == FileKind.PICTURE
					? file + " is a picture, not a shader"
					: file + " is not text";
		}
		if (node.problem == null) {
			node.problem = node.language.refusal(file);
		}
		if (node.problem != null) {
			return node;
		}
		node.source = node.language.pass(files, file, limits, builtins);
		// Not told for a file with errors of its own: they come first,
		// and may be why no entry point is found.
		node.entryPoint = node.source.entryPoint() ||
				hasError(node.source.diagnostics());
		diagnostics.addAll(node.source.diagnostics());
		for (HostDirective.Input input : node.source.inputs()) {
			Node producer = source(input.source(), input.file(),
					input.line());
			check(node, input, producer);
			node.inputs.add(new Edge(input, producer));
			if (producer != null && input.frame() == Frame.PREVIOUS) {
				producer.history = true;
			} else if (input.source() == null &&
					"backbuffer".equals(input.uniform())) {
				node.history = true;
			}
		}
		HostDirective.Target target = node.source.target();
		ProjectPath initial = target != null ? target.initial() : null;
		if (initial != null) {
			node.initialPass = source(initial, target.file(), target.line());
			node.initialImage = pictures.contains(initial) ? initial : null;
		}
		for (HostDirectives.Reference reference :
				node.source.references()) {
			source(reference.path(), reference.file(), reference.line());
		}
		return node;
	}

	// Return the pass of a source that is text, null for a picture:
	// what a file an input names is used as follows from its bytes.
	private Node source(ProjectPath source, ProjectPath from, int line) {
		if (source == null || stopped) {
			return null;
		}
		ProjectFiles.Read read = files.read(source);
		String problem = CapturedFiles.problem(source, read);
		if (problem != null) {
			error(from, line, problem);
			return null;
		}
		FileKind kind = FileKind.of(((ProjectFiles.Content) read).bytes());
		if (kind == FileKind.PICTURE) {
			pictures.add(source);
			return null;
		} else if (kind == FileKind.OTHER) {
			error(from, line, source + " is neither a picture (PNG, " +
					"JPEG) nor text");
			return null;
		}
		boolean seen = byPath.containsKey(source);
		Node producer = node(source, from, line);
		if (producer != null && producer.source == null) {
			error(from, line, producer.problem);
		} else if (producer != null && !producer.entryPoint && !seen) {
			error(from, line, source + " defines no " +
					producer.language.entryPoint() + ", so it cannot " +
					"be an input; include it instead");
		}
		return producer;
	}

	// The options of an input that depend on what its file is.
	private void check(Node node, HostDirective.Input input,
			Node producer) {
		ProjectPath source = input.source();
		if (source == null) {
			return;
		} else if (producer != null) {
			if (input.mipmap() != null) {
				error(input.file(), input.line(), "mipmap= applies only " +
						"to a picture, " + source + " is a pass and has " +
						"no mipmaps");
			}
			if (input.texture() != null &&
					input.texture() != TextureKind.FLAT) {
				error(input.file(), input.line(), "A pass is read as " +
						node.language.declaration(TextureKind.FLAT,
								input.uniform()) + ", not " +
						node.language.type(input.texture()));
			}
		} else if (pictures.contains(source) && input.frame() != null) {
			error(input.file(), input.line(), "frame= applies only to " +
					"a pass, " + source + " is a picture; remove it");
		}
	}

	// An initial shader is drawn at reset and sees the initial content
	// of the passes it reads, so it depends on their initial sources.
	private void validate() {
		Map<Node, Set<Link>> current = new HashMap<>();
		Map<Node, Set<Link>> initial = new HashMap<>();
		Set<Node> readAtReset = new HashSet<>();
		for (Node node : nodes) {
			Node shader = node.initialPass;
			if (shader != null) {
				HostDirective.Target target = node.source.target();
				link(initial, node, shader, target.file(), target.line(),
						"starts as");
				for (Edge edge : shader.inputs) {
					if (edge.producer != null && edge.producer != shader &&
							edge.producer.hasInitial()) {
						readAtReset.add(edge.producer);
						link(initial, shader, edge.producer,
								edge.input.file(), edge.input.line(), "reads");
					}
				}
			}
			for (Edge edge : node.inputs) {
				if (edge.producer != null &&
						edge.input.frame() != Frame.PREVIOUS) {
					link(current, node, edge.producer, edge.input.file(),
							edge.input.line(), "reads");
				}
			}
		}
		markPerFrame();
		List<Node> order = order(current, "Current-frame cycle",
				"read one of them with frame=previous");
		List<Node> resetOrder = order(initial, "Initial source cycle",
				"start one of them from an image or from black");
		if (order != null) {
			nodes.clear();
			nodes.addAll(order);
		}
		if (resetOrder != null) {
			for (Node node : resetOrder) {
				if (node.hasInitial()) {
					reset.add(node);
				}
			}
		}
		// With a backbuffer in the text, linking decides.
		for (Node node : nodes) {
			if (node.hasInitial() && !node.history &&
					!readAtReset.contains(node) && !mentionsBackbuffer(node)) {
				HostDirective.Target target = node.source.target();
				diagnostics.add(Preparation.initialWithoutEffect(node.file,
						target.file(), target.line()));
			}
		}
	}

	private static void link(Map<Node, Set<Link>> links, Node from, Node to,
			ProjectPath file, int line, String verb) {
		Set<Link> out = links.get(from);
		if (out == null) {
			out = new LinkedHashSet<>();
			links.put(from, out);
		}
		out.add(new Link(from, to, file, line, verb));
	}

	private void markPerFrame() {
		Deque<Node> queue = new ArrayDeque<>();
		entry.perFrame = true;
		queue.add(entry);
		while (!queue.isEmpty()) {
			for (Edge edge : queue.remove().inputs) {
				if (edge.producer != null && !edge.producer.perFrame) {
					edge.producer.perFrame = true;
					queue.add(edge.producer);
				}
			}
		}
	}

	/**
	 * Report cycles, self-links included, or return the nodes with
	 * every link target before its source, ties broken by path.
	 */
	private List<Node> order(Map<Node, Set<Link>> links, String cycle,
			String hint) {
		List<Node> sorted = new ArrayList<>(nodes);
		Collections.sort(sorted, BY_PATH);
		Map<Node, Integer> state = new HashMap<>();
		int errors = diagnostics.size();
		for (Node node : sorted) {
			visit(node, links, state, new ArrayList<>(), cycle + ": %s, " +
					hint);
		}
		if (diagnostics.size() > errors) {
			return null;
		}
		// Passes are few, so scan for the first ready one by path.
		List<Node> order = new ArrayList<>();
		while (order.size() < sorted.size()) {
			for (Node node : sorted) {
				if (!order.contains(node) && ready(node, links, order)) {
					order.add(node);
					break;
				}
			}
		}
		return order;
	}

	private static boolean ready(Node node, Map<Node, Set<Link>> links,
			List<Node> placed) {
		for (Link link : out(links, node)) {
			if (!placed.contains(link.to)) {
				return false;
			}
		}
		return true;
	}

	// State 1 is on the current path, 2 is done.
	private void visit(Node node, Map<Node, Set<Link>> links,
			Map<Node, Integer> state, List<Link> path, String message) {
		if (state.containsKey(node)) {
			return;
		}
		state.put(node, 1);
		for (Link link : out(links, node)) {
			Integer seen = state.get(link.to);
			path.add(link);
			if (seen == null) {
				visit(link.to, links, state, path, message);
			} else if (seen == 1) {
				reportCycle(path, message);
			}
			path.remove(path.size() - 1);
		}
		state.put(node, 2);
	}

	private static Set<Link> out(Map<Node, Set<Link>> links, Node node) {
		Set<Link> out = links.get(node);
		return out != null ? out : Set.of();
	}

	private void reportCycle(List<Link> path, String message) {
		Node start = path.get(path.size() - 1).to;
		int first = path.size() - 1;
		while (path.get(first).from != start) {
			--first;
		}
		StringBuilder files = new StringBuilder(start.file.toString());
		StringBuilder sites = new StringBuilder();
		for (Link link : path.subList(first, path.size())) {
			files.append(" -> ").append(link.to.file);
			sites.append(sites.length() > 0 ? ", " : "")
					.append(link.file).append(':').append(link.line)
					.append(' ').append(link.verb).append(' ')
					.append(link.to.file);
		}
		Link at = path.get(first);
		error(at.file, at.line, String.format(message,
				files + " (" + sites + ")"));
	}

	private static boolean hasError(List<ProjectDiagnostic> diagnostics) {
		for (ProjectDiagnostic diagnostic : diagnostics) {
			if (diagnostic.severity() == ProjectDiagnostic.Severity.ERROR) {
				return true;
			}
		}
		return false;
	}

	// Whether the code of node, comments apart, names the backbuffer.
	private static boolean mentionsBackbuffer(Node node) {
		for (String code : node.source.code()) {
			if (BACKBUFFER.matcher(code).find()) {
				return true;
			}
		}
		return false;
	}

	private void error(ProjectPath file, int line, String message) {
		diagnostics.add(new ProjectDiagnostic(ProjectDiagnostic.Severity.ERROR,
				message, file, line));
	}
}

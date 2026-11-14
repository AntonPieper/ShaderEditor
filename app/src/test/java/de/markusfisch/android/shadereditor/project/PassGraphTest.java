package de.markusfisch.android.shadereditor.project;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class PassGraphTest {
	private static final String MAIN = "void main() {}";

	@Test
	public void g01ReadsOnlyEntryDependencies() {
		MemoryProjectFiles files = new MemoryProjectFiles()
				.put("main.frag", MAIN)
				.put("broken.glsl", "#pragma se nonsense\n#include <x>\n")
				.put("empty.glsl", "")
				.put("notes.txt", new byte[]{(byte) 0xff});
		PassGraph graph = discover(files);
		assertTrue(graph.diagnostics.isEmpty());
		assertEquals(List.of("main.frag"), paths(graph.nodes));
		assertEquals(List.of(path("main.frag")), files.readPaths);
	}

	@Test
	public void g01KeepsDuplicateBasenamesApart() {
		PassGraph graph = discover(new MemoryProjectFiles()
				.put("main.frag", lines(
						input("b/blur.glsl", "later"),
						input("a/blur.glsl", "earlier"),
						MAIN))
				.put("a/blur.glsl", MAIN)
				.put("b/blur.glsl", MAIN));
		assertTrue(graph.diagnostics.isEmpty());
		assertEquals(List.of("a/blur.glsl", "b/blur.glsl", "main.frag"),
				paths(graph.nodes));
		assertEquals("b/blur.glsl",
				graph.entry.inputs.get(0).producer().file.toString());
		assertEquals("a/blur.glsl",
				graph.entry.inputs.get(1).producer().file.toString());
	}

	@Test
	public void g02DiamondRunsSharedProducerOnceInPathOrder() {
		MemoryProjectFiles files = new MemoryProjectFiles()
				.put("main.frag", lines(
						input("c.glsl", "c"),
						input("b.glsl", "b"),
						input("d.glsl", "d"),
						MAIN))
				.put("b.glsl", lines(input("d.glsl", "d"), MAIN))
				.put("c.glsl", lines(input("d.glsl", "d"), MAIN))
				.put("d.glsl", MAIN);
		PassGraph graph = discover(files);
		assertTrue(graph.diagnostics.isEmpty());
		assertEquals(List.of("d.glsl", "b.glsl", "c.glsl", "main.frag"),
				paths(graph.nodes));
		PassGraph.Node d = graph.nodes.get(0);
		assertSame(d, graph.entry.inputs.get(2).producer());
		assertSame(d, graph.nodes.get(1).inputs.get(0).producer());
		assertEquals(4, files.reads);
	}

	@Test
	public void g02SameProducerReferencedTwiceIsOnePass() {
		PassGraph graph = discover(new MemoryProjectFiles()
				.put("main.frag", lines(
						input("p.glsl", "now"),
						previous("./sub/../p.glsl", "then"),
						MAIN))
				.put("p.glsl", MAIN));
		assertTrue(graph.diagnostics.isEmpty());
		assertEquals(List.of("p.glsl", "main.frag"), paths(graph.nodes));
		assertSame(graph.entry.inputs.get(0).producer(),
				graph.entry.inputs.get(1).producer());
	}

	@Test
	public void g03RejectsCurrentSelfRead() {
		PassGraph graph = discover(new MemoryProjectFiles()
				.put("main.frag", lines(
						"precision mediump float;",
						input("main.frag", "self"),
						MAIN)));
		assertEquals(List.of("ERROR main.frag:2 Current-frame cycle: " +
						"main.frag -> main.frag (main.frag:2 reads " +
						"main.frag), read one of them with frame=previous"),
				messages(graph));
	}

	@Test
	public void g03RejectsMultiNodeCycleWithItsInputs() {
		PassGraph graph = discover(new MemoryProjectFiles()
				.put("main.frag", lines(input("pass/a.glsl", "a"), MAIN))
				.put("pass/a.glsl", lines(
						"// a",
						input("b.glsl", "b"),
						MAIN))
				.put("pass/b.glsl", lines(
						input("c.glsl", "c"),
						MAIN))
				.put("pass/c.glsl", lines(input("a.glsl", "a"), MAIN)));
		assertEquals(List.of("ERROR pass/a.glsl:2 Current-frame cycle: " +
						"pass/a.glsl -> pass/b.glsl -> pass/c.glsl -> " +
						"pass/a.glsl (pass/a.glsl:2 reads pass/b.glsl, " +
						"pass/b.glsl:1 reads pass/c.glsl, pass/c.glsl:1 " +
						"reads pass/a.glsl), read one of them with " +
						"frame=previous"),
				messages(graph));
	}

	@Test
	public void g04SelfPreviousIsValidHistory() {
		PassGraph graph = discover(new MemoryProjectFiles()
				.put("main.frag", lines(
						previous("main.frag", "last"),
						MAIN)));
		assertTrue(graph.diagnostics.isEmpty());
		assertTrue(graph.entry.history);
		assertEquals(List.of("main.frag"), paths(graph.nodes));
	}

	@Test
	public void g04TwoPassPreviousCycleTerminates() {
		MemoryProjectFiles files = new MemoryProjectFiles()
				.put("main.frag", lines(input("a.glsl", "a"), MAIN))
				.put("a.glsl", lines(previous("b.glsl", "b"), MAIN))
				.put("b.glsl", lines(previous("a.glsl", "a"), MAIN));
		PassGraph graph = discover(files);
		assertTrue(graph.diagnostics.isEmpty());
		assertEquals(List.of("a.glsl", "b.glsl", "main.frag"),
				paths(graph.nodes));
		assertTrue(graph.nodes.get(0).history);
		assertTrue(graph.nodes.get(1).history);
		assertFalse(graph.entry.history);
		assertEquals(3, files.reads);
	}

	@Test
	public void g05ProducerReachedOnlyThroughPreviousRunsEveryFrame() {
		PassGraph graph = discover(new MemoryProjectFiles()
				.put("main.frag", lines(previous("state.glsl", "s"), MAIN))
				.put("state.glsl", MAIN));
		assertTrue(graph.diagnostics.isEmpty());
		assertEquals(List.of("main.frag", "state.glsl"), paths(graph.nodes));
		PassGraph.Node state = graph.nodes.get(1);
		assertTrue(state.perFrame);
		assertTrue(state.history);
	}

	@Test
	public void g06PreviousReadGivesProducerHistoryWithoutBackbuffer() {
		PassGraph graph = discover(new MemoryProjectFiles()
				.put("main.frag", lines(
						input("now.glsl", "now"),
						previous("then.glsl", "then"),
						MAIN))
				.put("now.glsl", MAIN)
				.put("then.glsl", MAIN));
		assertTrue(graph.diagnostics.isEmpty());
		assertEquals(List.of("main.frag", "now.glsl", "then.glsl"),
				paths(sorted(graph.nodes)));
		assertFalse(graph.entry.inputs.get(0).producer().history);
		assertTrue(graph.entry.inputs.get(1).producer().history);
	}

	@Test
	public void annotatedBackbufferGivesOwnHistory() {
		PassGraph graph = discover(new MemoryProjectFiles()
				.put("main.frag", lines(
						"#pragma se input filter=linear",
						"uniform sampler2D backbuffer;",
						MAIN)));
		assertTrue(graph.diagnostics.isEmpty());
		assertTrue(graph.entry.history);
	}

	@Test
	public void g07UnusedInputWithMissingSourceIsAnError() {
		MemoryProjectFiles files = new MemoryProjectFiles()
				.put("main.frag", lines(
						input("gone.glsl", "unused"),
						input("gone.png", "alsoUnused"),
						MAIN));
		PassGraph graph = discover(files);
		assertEquals(List.of(
						"ERROR main.frag:1 File not found: gone.glsl",
						"ERROR main.frag:3 File not found: gone.png"),
				messages(graph));
		assertEquals(List.of(path("main.frag"), path("gone.glsl"),
				path("gone.png")), files.readPaths);
	}

	@Test
	public void g07UnusedInputInCurrentCycleIsAnError() {
		PassGraph graph = discover(new MemoryProjectFiles()
				.put("main.frag", lines(input("a.glsl", "unused"), MAIN))
				.put("a.glsl", lines(input("main.frag", "unusedToo"), MAIN)));
		assertEquals(1, graph.diagnostics.size());
		assertTrue(graph.diagnostics.get(0).message().startsWith(
				"Current-frame cycle: a.glsl -> main.frag -> a.glsl"));
	}

	@Test
	public void reportsMissingEntry() {
		PassGraph graph = discover(new MemoryProjectFiles());
		assertEquals(List.of("ERROR main.frag:0 File not found: main.frag"),
				messages(graph));
	}

	@Test
	public void g09InitialImageIsReadAndReset() {
		MemoryProjectFiles files = new MemoryProjectFiles()
				.put("main.frag", lines(
						"#pragma se target initial=\"seed.png\"",
						"uniform sampler2D backbuffer;",
						MAIN))
				.put("seed.png", Pictures.png(1, 2, 3));
		PassGraph graph = discover(files);
		assertTrue(graph.diagnostics.isEmpty());
		assertEquals(path("seed.png"), graph.entry.initialImage);
		assertEquals(List.of("main.frag"), paths(graph.reset));
		assertEquals(List.of(path("main.frag"), path("seed.png")),
				files.readPaths);
	}

	@Test
	public void g09InitialShaderIsDrawnOnlyOnReset() {
		PassGraph graph = discover(new MemoryProjectFiles()
				.put("main.frag", lines(
						"#pragma se target initial=\"seed.glsl\"",
						previous("main.frag", "last"),
						MAIN))
				.put("seed.glsl", MAIN));
		assertTrue(graph.diagnostics.isEmpty());
		PassGraph.Node seed = graph.entry.initialPass;
		assertEquals(path("seed.glsl"), seed.file);
		assertFalse(seed.perFrame);
		assertTrue(graph.entry.perFrame);
		assertEquals(List.of("main.frag"), paths(graph.reset));
	}

	@Test
	public void g09InitialShaderReadingPassWithInitialShaderResetsItFirst() {
		PassGraph graph = discover(new MemoryProjectFiles()
				.put("main.frag", lines(
						"#pragma se target initial=\"i.glsl\"",
						previous("main.frag", "last"),
						input("p.glsl", "p"),
						MAIN))
				.put("i.glsl", lines(input("p.glsl", "p"), MAIN))
				.put("p.glsl", lines(
						"#pragma se target initial=\"j.glsl\"",
						previous("p.glsl", "last"),
						MAIN))
				.put("j.glsl", MAIN));
		assertTrue(graph.diagnostics.isEmpty());
		assertEquals(List.of("p.glsl", "main.frag"), paths(graph.reset));
		assertEquals(List.of("i.glsl", "j.glsl"), perFrame(graph, false));
	}

	@Test
	public void g09PassesThatAreEachOthersInitialSourceAreACycle() {
		PassGraph graph = discover(new MemoryProjectFiles()
				.put("main.frag", lines(input("a.glsl", "a"), MAIN))
				.put("a.glsl", lines(
						"#pragma se target initial=\"b.glsl\"",
						previous("a.glsl", "last"),
						MAIN))
				.put("b.glsl", lines(
						previous("b.glsl", "last"),
						"#pragma se target initial=\"a.glsl\"",
						MAIN)));
		assertEquals(List.of("ERROR a.glsl:1 Initial source cycle: " +
						"a.glsl -> b.glsl -> a.glsl (a.glsl:1 starts as " +
						"b.glsl, b.glsl:3 starts as a.glsl), start one of " +
						"them from an image or from black"),
				messages(graph));
		assertTrue(graph.reset.isEmpty());
	}

	@Test
	public void g09InitialWithoutHistoryReadersWarns() {
		PassGraph graph = discover(new MemoryProjectFiles()
				.put("main.frag", lines(
						"// seed",
						"#pragma se target initial=\"seed.png\"",
						MAIN))
				.put("seed.png", Pictures.png(1)));
		assertEquals(List.of("WARNING main.frag:2 initial has no effect " +
						"because nothing reads the previous frame of " +
						"main.frag, read it with frame=previous or remove " +
						"initial"),
				messages(graph));
	}

	@Test
	public void g09InitialReadByAnInitialShaderDoesNotWarn() {
		PassGraph graph = discover(new MemoryProjectFiles()
				.put("main.frag", lines(
						"#pragma se target initial=\"i.glsl\"",
						"uniform sampler2D backbuffer;",
						input("p.glsl", "p"),
						MAIN))
				.put("i.glsl", lines(input("p.glsl", "p"), MAIN))
				.put("p.glsl", lines(
						"#pragma se target initial=\"seed.png\"",
						MAIN))
				.put("seed.png", Pictures.png(1)));
		assertTrue(graph.diagnostics.isEmpty());
		assertEquals(List.of("p.glsl", "main.frag"), paths(graph.reset));
	}

	@Test
	public void missingInitialSourceIsAnError() {
		PassGraph graph = discover(new MemoryProjectFiles()
				.put("main.frag", lines(
						"uniform sampler2D backbuffer;",
						"#pragma se target initial=\"seed.glsl\"",
						MAIN)));
		assertEquals(List.of("ERROR main.frag:2 File not found: seed.glsl"),
				messages(graph));
	}

	@Test
	public void rejectsMorePassesThanTheLimit() {
		ProjectLimits limits = new ProjectLimits(16, 1000, 100, 64, 2, 100);
		PassGraph graph = PassGraph.discover(new CapturedFiles(
						new MemoryProjectFiles()
								.put("main.frag", lines(input("a.glsl", "a"),
										MAIN))
								.put("a.glsl", lines(input("b.glsl", "b"), MAIN))
								.put("b.glsl", MAIN), limits),
				path("main.frag"), limits, BuiltinNames.ALL);
		assertEquals(List.of("ERROR a.glsl:1 Project has more than the " +
				"limit of 2 passes"), messages(graph));
	}

	private static String input(String source, String name) {
		return "#pragma se input \"" + source + "\"\nuniform sampler2D " +
				name + ";";
	}

	private static String previous(String source, String name) {
		return "#pragma se input \"" + source + "\" frame=previous\n" +
				"uniform sampler2D " + name + ";";
	}

	private static String lines(String... lines) {
		return String.join("\n", lines) + "\n";
	}

	private static PassGraph discover(MemoryProjectFiles files) {
		return PassGraph.discover(new CapturedFiles(files,
				ProjectLimits.DEFAULT), path("main.frag"), ProjectLimits.DEFAULT,
				BuiltinNames.ALL);
	}

	private static List<String> messages(PassGraph graph) {
		List<String> messages = new ArrayList<>();
		for (ProjectDiagnostic d : graph.diagnostics) {
			messages.add(d.severity() + " " + d.file() + ":" + d.line() +
					" " + d.message());
		}
		return messages;
	}

	private static List<String> paths(List<PassGraph.Node> nodes) {
		List<String> paths = new ArrayList<>();
		for (PassGraph.Node node : nodes) {
			paths.add(node.file.toString());
		}
		return paths;
	}

	private static List<PassGraph.Node> sorted(List<PassGraph.Node> nodes) {
		List<PassGraph.Node> sorted = new ArrayList<>(nodes);
		sorted.sort((a, b) -> a.file.toString().compareTo(b.file.toString()));
		return sorted;
	}

	private static List<String> perFrame(PassGraph graph, boolean perFrame) {
		List<PassGraph.Node> matching = new ArrayList<>();
		for (PassGraph.Node node : sorted(graph.nodes)) {
			if (node.perFrame == perFrame) {
				matching.add(node);
			}
		}
		return paths(matching);
	}

	private static ProjectPath path(String path) {
		return ProjectPath.parse(path).value();
	}
}

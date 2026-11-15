package de.markusfisch.android.shadereditor.navigation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;
import java.util.Set;

public class ReadmeTest {
	private static final Set<String> NAMES = Set.of("Shapes.frag",
			"Loop.frag", "Horizon.frag", "Ink", "lib", "textures",
			"Grüße.frag", "a b.frag", "README.md");

	// The examples' README: headings, order and lines.
	@Test
	public void headingsOrderAndLines() {
		List<Readme.Section> sections = Readme.sections("""
				# Examples

				Each example maps one group of inputs to a picture.

				## Start here
				- [Shapes](Shapes.frag) Touch the screen; the orbit moves
				- [Loop](Loop.frag) Rotate to restart

				## Wallpapers
				- [Horizon](Horizon.frag) Swipe home screen pages
				- [Ink](Ink/) Tilt the phone; the ink drifts downhill

				## Shared files
				- [lib](lib/) Code the examples include
				- [textures](textures) Pictures the examples read
				""", NAMES);
		assertEquals(3, sections.size());
		assertEquals(new Readme.Section("Start here", List.of(
				new Readme.Linked("Shapes.frag",
						"Touch the screen; the orbit moves"),
				new Readme.Linked("Loop.frag", "Rotate to restart"))),
				sections.get(0));
		assertEquals(List.of(new Readme.Linked("Horizon.frag",
						"Swipe home screen pages"),
				new Readme.Linked("Ink", "Tilt the phone; the ink drifts " +
						"downhill")), sections.get(1).entries());
		assertEquals("textures", sections.get(2).entries().get(1).name());
	}

	// Links that are not to an entry directly in the folder count for
	// nothing, and nothing is an error.
	@Test
	public void ignoresWhatIsNotAnEntryHere() {
		List<Readme.Section> sections = Readme.sections("""
				## Links
				- [Site](https://example.com) the web
				- [Deep](lib/sdf.glsl) too deep
				- [Gone](gone.frag) not there
				- ![Picture](textures/noise.png)
				Some text with [Loop](Loop.frag) in it.
				### Not a section
				- [Loop](Loop.frag)
				""", NAMES);
		assertEquals(List.of(new Readme.Section("Links", List.of(
				new Readme.Linked("Loop.frag", null)))), sections);
		assertTrue(Readme.sections("# Title\n\nNo links.\n", NAMES).isEmpty());
		assertTrue(Readme.sections("", NAMES).isEmpty());
	}

	// Other list marks, encoded names, a dash or colon before the line.
	@Test
	public void readsTheWaysALinkIsWritten() {
		List<Readme.Linked> entries = Readme.sections("""
				* [Grüße](Gr%C3%BC%C3%9Fe.frag) — greetings
				1. [A b](<a b.frag>): spaced
				2) [Ink](./Ink/ "the ink") - ink
				+ [Loop](Loop.frag)
				""", NAMES).get(0).entries();
		assertEquals(List.of(new Readme.Linked("Grüße.frag", "greetings"),
				new Readme.Linked("a b.frag", "spaced"),
				new Readme.Linked("Ink", "ink"),
				new Readme.Linked("Loop.frag", null)), entries);
	}

	// An entry linked twice counts where it is first linked; list items
	// before any heading form a section without one.
	@Test
	public void anEntryCountsOnce() {
		List<Readme.Section> sections = Readme.sections("""
				- [Loop](Loop.frag) first
				## Again
				- [Loop](Loop.frag) second
				- [Shapes](Shapes.frag)
				""", NAMES);
		assertEquals(new Readme.Section(null, List.of(
				new Readme.Linked("Loop.frag", "first"))), sections.get(0));
		assertEquals(List.of(new Readme.Linked("Shapes.frag", null)),
				sections.get(1).entries());
	}

	@Test
	public void aReadmeIsNamedInAnyCase() {
		assertTrue(Readme.is("README.md"));
		assertTrue(Readme.is("readme.MD"));
		assertEquals(false, Readme.is("README.txt"));
	}
}

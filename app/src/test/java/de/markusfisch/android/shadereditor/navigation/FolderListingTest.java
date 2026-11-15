package de.markusfisch.android.shadereditor.navigation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.markusfisch.android.shadereditor.project.FileRole;
import de.markusfisch.android.shadereditor.project.OpenProject;
import de.markusfisch.android.shadereditor.project.ProjectPath;

/** The sections a level of a folder lists, from what is known of it. */
public class FolderListingTest {
	private static final Level ROOT = Level.root("abcd1234");
	private static final FolderListing.Words WORDS =
			new FolderListing.Words() {
				@Override
				public String running() {
					return "Running";
				}

				@Override
				public String allFiles() {
					return "All files";
				}

				@Override
				public String otherFiles() {
					return "Other files";
				}

				@Override
				public String readmeOrders() {
					return "Orders this list";
				}

				@Override
				public String showMore() {
					return "Show more";
				}

				@Override
				public String shownOf() {
					return "%1$d of %2$d";
				}

				@Override
				public String showAllRunning(int count) {
					return "Show all " + count;
				}

				@Override
				public String role(FileRole.Kind kind, String of) {
					return kind + " " + of;
				}

				@Override
				public String cannotOpen(long bytes) {
					return "Cannot be opened here";
				}

				@Override
				public String runs(String entry) {
					return "Runs " + entry;
				}

				@Override
				public String defaultShader() {
					return "Default shader";
				}

				@Override
				public String defaultShaderLine() {
					return "Tap to run it";
				}

				@Override
				public String changed(long time) {
					return "date";
				}
			};

	// Without a README and without Running: one section, no heading.
	@Test
	public void aFolderAsItIs() {
		List<Listing.Section> sections = list(entries("b.frag", "a.frag"),
				"", List.of());
		assertEquals(1, sections.size());
		assertNull(sections.get(0).heading());
		assertEquals(List.of("a.frag", "b.frag"), names(sections.get(0)));
	}

	@Test
	public void theReadmeGivesHeadingsOrderAndLines() {
		List<Listing.Section> sections = list(entries("a.frag", "b.frag",
				"c.frag", "README.md"), """
				## Start
				- [B](b.frag) The second
				- [A](a.frag)
				""", List.of());
		assertEquals("Start", sections.get(0).heading());
		assertEquals(List.of("b.frag", "a.frag"), names(sections.get(0)));
		assertEquals("The second", sections.get(0).rows().get(0).detail());
		assertEquals("Other files", sections.get(1).heading());
		assertEquals(List.of("c.frag", "README.md"), names(sections.get(1)));
		assertEquals("Orders this list",
				sections.get(1).rows().get(1).detail());
	}

	// A README that links itself appears once, where it links itself.
	@Test
	public void aReadmeThatLinksItselfAppearsOnce() {
		List<Listing.Section> sections = list(entries("a.frag",
				"README.md"), """
				## Notes
				- [About](README.md)
				- [A](a.frag)
				""", List.of());
		assertEquals(1, sections.size());
		assertEquals(List.of("README.md", "a.frag"), names(sections.get(0)));
		assertEquals("Orders this list",
				sections.get(0).rows().get(0).detail());
	}

	// Links before the first heading stand first while nothing stands
	// above them, without a heading.
	@Test
	public void linksBeforeTheFirstHeadingStandFirst() {
		List<Listing.Section> sections = list(entries("a.frag", "b.frag",
				"c.frag"), """
				- [C](c.frag) First of all
				## Then
				- [A](a.frag)
				""", List.of());
		assertNull(sections.get(0).heading());
		assertEquals(List.of("c.frag"), names(sections.get(0)));
		assertEquals("Then", sections.get(1).heading());
		assertEquals(List.of("b.frag"), names(sections.get(2)));
	}

	// Under Running they would read as part of it: they lead the rest,
	// which has a heading.
	@Test
	public void linksBeforeTheFirstHeadingFollowRunningUnderAHeading() {
		List<OpenProject.Used> used = List.of(
				new OpenProject.Used(path("a.frag"), false,
						new FileRole(FileRole.Kind.RUNS, List.of())),
				new OpenProject.Used(path("b.frag"), false,
						new FileRole(FileRole.Kind.PASS, List.of())));
		List<Listing.Section> sections = list(entries("a.frag", "b.frag",
				"c.frag", "d.frag"), """
				- [C](c.frag)
				## Then
				- [A](a.frag)
				""", used);
		assertEquals("Running", sections.get(0).heading());
		assertEquals("Then", sections.get(1).heading());
		assertEquals("Other files", sections.get(2).heading());
		assertEquals(List.of("c.frag", "b.frag", "d.frag"),
				names(sections.get(2)));
	}

	// In a folder of more than a page, a linked file past the first
	// page is in its section, and pages count only the rest.
	@Test
	public void linkedFilesStayInTheirSectionAcrossPages() {
		List<String> names = new ArrayList<>();
		for (int i = 0; i < 450; ++i) {
			names.add(String.format("f%03d.frag", i));
		}
		List<Listing.Section> sections = list(entries(names.toArray(
				new String[0])), """
				## Late
				- [Last](f449.frag)
				""", List.of());
		assertEquals(List.of("f449.frag"), names(sections.get(0)));
		List<Row> rest = sections.get(1).rows();
		assertEquals(Reading.PAGE + 1, rest.size());
		Row more = rest.get(rest.size() - 1);
		assertEquals(Row.Kind.MORE, more.kind());
		assertEquals("200 of 449", more.detail());
	}

	// A link to a file that is not there, and a second link to one that
	// is, change nothing.
	@Test
	public void missingAndDuplicateLinksChangeNothing() {
		List<Listing.Section> sections = list(entries("a.frag", "b.frag"),
				"""
				## Gone
				- [X](gone.frag)
				## Here
				- [A](a.frag) first
				- [A again](a.frag) second
				""", List.of());
		assertEquals(2, sections.size());
		assertEquals("Here", sections.get(0).heading());
		assertEquals(List.of("a.frag"), names(sections.get(0)));
		assertEquals("first", sections.get(0).rows().get(0).detail());
		assertEquals(List.of("b.frag"), names(sections.get(1)));
		assertTrue(list(entries("a.frag"), "## Only\n- [X](x.frag)\n",
				List.of()).size() == 1);
	}

	// Running: the build's files in build order with their roles, six
	// and Show all from eight; above All files.
	@Test
	public void runningStandsAboveAllFiles() {
		List<OpenProject.Used> used = new ArrayList<>();
		used.add(new OpenProject.Used(path("main.frag"), false,
				new FileRole(FileRole.Kind.RUNS, List.of())));
		for (int i = 0; i < 8; ++i) {
			used.add(new OpenProject.Used(path("lib/l" + i + ".glsl"), false,
					new FileRole(FileRole.Kind.INCLUDE,
							List.of(path("main.frag")))));
		}
		List<Listing.Section> sections = list(entries("main.frag"), "",
				used);
		assertEquals("Running", sections.get(0).heading());
		assertEquals(7, sections.get(0).rows().size());
		assertEquals("Show all 9", sections.get(0).rows().get(6).name());
		assertEquals("INCLUDE main.frag",
				sections.get(0).rows().get(1).detail());
		assertEquals("All files", sections.get(1).heading());
	}

	// A file under Running has its menu, as in its own folder: Run where
	// it can run and does not, and changes where that folder was read
	// and can be changed.
	@Test
	public void runningRowsHaveTheirMenu() {
		List<OpenProject.Used> used = List.of(
				new OpenProject.Used(path("main.frag"), false,
						new FileRole(FileRole.Kind.RUNS, List.of())),
				new OpenProject.Used(path("pass.frag"), false,
						new FileRole(FileRole.Kind.PASS, List.of())),
				new OpenProject.Used(path("lib/sdf.glsl"), false,
						new FileRole(FileRole.Kind.INCLUDE,
								List.of(path("main.frag")))));
		List<Row> running = FolderListing.sections(new FolderListing.Facts(
				ROOT, Order.BY_NAME, Reading.started().listed(entries(
						"main.frag", "pass.frag")), Map.of(), null, null,
				null, List.of(), used, false, "main.frag", (key, name) -> null,
				true, Set.of(""), Set.of()), WORDS).get(0).rows();
		assertEquals(Set.of(Row.Action.RENAME, Row.Action.DUPLICATE,
				Row.Action.DELETE), running.get(0).actions());
		assertEquals(Set.of(Row.Action.RUN, Row.Action.RENAME,
				Row.Action.DUPLICATE, Row.Action.DELETE),
				running.get(1).actions());
		// lib was not read: nothing that changes it is offered.
		assertEquals(Set.of(Row.Action.RUN), running.get(2).actions());
	}

	// A folder where nothing ran yet offers what would run first; an
	// invalid manifest says why.
	@Test
	public void theDefaultShaderStandsFirstWhereNothingRanYet() {
		List<Listing.Section> sections = FolderListing.sections(
				new FolderListing.Facts(ROOT, Order.BY_NAME,
						Reading.started().listed(entries("blur.frag",
								"main.frag", "shader-project.json")),
						Map.of(), null, "Not valid: entry is missing",
						"main.frag", List.of(), List.of(), false, "main.frag",
						(key, name) -> null, true, Set.of(""), Set.of()),
				WORDS);
		assertEquals("Default shader", sections.get(0).heading());
		assertEquals("main.frag", sections.get(0).rows().get(0).name());
		assertEquals("Tap to run it", sections.get(0).rows().get(0).detail());
		assertEquals("All files", sections.get(1).heading());
		assertEquals("Not valid: entry is missing",
				sections.get(1).rows().get(2).detail());
	}

	private static List<Listing.Section> list(List<Reading.Entry> entries,
			String readme, List<OpenProject.Used> used) {
		Set<String> names = new HashSet<>();
		for (Reading.Entry entry : entries) {
			names.add(entry.name());
		}
		return FolderListing.sections(new FolderListing.Facts(ROOT,
				Order.BY_NAME, Reading.started().listed(entries), Map.of(),
				null, null, null, Readme.sections(readme, names), used, false,
				null, (key, name) -> null, true, Set.of(""), Set.of()), WORDS);
	}

	private static List<Reading.Entry> entries(String... names) {
		List<Reading.Entry> entries = new ArrayList<>();
		for (String name : names) {
			entries.add(new Reading.Entry(name, false, 1));
		}
		return entries;
	}

	private static List<String> names(Listing.Section section) {
		List<String> names = new ArrayList<>();
		for (Row row : section.rows()) {
			names.add(row.name());
		}
		return names;
	}

	private static ProjectPath path(String path) {
		return ProjectPath.parse(path).value();
	}
}

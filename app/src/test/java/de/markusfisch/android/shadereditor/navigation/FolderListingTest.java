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
				public String running(String shader) {
					return "Running · " + shader;
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
				public String notInFolder() {
					return "Not in this folder";
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
		assertEquals("Running · a.frag", sections.get(0).heading());
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
		assertEquals("Running · main.frag", sections.get(0).heading());
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
				true, Set.of(""), Set.of(), Set.of(), Set.of()), WORDS).get(0).rows();
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
						(key, name) -> null, true, Set.of(""), Set.of(), Set.of(), Set.of()),
				WORDS);
		assertEquals("Default shader", sections.get(0).heading());
		assertEquals("main.frag", sections.get(0).rows().get(0).name());
		assertEquals("Tap to run it", sections.get(0).rows().get(0).detail());
		assertEquals("All files", sections.get(1).heading());
		assertEquals("Not valid: entry is missing",
				sections.get(1).rows().get(2).detail());
		// Once it was built here it has a picture, and stands in its
		// place only: the folder as it is, without a heading.
		assertNull(FolderListing.sections(
				new FolderListing.Facts(ROOT, Order.BY_NAME,
						Reading.started().listed(entries("blur.frag",
								"main.frag")),
						Map.of("main.frag", new byte[]{1}), null, null,
						"main.frag", List.of(), List.of(), false, "main.frag",
						(key, name) -> null, true, Set.of(""), Set.of(), Set.of(), Set.of()),
				WORDS).get(0).heading());
	}

	// A link with nothing after it takes the next line that is text as
	// its line: indented or not, after blank lines; never a heading, a
	// list item or a link, and never a line of the next entry.
	@Test
	public void aLineBelowTheLinkIsItsLine() {
		List<Listing.Section> sections = list(entries("a.frag", "b.frag",
				"c.frag", "d.frag", "e.frag"), """
				## Start
				- [A](a.frag)
				  Below its link
				- [B](b.frag)

				After a blank line
				- [C](c.frag)
				- [D](d.frag) On its own line
				  Not D's, it has one
				- [E](e.frag)
				## Heading, not E's
				""", List.of());
		List<Row> rows = sections.get(0).rows();
		assertEquals(List.of("a.frag", "b.frag", "c.frag", "d.frag",
				"e.frag"), names(sections.get(0)));
		assertEquals("Below its link", rows.get(0).detail());
		assertEquals("After a blank line", rows.get(1).detail());
		// C is followed by a list item: no line from it.
		assertEquals("date", rows.get(2).detail());
		assertEquals("On its own line", rows.get(3).detail());
		assertEquals("date", rows.get(4).detail());
	}

	// A link on the next line is no line, nor is a fenced block.
	@Test
	public void aLinkOrCodeBelowIsNoLine() {
		List<Listing.Section> sections = list(entries("a.frag", "b.frag"),
				"""
				- [A](a.frag)
				[see the website](https://example.com)
				- [B](b.frag)
				```
				code
				```
				""", List.of());
		List<Row> rows = sections.get(0).rows();
		assertEquals("date", rows.get(0).detail());
		assertEquals("date", rows.get(1).detail());
	}

	// A pass never shows a picture of its own: it shows code, as an
	// include does, under Running and in its folder, and says it is a
	// pass; a shader that only never ran keeps its empty frame.
	@Test
	public void aPassShowsCodeNotAnEmptyFrame() {
		List<OpenProject.Used> used = List.of(
				new OpenProject.Used(path("main.frag"), false,
						new FileRole(FileRole.Kind.RUNS, List.of())),
				new OpenProject.Used(path("ink.frag"), false,
						new FileRole(FileRole.Kind.PASS,
								List.of(path("main.frag")))),
				new OpenProject.Used(path("seed.frag"), false,
						new FileRole(FileRole.Kind.FIRST_FRAME,
								List.of(path("ink.frag")))));
		List<Listing.Section> sections = list(entries("main.frag",
				"ink.frag", "seed.frag", "other.frag"), "", used);
		for (Listing.Section section : sections) {
			for (Row row : section.rows()) {
				boolean pass = row.key().equals("ink.frag") ||
						row.key().equals("seed.frag");
				assertEquals(row.key(), pass, row.picture().spoken() ==
						Row.Picture.Spoken.PASS);
				if (pass) {
					assertEquals(Row.Picture.Icon.CODE, row.picture().icon());
				}
			}
		}
		Row other = sections.get(1).rows().get(2);
		assertEquals("other.frag", other.name());
		assertEquals(Row.Picture.Spoken.SHADER_NOT_RUN,
				other.picture().spoken());
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
				null, (key, name) -> null, true, Set.of(""), Set.of(), Set.of(), Set.of()), WORDS);
	}

	// Unsaved text whose file is gone, also with its folder, is listed
	// at the root, marked, whatever the root's listing holds; a folder
	// holding a file in conflict is marked.
	@Test
	public void unsavedTextWhoseFileIsGoneIsListedAtTheRoot() {
		Set<String> unsaved = Set.of("gone.glsl", "notes/scratch.glsl",
				"lib/noise.glsl", "main.frag");
		Set<String> outside = Set.of("gone.glsl", "lib/noise.glsl");
		Set<String> gone = Set.of("gone.glsl", "notes/scratch.glsl");
		List<Reading.Entry> listed = List.of(
				new Reading.Entry("main.frag", false, 1),
				new Reading.Entry("lib", true, 1));

		List<Listing.Section> sections = FolderListing.sections(
				facts(ROOT, Reading.started().listed(listed), unsaved,
						outside, gone), WORDS);

		assertEquals("Not in this folder", sections.get(0).heading());
		List<Row> rows = sections.get(0).rows();
		assertEquals(List.of("gone.glsl", "notes/scratch.glsl"),
				List.of(rows.get(0).name(), rows.get(1).name()));
		assertEquals(Set.of(Row.Mark.UNSAVED, Row.Mark.CHANGED_OUTSIDE),
				rows.get(0).marks());
		assertEquals(Set.of(Row.Mark.UNSAVED), rows.get(1).marks());
		List<Row> all = sections.get(1).rows();
		assertEquals(List.of("lib", "main.frag"),
				List.of(all.get(0).name(), all.get(1).name()));
		assertEquals(Set.of(Row.Mark.UNSAVED, Row.Mark.CHANGED_OUTSIDE),
				all.get(0).marks());
		// Also where the root cannot be listed.
		assertEquals("Not in this folder", FolderListing.sections(
				facts(ROOT, Reading.started().failed("gone"), unsaved,
						outside, gone), WORDS).get(0).heading());
	}

	// Unsaved files whose paths are shorter than the folder shown do not
	// break its listing (from a review's probe).
	@Test
	public void aFolderListsBesideShorterUnsavedPaths() {
		Level folder = ROOT.enter("long-folder");
		List<Listing.Section> sections = FolderListing.sections(
				facts(folder, Reading.started().listed(List.of(
								new Reading.Entry("x.glsl", false, 1))),
						Set.of("a.glsl"), Set.of("a.glsl"), Set.of("a.glsl")),
				WORDS);
		assertEquals(1, sections.size());
		assertEquals("x.glsl", sections.get(0).rows().get(0).name());
	}

	private static FolderListing.Facts facts(Level level, Reading reading,
			Set<String> unsaved, Set<String> outside, Set<String> gone) {
		return new FolderListing.Facts(level, Order.BY_NAME, reading,
				Map.of(), null, null, null, List.of(), List.of(), false, null,
				(key, name) -> null, true, Set.of(""), unsaved, outside, gone);
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

	// README links before its first heading follow the Default shader
	// under a heading of their own, never inside its section (from a
	// review's probe).
	@Test
	public void untitledReadmeLinksMustNotAppearUnderDefaultShader() {
		List<Listing.Section> sections = FolderListing.sections(
				new FolderListing.Facts(ROOT, Order.BY_NAME,
						Reading.started().listed(entries("main.frag",
								"b.frag")), Map.of(), null, null, "main.frag",
						Readme.sections("- [B](b.frag)", Set.of("b.frag")),
						List.of(), false, null, (key, name) -> null, true,
						Set.of(""), Set.of(), Set.of(), Set.of()), WORDS);
		assertEquals("Default shader", sections.get(0).heading());
		assertEquals(List.of("main.frag"), names(sections.get(0)));
		assertEquals("Other files", sections.get(1).heading());
		assertEquals("b.frag", sections.get(1).rows().get(0).name());
	}

	// A manifest's entry in a folder below is the default shader too,
	// named by its path from the root (from a review's probe).
	@Test
	public void aNestedManifestEntryIsAlsoADefaultShader() {
		List<Listing.Section> sections = FolderListing.sections(
				new FolderListing.Facts(ROOT, Order.BY_NAME,
						Reading.started().listed(List.of(
								new Reading.Entry("passes", true))),
						Map.of(), null, null, "passes/main.frag", List.of(),
						List.of(), false, null, (key, name) -> null, true,
						Set.of(""), Set.of(), Set.of(), Set.of()), WORDS);
		assertEquals("Default shader", sections.get(0).heading());
		Row row = sections.get(0).rows().get(0);
		assertEquals("passes/main.frag", row.key());
		assertEquals("passes/main.frag", row.name());
		assertEquals("Tap to run it", row.detail());
	}
}

package de.markusfisch.android.shadereditor.project;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import de.markusfisch.android.shadereditor.project.LibraryArchive.Limits;
import de.markusfisch.android.shadereditor.project.LibraryArchive.Refused;

/**
 * An archive comes from anywhere. Each test is one way it can be
 * wrong, built by hand; a refused archive is refused whole.
 */
public class LibraryImportTest {
	@Rule
	public final TemporaryFolder temp = new TemporaryFolder();

	// The files are staged under numbers, with their bytes and paths,
	// names in either Unicode form as NFC; the text file is read.
	@Test
	public void stagesTheFilesOfAnArchive() throws Exception {
		LibraryImport.Archive archive = read(new Zips()
				.add(".shadereditor/library.txt", "format\t1\n" +
						"later\tfact\ntemplate\tPlasma.frag\n" +
						"quality\t0.5\tInk/main.frag\n")
				.add("Plasma.frag", "plasma")
				.add("Ink/main.frag", "ink")
				.add("Gro\u0308\u00dfe.frag", "size")
				.end());

		assertEquals(List.of("Plasma.frag", "Ink/main.frag",
				"Gr\u00f6\u00dfe.frag"), paths(archive));
		assertEquals("0", archive.files().get(0).file().getName());
		assertEquals("ink", new String(Files.readAllBytes(
				archive.files().get(1).file().toPath()),
				StandardCharsets.UTF_8));
		assertTrue(archive.files().get(0).modified() > 0);
		assertEquals("Plasma.frag", archive.facts().template());
		assertEquals(.5f, archive.facts().qualities().get("Ink/main.frag"),
				0f);
		assertEquals(0, archive.hidden());
	}

	@Test
	public void refusesANameThatIsNotInsideALibrary() {
		for (String name : new String[]{"../evil.frag", "a/../../evil.frag",
				"a/../b.frag", "/etc/evil.frag", "a//b.frag", "./a.frag",
				"a/./b.frag", " ", ""}) {
			assertEquals(name, "\"" + name + "\" is not a name inside a " +
					"library", refusal(new Zips().add("ok.frag", "ok")
					.add(name, "evil").end()));
		}
	}

	@Test
	public void refusesACharacterNoFolderHolds() {
		for (String name : new String[]{"..\\evil.frag", "a\\b.frag",
				"C:evil.frag", "C:\\evil.frag", "a\u0000.frag", "a\nb.frag",
				"what?.frag", "a\"b.frag", "a*b.frag", "a|b/c.frag"}) {
			assertTrue(name, refusal(new Zips().add(name, "evil").end())
					.endsWith(" has a character no folder holds in a name"));
		}
	}

	@Test
	public void refusesANameLongerThanAPhoneHolds() {
		String part = "x".repeat(250);
		assertTrue(read(new Zips().add(part + ".frag", "ok").end()) != null);
		// Counted in bytes, not characters.
		assertTrue(refusal(new Zips().add("\u00e4".repeat(126) + ".frag",
				"long").end()).endsWith(" is longer than a name may be"));
		assertTrue(refusal(new Zips().add("a/" + part + "123456/b.frag",
				"long").end()).endsWith(" is longer than a name may be"));
	}

	// A dot name is no file of a library: skipped, counted, never
	// staged, and what a Mac adds to an archive makes no folder.
	@Test
	public void skipsDotNames() throws Exception {
		LibraryImport.Archive archive = read(new Zips()
				.add("Shaders/", "")
				.add("Shaders/a.frag", "a")
				.add("__MACOSX/", "")
				.add("__MACOSX/Shaders/", "")
				.add("__MACOSX/Shaders/._a.frag", "junk")
				.add(".DS_Store", "junk")
				.add(".git/config", "junk")
				.add(".shadereditor/later", "junk")
				.add("ideas/", "")
				.end());

		assertEquals(List.of("Shaders/a.frag"), paths(archive));
		assertEquals(4, archive.hidden());
		assertEquals(List.of(path("ideas")), archive.folders());
		assertEquals(1, temp.getRoot().listFiles()[0].list().length);
	}

	@Test
	public void refusesTwoEntriesAPhoneCannotKeepApart() {
		assertEquals("\"a.frag\" is in it twice", refusal(new Zips()
				.add("a.frag", "1").add("a.frag", "2").end()));
		assertEquals("\"Plasma.frag\" and \"plasma.FRAG\" differ only by " +
				"case, which a phone cannot keep apart", refusal(new Zips()
				.add("Plasma.frag", "1").add("plasma.FRAG", "2").end()));
		assertEquals("\"Ink\" and \"ink\" differ only by case, which a " +
				"phone cannot keep apart", refusal(new Zips()
				.add("Ink/main.frag", "1").add("ink/paper.frag", "2").end()));
		// One name in the two forms of Unicode.
		assertEquals("\"o\u0308.frag\" is in it twice", refusal(new Zips()
				.add("\u00f6.frag", "1").add("o\u0308.frag", "2").end()));
	}

	@Test
	public void refusesANameThatIsAFileAndAFolder() {
		assertEquals("\"a\" is a file and a folder", refusal(new Zips()
				.add("a", "file").add("a/b.frag", "below").end()));
		assertEquals("\"a\" is a file and a folder", refusal(new Zips()
				.add("a/b.frag", "below").add("a", "file").end()));
		assertEquals("\"a\" is a file and a folder", refusal(new Zips()
				.add("a", "file").add("a/", "").end()));
		assertEquals("\"A\" is a file and a folder", refusal(new Zips()
				.add("a/", "").add("A", "file").end()));
	}

	// A link is never made: the entry is an ordinary staged file
	// holding the link's text, and nothing is read through it.
	@Test
	public void aLinkIsAnOrdinaryFile() throws Exception {
		LibraryImport.Archive archive = read(new Zips()
				.add("passwd.frag".getBytes(StandardCharsets.UTF_8),
						"/etc/passwd".getBytes(StandardCharsets.UTF_8), 0x800,
						Zips.LINK, -1)
				.end());

		File staged = archive.files().get(0).file();
		assertTrue(Files.isRegularFile(staged.toPath(),
				java.nio.file.LinkOption.NOFOLLOW_LINKS));
		assertEquals("/etc/passwd", new String(Files.readAllBytes(
				staged.toPath()), StandardCharsets.UTF_8));
	}

	// The sizes are counted as the bytes come: an archive of a few
	// bytes that unpacks to very many stops at the limit.
	@Test
	public void stopsAtTheLimits() throws Exception {
		ByteArrayOutputStream bomb = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(bomb)) {
			zip.putNextEntry(new ZipEntry("bomb.frag"));
			byte[] zeros = new byte[1 << 16];
			for (int i = 0; i < 256; ++i) {
				zip.write(zeros);
			}
		}
		assertTrue(bomb.size() < 1 << 16);
		assertEquals("it holds more than 1 MiB", refusal(bomb.toByteArray(),
				new Limits(10, 1 << 20)));
		long staged = 0;
		for (File file : temp.getRoot().listFiles()[0].listFiles()) {
			staged += file.length();
		}
		assertTrue(staged <= 1 << 20);

		// A skipped entry counts as well, and so does every entry.
		assertEquals("it holds more than 0 MiB", refusal(new Zips()
				.add(".hidden", "0123456789").end(), new Limits(10, 9)));
		assertEquals("it holds more than 2 files", refusal(new Zips()
				.add("a.frag", "a").add("b.frag", "b").add("c.frag", "c")
				.end(), new Limits(2, 100)));
	}

	@Test
	public void refusesAnArchiveThatIsCutOffOrDamaged() throws Exception {
		byte[] whole = new Zips().add("a.frag", "aaaaaaaaaaaa")
				.add("b.frag", "bbbbbbbbbbbb").end();
		// In the middle of an entry.
		assertTrue(refusal(Arrays.copyOf(whole, 40)).startsWith(
				"it cannot be read as a zip file"));
		// Between two entries, where a stream of entries just ends.
		assertEquals("it is cut off or damaged: its end does not list " +
				"the 2 entries that were read", refusal(new Zips()
				.add("a.frag", "a").add("b.frag", "b").cut()));
		// The end lists other entries than there are.
		assertEquals("it is cut off or damaged: its end does not list " +
				"the 2 entries that were read", refusal(new Zips()
				.add("a.frag", "a").add("b.frag", "b").end(1)));
		// A checksum that does not match the bytes.
		assertTrue(refusal(new Zips().add("a.frag".getBytes(
				StandardCharsets.UTF_8), new byte[]{1, 2, 3}, 0x800, 0, 7)
				.end()).startsWith("it cannot be read as a zip file"));
		// An encrypted entry.
		assertTrue(refusal(new Zips().add("a.frag".getBytes(
				StandardCharsets.UTF_8), new byte[]{1, 2, 3}, 0x801, 0, -1)
				.end()).startsWith("it cannot be read as a zip file"));
		assertEquals("it is not a zip file, or it is empty", refusal(
				"SQLite format 3\u0000 and so on".getBytes(
						StandardCharsets.UTF_8)));
		assertEquals("it is not a zip file, or it is empty",
				refusal(new Zips().end()));
		assertEquals("it holds no files", refusal(new Zips()
				.add(".DS_Store", "junk").end()));
	}

	@Test
	public void refusesAnArchiveOfANewerVersion() {
		assertEquals("it was made by a newer version of ShaderEditor " +
				"(format 2)", refusal(new Zips()
				.add(".shadereditor/library.txt", "format\t2\n")
				.add("a.frag", "a").end()));
	}

	// Some thousand entries are read one after the other.
	@Test
	public void readsSomeThousandEntries() throws Exception {
		Zips zips = new Zips();
		for (int i = 0; i < 3000; ++i) {
			zips.add("f" + (i % 30) + "/s" + i + ".frag", "void main(){}");
		}
		assertEquals(3000, read(zips.end()).files().size());
	}

	private LibraryImport.Archive read(byte[] archive) {
		try {
			return LibraryImport.read(new ByteArrayInputStream(archive),
					temp.newFolder(), Limits.DEFAULT);
		} catch (Refused | IOException e) {
			throw new AssertionError(e.getMessage(), e);
		}
	}

	private String refusal(byte[] archive) {
		return refusal(archive, Limits.DEFAULT);
	}

	private String refusal(byte[] archive, Limits limits) {
		for (File old : temp.getRoot().listFiles()) {
			delete(old);
		}
		return assertThrows(Refused.class, () -> LibraryImport.read(
				new ByteArrayInputStream(archive), temp.newFolder(),
				limits)).getMessage();
	}

	private static void delete(File file) {
		File[] files = file.listFiles();
		for (File inside : files != null ? files : new File[0]) {
			delete(inside);
		}
		assertTrue(file.delete());
	}

	private static List<String> paths(LibraryImport.Archive archive) {
		List<String> paths = new ArrayList<>();
		for (LibraryImport.Staged staged : archive.files()) {
			paths.add(staged.path().toString());
		}
		return paths;
	}

	private static ProjectPath path(String path) {
		return ProjectPath.parse(path).value();
	}
}

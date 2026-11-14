package de.markusfisch.android.shadereditor.project;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import de.markusfisch.android.shadereditor.project.legacy.LegacyShaderConverter.LibraryTexture;

public class WallpaperSnapshotsTest {
	private static final String MAIN = "void main() {\n" +
			"\tgl_FragColor = vec4(1.0);\n}\n";

	@Rule
	public final TemporaryFolder temp = new TemporaryFolder();

	// The trace that must not break: a wallpaper is set, then the
	// shaders move.
	@Test
	public void aWallpaperSetBeforeTheMoveStillShowsItsDatabaseShader()
			throws IOException {
		MoveDatabase database = database();
		List<MoveDatabase.Row> before = new ArrayList<>(database.rows);
		long wallpaper = 1;
		Library library = library();
		library.move(database, "Untitled", new LibraryMove.Chosen(0, 0), null, null);

		// The service reads what it read: no snapshot is installed,
		// and the row it shows is as it was.
		WallpaperSnapshots snapshots = snapshots();
		assertNull(snapshots.current());
		assertEquals(before, database.rows);
		// A list can mark the file that shader became.
		assertEquals(path("Plasma.frag"), library.wallpaperFile(
				snapshots.current(), wallpaper));
		assertNull(library.wallpaperFile(snapshots.current(), 99));

		// Editing the file does not reach the wallpaper.
		write(new File(library.folder(), "Plasma.frag"), "// edited\n");
		assertNull(snapshots().current());
		assertEquals(before, database.rows);
	}

	@Test
	public void aWallpaperSetFromAFileShowsItsOwnCopy() throws IOException {
		Library library = library();
		library.move(database(), "Untitled", new LibraryMove.Chosen(0, 0), null, null);
		WallpaperSnapshots snapshots = snapshots();
		assertNull(snapshots.install(prepare(library, "Plasma.frag"),
				path("Plasma.frag"), .5f, path("Plasma.frag")));

		WallpaperSnapshots.Snapshot snapshot = snapshots().current();
		assertEquals(path("Plasma.frag"), snapshot.entry());
		assertEquals(.5f, snapshot.quality(), 0f);
		assertEquals(path("Plasma.frag"), library.wallpaperFile(snapshot,
				2));
		// Only what the shader is made of was copied.
		assertTrue(new File(snapshot.folder(), "textures/noise.png")
				.isFile());
		assertFalse(new File(snapshot.folder(), "Clouds.frag").exists());
		// It draws without the library: its files are gone or changed.
		assertTrue(new File(library.folder(), "textures/noise.png")
				.delete());
		write(new File(library.folder(), "Plasma.frag"), "broken");
		Preparation shown = Preparation.prepare(snapshot.files(),
				snapshot.entry(), ProjectLimits.DEFAULT, BuiltinNames.ALL);
		assertNotNull(shown.diagnostics().toString(), shown.plan());
	}

	@Test
	public void theSnapshotBeforeStaysUntilTheNewOneIsWhole()
			throws IOException {
		Library library = library();
		library.move(database(), "Untitled", new LibraryMove.Chosen(0, 0), null, null);
		WallpaperSnapshots snapshots = snapshots();
		snapshots.install(prepare(library, "Plasma.frag"),
				path("Plasma.frag"), 1f, path("Plasma.frag"));
		File first = snapshots.current().folder();

		// A shader with errors is not installed, and nothing changes.
		write(new File(library.folder(), "Broken.frag"),
				"#include \"missing.glsl\"\n" + MAIN);
		assertEquals("the shader has errors; fix them first",
				snapshots.install(prepare(library, "Broken.frag"),
						path("Broken.frag"), 1f, path("Broken.frag")));
		assertEquals(first, snapshots().current().folder());
		// A snapshot that was written and never published is not shown.
		write(new File(temp.getRoot(), "files/wallpaper/7/Clouds.frag"),
				MAIN);
		assertEquals(path("Plasma.frag"), snapshots().current().entry());

		// The next one takes its place. What was never installed
		// goes; the one that was shown until now stays for its reader.
		assertNull(snapshots.install(prepare(library, "Clouds.frag"),
				path("Clouds.frag"), 1f, null));
		WallpaperSnapshots.Snapshot second = snapshots().current();
		assertEquals(path("Clouds.frag"), second.entry());
		assertNull(second.origin());
		assertNull(library.wallpaperFile(second, 1));
		assertTrue(first.exists());
		assertFalse(new File(temp.getRoot(), "files/wallpaper/7").exists());
		assertTrue(new File(second.folder(), "Clouds.frag").isFile());
		// The install after that removes it.
		assertNull(snapshots.install(prepare(library, "Clouds.frag"),
				path("Clouds.frag"), 1f, null));
		assertFalse(first.exists());
		assertTrue(second.folder().exists());
	}

	// The service reads current, then the files, on its own thread,
	// while the queue installs the next snapshot.
	@Test
	public void aSnapshotItsReaderWasToldOfSurvivesTheNextInstall()
			throws IOException {
		Library library = library();
		library.move(database(), "Untitled", new LibraryMove.Chosen(0, 0),
				null, null);
		WallpaperSnapshots snapshots = snapshots();
		Preparation build = prepare(library, "Plasma.frag");
		assertNull(snapshots.install(build, path("Plasma.frag"), 1f,
				path("Plasma.frag")));
		WallpaperSnapshots.Snapshot told = snapshots.current();
		assertNull(snapshots.install(build, path("Plasma.frag"), 1f,
				path("Plasma.frag")));
		assertNotNull(Preparation.prepare(told.files(), told.entry(),
				ProjectLimits.DEFAULT, BuiltinNames.ALL).plan());
		// A reader two installs behind finds nothing, reads current
		// again, and finds that one whole.
		assertNull(snapshots.install(build, path("Plasma.frag"), 1f,
				path("Plasma.frag")));
		assertNull(Preparation.prepare(told.files(), told.entry(),
				ProjectLimits.DEFAULT, BuiltinNames.ALL).plan());
		WallpaperSnapshots.Snapshot again = snapshots.current();
		assertNotNull(Preparation.prepare(again.files(), again.entry(),
				ProjectLimits.DEFAULT, BuiltinNames.ALL).plan());
	}

	@Test
	public void aReferenceNothingWroteIsNoSnapshot() throws IOException {
		write(new File(temp.getRoot(), "files/wallpaper/current"),
				"1\nfast\nmain.frag\n\n");
		assertNull(snapshots().current());
		write(new File(temp.getRoot(), "files/wallpaper/current"),
				"1\n1.0\nmain.frag\n\n");
		// Its folder is not there.
		assertNull(snapshots().current());
		write(new File(temp.getRoot(), "files/wallpaper/current"), "1\n");
		assertNull(snapshots().current());
	}

	private Library library() {
		File userFiles = new File(temp.getRoot(), "user-files");
		File files = new File(temp.getRoot(), "files");
		userFiles.mkdirs();
		files.mkdirs();
		return new Library(userFiles, files);
	}

	private WallpaperSnapshots snapshots() {
		return WallpaperSnapshots.of(new File(temp.getRoot(), "files"));
	}

	private static Preparation prepare(Library library, String entry) {
		return Preparation.prepare(library.files(), path(entry),
				ProjectLimits.DEFAULT, BuiltinNames.ALL);
	}

	private static MoveDatabase database() {
		MoveDatabase database = new MoveDatabase();
		database.textures.put("noise", new LibraryTexture(false, 8, 8));
		database.images.put("noise", Pictures.png(1));
		database.add(1, "Plasma", "uniform sampler2D noise;\n" + MAIN);
		database.add(2, "Clouds", MAIN);
		return database;
	}

	private static void write(File file, String text) throws IOException {
		file.getParentFile().mkdirs();
		try (FileOutputStream out = new FileOutputStream(file)) {
			out.write(text.getBytes(StandardCharsets.UTF_8));
		}
	}

	private static ProjectPath path(String path) {
		return ProjectPath.parse(path).value();
	}
}

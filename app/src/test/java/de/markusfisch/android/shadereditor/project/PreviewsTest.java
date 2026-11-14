package de.markusfisch.android.shadereditor.project;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

public class PreviewsTest {
	@Rule
	public final TemporaryFolder temp = new TemporaryFolder();

	@Test
	public void keepsAPictureWithTheRevisionItShows() {
		Previews previews = previews();
		assertNull(previews.get(path("Plasma.frag")));
		assertTrue(previews.put(path("Plasma.frag"), "sha256:a",
				Pictures.png(1)));
		Previews.Picture picture = previews().get(path("Plasma.frag"));
		assertArrayEquals(Pictures.png(1), picture.png());
		assertEquals("sha256:a", picture.revision());
		// A newer picture takes its place.
		assertTrue(previews.put(path("Plasma.frag"), "sha256:b",
				Pictures.png(2)));
		picture = previews().get(path("Plasma.frag"));
		assertArrayEquals(Pictures.png(2), picture.png());
		assertEquals("sha256:b", picture.revision());
	}

	@Test
	public void aPictureIsOfItsWholePathOnly() {
		Previews previews = previews();
		previews.put(path("a/Plasma.frag"), "r", Pictures.png(1));
		assertNull(previews.get(path("Plasma.frag")));
		assertNull(previews.get(path("a/plasma.frag")));
		assertNull(previews.get(path("a")));
		// No path is a file name here, whatever it holds.
		assertTrue(previews.put(path("a b/ä: ö.frag"), "r", Pictures.png(3)));
		assertArrayEquals(Pictures.png(3),
				previews.get(path("a b/ä: ö.frag")).png());
		for (File file : new File(temp.getRoot(), "previews").listFiles()) {
			assertTrue(file.getName(),
					file.getName().matches("[0-9a-f]{64}\\.preview"));
		}
	}

	@Test
	public void followsARenamedFileAndForgetsADeletedOne() {
		Previews previews = previews();
		previews.put(path("Old.frag"), "r", Pictures.png(1));
		assertTrue(previews.follow(new Journal.Move(path("Old.frag"), path("New.frag"))));
		assertNull(previews.get(path("Old.frag")));
		assertArrayEquals(Pictures.png(1),
				previews.get(path("New.frag")).png());
		// Nothing to follow is no failure.
		assertTrue(previews.follow(new Journal.Move(path("None.frag"),
				path("X.frag"))));
		assertTrue(previews.follow(new Journal.Move(path("New.frag"), null)));
		assertNull(previews.get(path("New.frag")));
		assertTrue(previews.follow(new Journal.Move(path("New.frag"), null)));
	}

	// A hash of a path alone could not find the files of a folder.
	@Test
	public void aRenamedFolderTakesThePicturesOfItsFilesAlong() {
		Previews previews = previews();
		previews.put(path("a/one.frag"), "r1", Pictures.png(1));
		previews.put(path("a/deep/two.frag"), "r2", Pictures.png(2));
		previews.put(path("ab/three.frag"), "r3", Pictures.png(3));
		assertTrue(previews.follow(new Journal.Move(path("a"), path("b"))));

		// The old path can be another file now, and shows nothing.
		assertNull(previews.get(path("a/one.frag")));
		assertNull(previews.get(path("a/deep/two.frag")));
		assertArrayEquals(Pictures.png(1),
				previews.get(path("b/one.frag")).png());
		assertEquals("r2", previews.get(path("b/deep/two.frag")).revision());
		// A name that only starts alike is another folder.
		assertArrayEquals(Pictures.png(3),
				previews.get(path("ab/three.frag")).png());
		assertEquals(3, new File(temp.getRoot(), "previews").list().length);
	}

	@Test
	public void aDeletedFolderTakesThePicturesOfItsFilesWithIt() {
		Previews previews = previews();
		previews.put(path("a/one.frag"), "r1", Pictures.png(1));
		previews.put(path("a/deep/two.frag"), "r2", Pictures.png(2));
		previews.put(path("keep.frag"), "r3", Pictures.png(3));
		assertTrue(previews.follow(new Journal.Move(path("a"), null)));
		assertNull(previews.get(path("a/one.frag")));
		assertNull(previews.get(path("a/deep/two.frag")));
		assertArrayEquals(Pictures.png(3),
				previews.get(path("keep.frag")).png());
		assertEquals(1, new File(temp.getRoot(), "previews").list().length);
	}

	@Test
	public void aFileNothingWroteIsNoPicture() throws IOException {
		Previews previews = previews();
		previews.put(path("Plasma.frag"), "r", Pictures.png(1));
		File file = new File(temp.getRoot(), "previews").listFiles()[0];
		try (FileOutputStream out = new FileOutputStream(file)) {
			out.write(new byte[]{1, 2, 3});
		}
		assertNull(previews.get(path("Plasma.frag")));
	}

	private Previews previews() {
		return new Previews(new File(temp.getRoot(), "previews"));
	}

	private static ProjectPath path(String path) {
		return ProjectPath.parse(path).value();
	}
}

package de.markusfisch.android.shadereditor.project;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class MoveNoteTest {
	@Rule
	public final TemporaryFolder temp = new TemporaryFolder();

	@Test
	public void keepsWhatWasNotedForTheNextRun() {
		MoveNote note = note();
		assertNull(note.name(7));
		assertFalse(note.selected());
		assertTrue(note.noteName(7, "Plasma.frag"));
		assertTrue(note.noteName(8, "a b 2.frag"));
		assertTrue(note.noteMoved(7, "Plasma.frag"));
		assertTrue(note.noteFile("sha256:ab", path("textures/a b.png")));
		assertTrue(note.noteSelected(7));

		MoveNote again = note();
		assertFalse(again.unreadable());
		assertEquals("Plasma.frag", again.name(7));
		assertEquals(path("Plasma.frag"), again.moved(7));
		assertEquals("a b 2.frag", again.name(8));
		assertNull(again.moved(8));
		assertEquals(Set.of("Plasma.frag", "a b 2.frag"),
				new HashSet<>(again.names()));
		assertEquals("sha256:ab", again.file(path("textures/a b.png")));
		assertNull(again.file(path("textures/other.png")));
		assertTrue(again.selected());
		// A name noted later for a shader is the one it has.
		assertTrue(again.noteName(8, "a b 3.frag"));
		assertEquals("a b 3.frag", note().name(8));
	}

	// The tail of a write that did not finish must never become a
	// line because the next line was put behind it.
	@Test
	public void aLineThatDidNotFinishNeverBecomesOne() throws IOException {
		write("name\t1\tAlpha.frag\nmoved\t1\tAlpha.fr");
		MoveNote resumed = note();
		assertNull(resumed.moved(1));
		assertTrue(resumed.noteName(2, "Beta.frag"));

		MoveNote again = note();
		assertNull(again.moved(1));
		assertEquals("Alpha.frag", again.name(1));
		assertEquals("Beta.frag", again.name(2));
		assertEquals("name\t1\tAlpha.frag\nname\t2\tBeta.frag\n", text());
	}

	// Cut the file at every byte, as a death in a write leaves it;
	// append, as the next run does; cut that at every byte again, as
	// a death in the recovery leaves it. Only lines that were written
	// whole are ever read.
	@Test
	public void cutAtAnyByteOnlyWholeLinesAreRead() throws IOException {
		String whole = "name\t1\tAlpha.frag\nfile\tsha256:ab\ttextures/n.png\n" +
				"moved\t1\tAlpha.frag\nname\t2\tBär.frag\nselected\t1\t-\n";
		byte[] bytes = whole.getBytes(StandardCharsets.UTF_8);
		for (int cut = 0; cut <= bytes.length; ++cut) {
			byte[] left = Arrays.copyOf(bytes, cut);
			write(left);
			String lines = wholeLines(left);
			assertEquals("cut at " + cut, state(lines), state(note()));

			assertTrue(note().noteName(9, "Next.frag"));
			String after = lines + "name\t9\tNext.frag\n";
			assertEquals("cut at " + cut, after, text());

			byte[] appended = after.getBytes(StandardCharsets.UTF_8);
			for (int again = cut; again <= appended.length; ++again) {
				byte[] rest = Arrays.copyOf(appended, again);
				write(rest);
				assertEquals("cut at " + cut + " and " + again,
						state(wholeLines(rest)), state(note()));
			}
		}
	}

	@Test
	public void linesNothingWroteSayNothing() throws IOException {
		write("moved\tseven\tA.frag\nnonsense\n\nname\t3\n" +
				"name\t4\tB.frag\n");
		MoveNote note = note();
		assertNull(note.name(3));
		assertEquals("B.frag", note.name(4));
	}

	// Unreadable is not empty: nothing is appended to a history that
	// was not read.
	@Test
	public void aNoteThatCannotBeReadTakesNoLine() throws IOException {
		assertTrue(note().noteMoved(1, "Alpha.frag"));
		File file = file();
		assertTrue(file.setReadable(false, false));
		try {
			assertFalse(file.canRead());
			MoveNote note = note();
			assertTrue(note.unreadable());
			assertNull(note.moved(1));
			assertFalse(note.noteName(2, "Beta.frag"));
			assertFalse(note.load());
		} finally {
			file.setReadable(true);
		}
		assertEquals("moved\t1\tAlpha.frag\n", text());
		MoveNote note = note();
		assertFalse(note.unreadable());
		assertEquals(path("Alpha.frag"), note.moved(1));
	}

	// What a note of exactly these whole lines says.
	private String state(String lines) throws IOException {
		File other = new File(temp.getRoot(), "other/moved");
		other.getParentFile().mkdirs();
		try (FileOutputStream out = new FileOutputStream(other)) {
			out.write(lines.getBytes(StandardCharsets.UTF_8));
		}
		return state(new MoveNote(other));
	}

	private static String state(MoveNote note) {
		StringBuilder sb = new StringBuilder();
		for (long id = 1; id <= 9; ++id) {
			sb.append(id).append(' ').append(note.name(id)).append(' ')
					.append(note.moved(id)).append('\n');
		}
		return sb.append(note.file(path("textures/n.png"))).append(' ')
				.append(note.selected()).toString();
	}

	private static String wholeLines(byte[] bytes) {
		String text = new String(bytes, StandardCharsets.ISO_8859_1);
		int end = text.lastIndexOf('\n') + 1;
		return new String(Arrays.copyOf(bytes, end), StandardCharsets.UTF_8);
	}

	private MoveNote note() {
		return new MoveNote(file());
	}

	private File file() {
		return new File(temp.getRoot(), "library/moved");
	}

	private String text() throws IOException {
		return new String(Files.readAllBytes(file().toPath()),
				StandardCharsets.UTF_8);
	}

	private void write(String text) throws IOException {
		write(text.getBytes(StandardCharsets.UTF_8));
	}

	private void write(byte[] bytes) throws IOException {
		file().getParentFile().mkdirs();
		try (FileOutputStream out = new FileOutputStream(file())) {
			out.write(bytes);
		}
	}

	private static ProjectPath path(String path) {
		return ProjectPath.parse(path).value();
	}
}

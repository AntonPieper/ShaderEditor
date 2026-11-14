package de.markusfisch.android.shadereditor.project;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class FileKindTest {
	private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a,
			0x1a, 0x0a, 0, 0, 0, 13};
	private static final byte[] JPEG = {(byte) 0xff, (byte) 0xd8, (byte) 0xff,
			(byte) 0xe0, 0, 16};

	@Rule
	public final TemporaryFolder temp = new TemporaryFolder();

	@Test
	public void kindComesFromTheBytes() {
		assertEquals(FileKind.PICTURE, FileKind.of(PNG));
		assertEquals(FileKind.PICTURE, FileKind.of(JPEG));
		assertEquals("PNG", FileKind.picture(PNG));
		assertEquals("JPEG", FileKind.picture(JPEG));
		assertEquals(FileKind.TEXT, FileKind.of(bytes("void main() {}")));
		assertEquals(FileKind.TEXT, FileKind.of(new byte[0]));
		assertEquals(FileKind.TEXT, FileKind.of(bytes("\u00e9 \ud83d\ude00")));
		// Neither a signature nor UTF-8.
		assertEquals(FileKind.OTHER, FileKind.of(new byte[]{(byte) 0xff,
				(byte) 0xfe, 0, 1}));
		// Half a signature is not one.
		assertNull(FileKind.picture(new byte[]{(byte) 0x89, 'P', 'N'}));
		assertNull(FileKind.picture(new byte[]{(byte) 0xff, (byte) 0xd8}));
	}

	@Test
	public void theFirstBytesTellAPicture() {
		byte[] head = java.util.Arrays.copyOf(PNG, FileKind.HEAD);
		assertEquals("PNG", FileKind.picture(head));
		assertEquals("JPEG", FileKind.picture(java.util.Arrays.copyOf(JPEG,
				FileKind.HEAD)));
		assertNull(FileKind.picture(java.util.Arrays.copyOf(
				bytes("void main() {}"), FileKind.HEAD)));
	}

	@Test
	public void aHintLooksAtNameAndTypeOnlyAndMayBeWrong() {
		assertEquals(FileKind.PICTURE, FileKind.hint("noise.PNG", null));
		assertEquals(FileKind.PICTURE, FileKind.hint("a.jpeg", null));
		assertEquals(FileKind.PICTURE, FileKind.hint("download", "image/webp"));
		assertEquals(FileKind.TEXT, FileKind.hint("main.frag", null));
		assertEquals(FileKind.TEXT, FileKind.hint("notes", "text/plain"));
		assertEquals(FileKind.TEXT, FileKind.hint("data.bin",
				"application/octet-stream"));
		// The name says picture; the bytes are what it is.
		assertEquals(FileKind.PICTURE, FileKind.hint("shader.png", null));
		assertEquals(FileKind.TEXT, FileKind.of(bytes("void main() {}")));
	}

	@Test
	public void aStoreReadsTheFirstBytesAlone() throws IOException {
		File root = temp.newFolder("project");
		byte[] large = new byte[100_000];
		System.arraycopy(PNG, 0, large, 0, PNG.length);
		Files.write(new File(root, "large.frag").toPath(), large);
		Files.write(new File(root, "tiny").toPath(), new byte[]{1, 2});
		DirectoryProjectFiles files = new DirectoryProjectFiles(root);

		byte[] head = files.head(path("large.frag"), FileKind.HEAD);

		assertEquals(FileKind.HEAD, head.length);
		assertEquals("PNG", FileKind.picture(head));
		assertArrayEquals(new byte[]{1, 2}, files.head(path("tiny"), 8));
		assertNull(files.head(path("absent"), 8));
		// A store that cannot read part of a file reads it.
		MemoryProjectFiles memory = new MemoryProjectFiles();
		memory.put("a.frag", large);
		assertEquals("PNG", FileKind.picture(memory.head(path("a.frag"),
				FileKind.HEAD)));
		assertNull(memory.head(path("absent"), 8));
	}

	private static byte[] bytes(String text) {
		return text.getBytes(StandardCharsets.UTF_8);
	}

	private static ProjectPath path(String path) {
		return ProjectPath.parse(path).value();
	}
}

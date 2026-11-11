package de.markusfisch.android.shadereditor.project.legacy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

public class StoredFilesTest {
	@Test
	public void textsWithTheSameStringHashGetDifferentRevisions() {
		String a = "float Aa=0.0; float BB=1.0; " +
				"void main(){gl_FragColor=vec4(Aa);}";
		String b = "float Aa=0.0; float BB=1.0; " +
				"void main(){gl_FragColor=vec4(BB);}";
		assertEquals(a.hashCode(), b.hashCode());
		assertNotEquals(StoredFiles.revision(utf8(a)),
				StoredFiles.revision(utf8(b)));
	}

	@Test
	public void revisionChangesWithAnyByte() {
		byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0, 1, 2, 3};
		String revision = StoredFiles.revision(png);
		for (int i = 0; i < png.length; ++i) {
			byte[] changed = png.clone();
			++changed[i];
			assertNotEquals(revision, StoredFiles.revision(changed));
		}
		assertEquals(revision, StoredFiles.revision(png.clone()));
	}

	@Test
	public void revisionIsTheSha256OfTheBytes() {
		assertEquals("sha256:ba7816bf8f01cfea414140de5dae2223" +
						"b00361a396177a9cb410ff61f20015ad",
				StoredFiles.revision(utf8("abc")));
		assertEquals("sha256:e3b0c44298fc1c149afbf4c8996fb924" +
						"27ae41e4649b934ca495991b7852b855",
				StoredFiles.revision(new byte[0]));
	}

	private static byte[] utf8(String text) {
		return text.getBytes(StandardCharsets.UTF_8);
	}
}

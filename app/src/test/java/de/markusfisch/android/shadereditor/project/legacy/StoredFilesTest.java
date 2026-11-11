package de.markusfisch.android.shadereditor.project.legacy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

import de.markusfisch.android.shadereditor.project.ProjectLimits;
import de.markusfisch.android.shadereditor.project.legacy.StoredFiles.Problem;

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

	@Test
	public void imageWithinTheLimitPasses() {
		assertNull(StoredFiles.check("noise", 4096, 4096, false,
				ProjectLimits.DEFAULT.imagePixels()));
		assertNull(StoredFiles.check("sky", 64, 96, true, 64 * 96));
	}

	@Test
	public void oversizedAtlasFailsBeforeItIsDecoded() {
		String reason = StoredFiles.check("sky", 8192, 12288, true,
				ProjectLimits.DEFAULT.imagePixels());
		assertReason(reason, "sky", "16777216");
	}

	@Test
	public void oversizedImageNamesTheLimitWithoutOverflow() {
		assertReason(StoredFiles.check("noise", Integer.MAX_VALUE,
				Integer.MAX_VALUE, false, Integer.MAX_VALUE), "noise",
				String.valueOf(Integer.MAX_VALUE));
		assertReason(StoredFiles.check("sky", Integer.MAX_VALUE,
				Integer.MAX_VALUE, true, Integer.MAX_VALUE), "sky",
				String.valueOf(Integer.MAX_VALUE));
	}

	@Test
	public void stripLargerThanTheAtlasIsCheckedToo() {
		// An odd width cuts faces of ceil(width / 2), so the strip of
		// 6 * 2 * 2 pixels is larger than the 3x6 atlas.
		assertNull(StoredFiles.check("sky", 3, 6, false, 18));
		assertReason(StoredFiles.check("sky", 3, 6, true, 18), "sky",
				"18");
	}

	@Test
	public void zeroAndNegativeLimitsAdmitNothing() {
		assertReason(StoredFiles.check("noise", 1, 1, false, 0), "noise",
				"limit of 0 ");
		assertReason(StoredFiles.check("noise", 1, 1, false, -5), "noise",
				"limit of 0 ");
	}

	@Test
	public void imagesWithoutPixelsOrFacesFail() {
		assertReason(StoredFiles.check("noise", 0, 4, false, 100), "noise",
				"no pixels");
		assertReason(StoredFiles.check("noise", -1, -1, false, 100),
				"noise", "no pixels");
		assertReason(StoredFiles.check("sky", 1, 1, true, 100), "sky",
				"cubemap");
	}

	@Test
	public void everyProblemHasAReasonNamingTheTexture() {
		for (Problem problem : Problem.values()) {
			String reason = StoredFiles.reason(problem, "noise");
			assertTrue(reason, reason.contains("\"noise\""));
		}
		assertTrue(StoredFiles.reason(Problem.NO_MEMORY, "noise")
				.contains("memory"));
	}

	private static void assertReason(String reason, String... parts) {
		assertNotNull(reason);
		for (String part : parts) {
			assertTrue(reason, reason.contains(part));
		}
	}

	private static byte[] utf8(String text) {
		return text.getBytes(StandardCharsets.UTF_8);
	}
}

package de.markusfisch.android.shadereditor.project;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

public class AssetProjectFilesTest {
	private static final File ASSETS = ExamplesTest.folder().getParentFile();
	private static final AssetProjectFiles FILES = new AssetProjectFiles(
			new AssetProjectFiles.Assets() {
				@Override
				public InputStream open(String path) throws IOException {
					return new FileInputStream(new File(ASSETS, path));
				}

				@Override
				public String[] list(String path) {
					String[] names = new File(ASSETS, path).list();
					return names != null ? names : new String[0];
				}
			}, "examples");

	@Test
	public void readsAndListsWhatIsThere() throws IOException {
		ProjectPath tilt = ProjectPath.parse("Tilt.frag").value();
		ProjectFiles.Content content = (ProjectFiles.Content) FILES.read(tilt);
		assertArrayEquals(Files.readAllBytes(
				new File(ExamplesTest.folder(), "Tilt.frag").toPath()),
				content.bytes());
		assertNull(FILES.read(ProjectPath.parse("Absent.frag").value()));
		assertNull(FILES.list(tilt));

		Map<String, Boolean> folders = new HashMap<>();
		for (ProjectFiles.Entry entry :
				((ProjectFiles.Entries) FILES.list(null)).entries()) {
			folders.put(entry.name(), entry.folder());
		}
		assertEquals(Boolean.TRUE, folders.get("textures"));
		assertEquals(Boolean.FALSE, folders.get("Tilt.frag"));
	}

	@Test
	public void changesNothing() {
		ProjectPath tilt = ProjectPath.parse("Tilt.frag").value();
		assertEquals(ProjectFiles.Offers.NONE, FILES.offers(tilt));
		assertTrue(FILES.write(tilt, new byte[]{1}, null) instanceof
				ProjectFiles.Refused);
	}
}

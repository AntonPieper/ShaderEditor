package de.markusfisch.android.shadereditor.project;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ProjectFilesTest {
	private final MemoryProjectFiles files = new MemoryProjectFiles()
			.put("main.glsl", "void main() {}");

	@Test
	public void readOnlyStoreRefusesEveryChange() {
		ProjectPath main = path("main.glsl");
		assertUnsupported(files.write(main, new byte[0],
				files.read(main).revision()));
		assertUnsupported(files.write(path("new.glsl"), new byte[0], null));
		assertUnsupported(files.createFolder(path("passes")));
		assertUnsupported(files.rename(main, path("other.glsl")));
		assertUnsupported(files.delete(main));
		assertEquals("void main() {}", new String(
				((ProjectFiles.Content) files.read(main)).bytes()));
		assertNull(files.read(path("new.glsl")));
	}

	@Test
	public void readOnlyStoreCannotList() {
		assertTrue(files.list(null) instanceof ProjectFiles.Failed);
		ProjectFiles lambda = path -> null;
		assertTrue(lambda.list(path("passes")) instanceof ProjectFiles.Failed);
	}

	private static void assertUnsupported(ProjectFiles.Change change) {
		assertTrue(change instanceof ProjectFiles.Refused);
		assertEquals(ProjectFiles.Refusal.UNSUPPORTED,
				((ProjectFiles.Refused) change).refusal());
	}

	private static ProjectPath path(String path) {
		return ProjectPath.parse(path).value();
	}
}

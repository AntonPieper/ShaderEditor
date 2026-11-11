package de.markusfisch.android.shadereditor.project;

import static org.junit.Assert.assertEquals;

import android.content.Intent;

import org.junit.Test;

public class SafProjectsTest {
	private static final int READ = Intent.FLAG_GRANT_READ_URI_PERMISSION;
	private static final int WRITE = Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
	private static final int PERSISTABLE =
			Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION;

	@Test
	public void keepsOnlyOfferedPersistableGrants() {
		assertEquals(READ | WRITE, SafProjects.persistableModes(
				READ | WRITE | PERSISTABLE));
		assertEquals(READ, SafProjects.persistableModes(READ | PERSISTABLE));
		assertEquals(0, SafProjects.persistableModes(READ | WRITE));
		assertEquals(0, SafProjects.persistableModes(PERSISTABLE));
		assertEquals(READ, SafProjects.persistableModes(READ | PERSISTABLE |
				Intent.FLAG_GRANT_PREFIX_URI_PERMISSION));
	}
}

package de.markusfisch.android.shadereditor.project;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

public class MemoryProjectFilesTest {
	@Test
	public void everyOutsideEditChangesTheRevision() {
		MemoryProjectFiles store = new MemoryProjectFiles();
		ProjectPath path = ProjectPath.parse("main.frag").value();
		Set<String> revisions = new HashSet<>();
		for (String text : new String[]{"A", "B", "C"}) {
			revisions.add(store.put("main.frag", text).read(path).revision());
		}
		assertEquals(3, revisions.size());
	}
}

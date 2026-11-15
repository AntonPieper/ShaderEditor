package de.markusfisch.android.shadereditor.fragment;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import de.markusfisch.android.shadereditor.widget.CodeView;

/** How "Use name" may be shortened in the error row. */
public class FileFixesTest {
	@Test
	public void cutsTheNameButNotUseOrTheExtension() {
		CodeView.Fix fix = FileFixes.use("Use lib/sdf_shapes.glsl",
				"lib/sdf_shapes.glsl");
		assertEquals("Use lib/sdf_shapes.glsl", fix.spoken());
		assertEquals(4, fix.cutStart());
		assertEquals("Use lib/sdf_shapes".length(), fix.cutEnd());
	}

	@Test
	public void aNameWithoutAnExtensionIsCutWhole() {
		CodeView.Fix fix = FileFixes.use("Use noise", "noise");
		assertEquals(4, fix.cutStart());
		assertEquals("Use noise".length(), fix.cutEnd());
		// A dot that starts a name is no extension.
		CodeView.Fix hidden = FileFixes.use("Use .hidden", ".hidden");
		assertEquals("Use .hidden".length(), hidden.cutEnd());
	}
}

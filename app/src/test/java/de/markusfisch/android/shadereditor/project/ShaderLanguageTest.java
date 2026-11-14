package de.markusfisch.android.shadereditor.project;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class ShaderLanguageTest {
	@Test
	public void everyTextFileIsGlslUnlessTheTableSaysOtherwise() {
		for (String name : new String[]{"main.frag", "lib/sdf.glsl",
				"a.fs", "b.FSH", "notes.txt", "README", "noise.png",
				"x.vert"}) {
			assertEquals(name, ShaderLanguage.GLSL, ShaderLanguage.of(name));
		}
	}

	@Test
	public void listsTheNamesShadersUsuallyHave() {
		assertEquals(ShaderLanguage.GLSL, ShaderLanguage.listed("main.frag"));
		assertEquals(ShaderLanguage.GLSL, ShaderLanguage.listed("A.GLSL"));
		assertEquals(ShaderLanguage.GLSL, ShaderLanguage.listed("a.fs"));
		assertEquals(ShaderLanguage.GLSL, ShaderLanguage.listed("a.fsh"));
		assertNull(ShaderLanguage.listed("notes.txt"));
		assertNull(ShaderLanguage.listed("frag"));
		assertEquals(".frag", ShaderLanguage.PASS);
		assertEquals(".glsl", ShaderLanguage.INCLUDE);
	}
}

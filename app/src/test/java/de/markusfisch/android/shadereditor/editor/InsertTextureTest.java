package de.markusfisch.android.shadereditor.editor;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class InsertTextureTest {
	@Test
	public void thePathIsFromTheEditedFilesFolder() {
		assertEquals("textures/noise.png",
				InsertTexture.relative("Aurora.frag", "textures/noise.png"));
		assertEquals("../textures/noise.png",
				InsertTexture.relative("Ink/main.frag", "textures/noise.png"));
		assertEquals("noise.png",
				InsertTexture.relative("Ink/main.frag", "Ink/noise.png"));
		assertEquals("../../a.png",
				InsertTexture.relative("a/b/c.frag", "a.png"));
	}

	@Test
	public void theNameIsUnusedAndFromTheFile() {
		assertEquals("noise", InsertTexture.name("void main() {}",
				"textures/noise.png"));
		assertEquals("noise2", InsertTexture.name(
				"uniform sampler2D noise;", "noise.png"));
		assertEquals("noise3", InsertTexture.name(
				"uniform sampler2D noise; float noise2;", "noise.png"));
		// Part of another word is not a use.
		assertEquals("noise", InsertTexture.name("float noises;",
				"noise.png"));
		assertEquals("paperGrain", InsertTexture.name("", "paper-grain.jpg"));
		assertEquals("image16x16", InsertTexture.name("", "16x16.png"));
		assertEquals("texture2", InsertTexture.name("", "texture.png"));
		assertEquals("grE", InsertTexture.name("", "Grüße.png"));
	}

	@Test
	public void theTwoLinesOfUx4() {
		InsertTexture.Lines lines = InsertTexture.of("uniform vec2 resolution;",
				"Aurora.frag", "textures/noise.png");
		assertEquals("#pragma se input \"textures/noise.png\"\n" +
				"uniform sampler2D noise;", lines.statement());
		assertEquals("noise", lines.name());
	}
}

package de.markusfisch.android.shadereditor.editor;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import de.markusfisch.android.shadereditor.project.ShaderLanguage;

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

	// A sampler named like a GLSL keyword or reserved word does not
	// compile; the name is the next free one.
	@Test
	public void theNameIsNoKeyword() {
		for (String image : new String[]{"highp.png", "lowp.png",
				"mediump.png", "uint.png", "layout.png", "samplerCube.png",
				"ivec2.png", "varying.png", "attribute.png", "sample.png",
				"texture.png", "main.png"}) {
			String stem = image.substring(0, image.indexOf('.'));
			assertEquals(stem + "2", InsertTexture.name("", image));
		}
	}

	// A name that is no identifier is made one.
	@Test
	public void theNameIsAnIdentifier() {
		assertEquals("image3d", InsertTexture.name("", "3d.png"));
		assertEquals("noise1", InsertTexture.name("", "noise(1).png"));
		assertEquals("image", InsertTexture.name("", "___.png"));
		assertEquals("glNoise", InsertTexture.name("", "gl_noise.png"));
	}

	// Offered only for a GLSL file that runs: no JSON, no Markdown, no
	// include, no file of another folder.
	@Test
	public void offeredOnlyForAShaderOfTheFolder() {
		assertEquals(InsertTexture.Offer.OFFERED, InsertTexture.offer(
				"main.frag", ShaderLanguage.GLSL, true));
		assertEquals(InsertTexture.Offer.OFFERED, InsertTexture.offer(
				"main.frag", ShaderLanguage.GLSL, null));
		assertEquals(InsertTexture.Offer.NO_SHADER_HERE,
				InsertTexture.offer(null, null, null));
		assertEquals(InsertTexture.Offer.NOT_A_SHADER, InsertTexture.offer(
				"shader-project.json", ShaderLanguage.JSON, false));
		assertEquals(InsertTexture.Offer.NOT_A_SHADER, InsertTexture.offer(
				"README.md", ShaderLanguage.MARKDOWN, false));
		assertEquals(InsertTexture.Offer.NOT_A_SHADER, InsertTexture.offer(
				"blur.wgsl", ShaderLanguage.UNSUPPORTED, null));
		assertEquals(InsertTexture.Offer.NOT_A_SHADER, InsertTexture.offer(
				"sdf.glsl", ShaderLanguage.GLSL, false));
		assertEquals(InsertTexture.Offer.NOT_A_SHADER, InsertTexture.offer(
				"sdf.glsl", ShaderLanguage.GLSL, null));
		// An include that defines main runs, and may read a texture.
		assertEquals(InsertTexture.Offer.OFFERED, InsertTexture.offer(
				"pass.glsl", ShaderLanguage.GLSL, true));
	}

	// A uniform of that name already there: the next free name.
	@Test
	public void anExistingUniformOfThatNameIsKept() {
		assertEquals("noise2", InsertTexture.of(
				"uniform sampler2D noise;\nvoid main() {}", "main.frag",
				"textures/noise.png").name());
	}
}

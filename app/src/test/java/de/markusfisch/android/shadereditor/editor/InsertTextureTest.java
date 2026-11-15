package de.markusfisch.android.shadereditor.editor;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import de.markusfisch.android.shadereditor.project.ProjectPath;
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
		assertEquals(InsertTexture.Offer.OFFERED, offer("main.frag",
				ShaderLanguage.GLSL, true));
		assertEquals(InsertTexture.Offer.OFFERED, offer("main.frag",
				ShaderLanguage.GLSL, null));
		assertEquals(InsertTexture.Offer.NO_SHADER_HERE,
				InsertTexture.offer(null, path("noise.png")));
		assertEquals(InsertTexture.Offer.NOT_A_SHADER, offer(
				"shader-project.json", ShaderLanguage.JSON, false));
		assertEquals(InsertTexture.Offer.NOT_A_SHADER, offer("README.md",
				ShaderLanguage.MARKDOWN, false));
		assertEquals(InsertTexture.Offer.NOT_A_SHADER, offer("blur.wgsl",
				ShaderLanguage.UNSUPPORTED, null));
		assertEquals(InsertTexture.Offer.NOT_A_SHADER, offer("sdf.glsl",
				ShaderLanguage.GLSL, false));
		assertEquals(InsertTexture.Offer.NOT_A_SHADER, offer("sdf.glsl",
				ShaderLanguage.GLSL, null));
		// An include that defines main runs, and may read a texture.
		assertEquals(InsertTexture.Offer.OFFERED, offer("pass.glsl",
				ShaderLanguage.GLSL, true));
	}

	// A file the running shader includes is never the target, also
	// when it defines main; neither is one that cannot be changed.
	@Test
	public void neverIntoAnIncludeOrAFileThatCannotChange() {
		assertEquals(InsertTexture.Offer.NOT_A_SHADER, InsertTexture.offer(
				new InsertTexture.Target(path("lib/noise.glsl"), "",
						ShaderLanguage.GLSL, true, true, false),
				path("textures/paper.png")));
		assertEquals(InsertTexture.Offer.NOT_A_SHADER, InsertTexture.offer(
				new InsertTexture.Target(path("lib/noise.glsl"), "",
						ShaderLanguage.GLSL, null, true, true),
				path("textures/paper.png")));
		assertEquals(InsertTexture.Offer.READ_ONLY, InsertTexture.offer(
				new InsertTexture.Target(path("Ink/main.frag"), "",
						ShaderLanguage.GLSL, true, false, true),
				path("textures/paper.png")));
	}

	// A shader that reads the image already is not offered it again;
	// a directive in a comment reads nothing.
	@Test
	public void notOfferedWhereTheImageIsReadAlready() {
		String reads = "#pragma se input \"../textures/paper.png\"\n" +
				"uniform sampler2D paper;\nvoid main() {}";
		assertEquals(InsertTexture.Offer.READS_ALREADY, InsertTexture.offer(
				new InsertTexture.Target(path("Ink/main.frag"), reads,
						ShaderLanguage.GLSL, true, false, false),
				path("textures/paper.png")));
		assertEquals(InsertTexture.Offer.READS_ALREADY, InsertTexture.offer(
				new InsertTexture.Target(path("Ink/main.frag"),
						"  #  pragma se input \"../textures/paper.png\" " +
								"filter=nearest\nuniform sampler2D p;",
						ShaderLanguage.GLSL, true, false, false),
				path("textures/paper.png")));
		assertEquals(InsertTexture.Offer.OFFERED, InsertTexture.offer(
				new InsertTexture.Target(path("Ink/main.frag"), reads,
						ShaderLanguage.GLSL, true, false, false),
				path("textures/noise.png")));
		assertEquals(InsertTexture.Offer.OFFERED, InsertTexture.offer(
				new InsertTexture.Target(path("Ink/main.frag"),
						"// #pragma se input \"../textures/paper.png\"\n" +
								"/* #pragma se input " +
								"\"../textures/paper.png\" */",
						ShaderLanguage.GLSL, true, false, false),
				path("textures/paper.png")));
	}

	// A word in a comment or in a path is no use of a name: no noise2
	// where no noise is declared.
	@Test
	public void wordsInCommentsAndPathsAreNoUse() {
		assertEquals("noise", InsertTexture.name(
				"// noise from the old version\n" +
						"/* paper and noise */\n" +
						"#pragma se input \"lib/noise.png\"\n" +
						"uniform sampler2D grain;\n" +
						"#include \"noise.glsl\"\n" +
						"void main() {}", "textures/noise.png"));
		assertEquals("noise2", InsertTexture.name(
				"// a comment\nuniform sampler2D noise; // \"quoted\"",
				"textures/noise.png"));
		assertEquals("noise2", InsertTexture.name(
				"float a = 1.0; /* open\n close */ float noise;",
				"noise.png"));
	}

	// Insert adds to the file shown when it is pressed: not when the
	// editor shows another file than when the sheet opened (an import
	// opened its shader meanwhile), and not when its text now reads the
	// picture already; else the lines for its text now.
	@Test
	public void insertGoesOnlyToTheFileShownWhenPressed() {
		InsertTexture.Target opened = new InsertTexture.Target(
				path("Aurora.frag"), "void main() {}", ShaderLanguage.GLSL,
				true, false, false);
		InsertTexture.Target imported = new InsertTexture.Target(
				path("Clouds/main.frag"), "void main() {}",
				ShaderLanguage.GLSL, true, false, false);
		ProjectPath image = path("textures/noise.png");
		assertEquals(InsertTexture.Offer.OFFERED,
				InsertTexture.atInsert(opened, opened, image));
		assertEquals(InsertTexture.Offer.ANOTHER_FILE,
				InsertTexture.atInsert(opened, imported, image));
		assertEquals(InsertTexture.Offer.ANOTHER_FILE,
				InsertTexture.atInsert(opened, null, image));
		InsertTexture.Target typed = new InsertTexture.Target(
				path("Aurora.frag"), "#pragma se input \"textures/noise.png\"\n" +
				"uniform sampler2D noise;\nvoid main() {}",
				ShaderLanguage.GLSL, true, false, false);
		assertEquals(InsertTexture.Offer.READS_ALREADY,
				InsertTexture.atInsert(opened, typed, image));
	}

	private static InsertTexture.Offer offer(String name,
			ShaderLanguage language, Boolean runs) {
		return InsertTexture.offer(new InsertTexture.Target(path(name), "",
				language, runs, false, false), path("noise.png"));
	}

	private static ProjectPath path(String path) {
		return ProjectPath.parse(path).value();
	}

	// A uniform of that name already there: the next free name.
	@Test
	public void anExistingUniformOfThatNameIsKept() {
		assertEquals("noise2", InsertTexture.of(
				"uniform sampler2D noise;\nvoid main() {}", "main.frag",
				"textures/noise.png").name());
	}
}

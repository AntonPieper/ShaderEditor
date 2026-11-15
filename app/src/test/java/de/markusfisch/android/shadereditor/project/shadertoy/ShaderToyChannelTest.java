package de.markusfisch.android.shadereditor.project.shadertoy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.List;

import de.markusfisch.android.shadereditor.project.shadertoy.ShaderToyShader.Input;

public class ShaderToyChannelTest {
	@Test
	public void aBufferIsAnInput() {
		ShaderToyChannel now = ShaderToyChannel.buffer(0,
				input("buffer", "", "nearest", "clamp", true),
				"bufferA.frag", "Buffer A", false);
		assertEquals("// iChannel0: Buffer A, as drawn in this frame.\n" +
				"#pragma se input \"bufferA.frag\"\n" +
				"uniform sampler2D iChannel0;\n", now.lines());
		assertNull(now.warning());
		assertEquals("sampler2D", now.sampler());
		ShaderToyChannel before = ShaderToyChannel.buffer(3,
				input("buffer", "", "linear", "repeat", true),
				"bufferB.frag", "Buffer B", true);
		assertEquals("// iChannel3: Buffer B, as it was the frame " +
				"before.\n" +
				"#pragma se input \"bufferB.frag\" frame=previous " +
				"filter=linear wrap=repeat\n" +
				"uniform sampler2D iChannel3;\n", before.lines());
		// Passes have no mipmaps: linear, and said.
		ShaderToyChannel mipmap = ShaderToyChannel.buffer(1,
				input("buffer", "", "mipmap", "mirror", true),
				"bufferA.frag", "Buffer A", false);
		assertEquals("// iChannel1: Buffer A, as drawn in this frame.\n" +
				"#pragma se input \"bufferA.frag\" filter=linear " +
				"wrap=mirror\n" +
				"uniform sampler2D iChannel1;\n", mipmap.lines());
		assertEquals("iChannel1 read Buffer A with mipmaps, which passes " +
				"do not have here. It is read with filter=linear.",
				mipmap.warning());
	}

	// No input stands for what the project does not have, so it reads
	// black; a picture can be attached where the app can read one.
	@Test
	public void aTextureIsDeclaredAndSaysHowToAttachAPicture() {
		ShaderToyChannel texture = ShaderToyChannel.missing(1, input(
				"texture", "/media/a/stone.jpg", "mipmap", "repeat", true));
		assertEquals("// iChannel1 read a texture of ShaderToy " +
				"(/media/a/stone.jpg),\n" +
				"// which is not part of the shader. It reads black until " +
				"you attach a picture; the file says how.\n" +
				"// Put a picture into this folder and take the \"//\" off " +
				"the next line:\n" +
				"// #pragma se input \"channel1.png\" mipmap=linear " +
				"wrap=repeat\n" +
				"uniform sampler2D iChannel1;\n", texture.lines());
		assertEquals("iChannel1 read a texture of ShaderToy " +
				"(/media/a/stone.jpg), which is not part of the shader. " +
				"It reads black until you attach a picture; the file " +
				"says how.", texture.warning());
		ShaderToyChannel flipped = ShaderToyChannel.missing(0, input(
				"texture", "", "nearest", "clamp", false));
		assertEquals("// iChannel0 read a texture of ShaderToy,\n" +
				"// which is not part of the shader. It reads black until " +
				"you attach a picture; the file says how.\n" +
				"// ShaderToy read it upside down; the app reads a picture " +
				"upright.\n" +
				"// Put a picture into this folder and take the \"//\" off " +
				"the next line:\n" +
				"// #pragma se input \"channel0.png\" filter=nearest\n" +
				"uniform sampler2D iChannel0;\n", flipped.lines());
		ShaderToyChannel cubemap = ShaderToyChannel.missing(2, input(
				"cubemap", "/media/a/sky.jpg", "linear", "repeat", true));
		assertEquals("// iChannel2 read a cubemap of ShaderToy " +
				"(/media/a/sky.jpg),\n" +
				"// which is not part of the shader. It reads black until " +
				"you attach a picture; the file says how.\n" +
				"// A cubemap is one picture of six squares side by side: " +
				"+X, -X, +Y, -Y, +Z, -Z.\n" +
				"// Put a picture into this folder and take the \"//\" off " +
				"the next line:\n" +
				"// #pragma se input \"channel2.png\"\n" +
				"uniform samplerCube iChannel2;\n", cubemap.lines());
	}

	@Test
	public void everyKindTheAppDoesNotHaveSaysSo() {
		String[][] kinds = {
				{"volume", "/v.bin", "sampler3D",
						"a volume texture of ShaderToy (/v.bin), which " +
								"the app cannot load. It reads black."},
				{"cubemap", "/media/previz/cubemap00.png", "samplerCube",
						"Cube A, a pass that draws a cubemap, which the " +
								"app cannot do. It reads black."},
				{"keyboard", "/presets/tex00.jpg", "sampler2D",
						"the keyboard, which the app does not have. It " +
								"reads black: no key is down."},
				{"webcam", "", "sampler2D",
						"the webcam, which the import does not attach " +
								"the app's camera to. It reads black."},
				{"mic", "", "sampler2D",
						"the microphone, which the app does not turn " +
								"into a picture. It reads black."},
				{"music", "/a.mp3", "sampler2D",
						"music (/a.mp3), which the app does not turn " +
								"into a picture. It reads black."},
				{"musicstream", "", "sampler2D",
						"music, which the app does not turn into a " +
								"picture. It reads black."},
				{"video", "/a.webm", "sampler2D",
						"a video (/a.webm), which the app does not play. " +
								"It reads black."},
				{"buffer", "", "sampler2D",
						"a buffer, which this shader does not have. It " +
								"reads black."},
				{"holo\ngram", "", "sampler2D",
						"something of the kind \"holo gram\", which the " +
								"import does not know. It reads black."},
		};
		for (String[] kind : kinds) {
			ShaderToyChannel channel = ShaderToyChannel.missing(3,
					input(kind[0], kind[1], "linear", "clamp", true));
			assertEquals(kind[0], kind[2], channel.sampler());
			assertEquals("iChannel3 read " + kind[3], channel.warning());
			String[] lines = channel.lines().split("\n");
			assertEquals(kind[0], 3, lines.length);
			assertEquals("uniform " + kind[2] + " iChannel3;", lines[2]);
		}
	}

	@Test
	public void aChannelWithNothingOnItAndOneNobodyToldOf() {
		ShaderToyChannel nothing = ShaderToyChannel.unattached(2);
		assertEquals("// iChannel2: nothing was attached to it on " +
				"ShaderToy. It reads black.\n" +
				"uniform sampler2D iChannel2;\n", nothing.lines());
		assertNull(nothing.warning());
		ShaderToyChannel unknown = ShaderToyChannel.unknown(0, List.of(
				"#pragma se input \"bufferA.frag\" frame=previous"));
		assertEquals("// iChannel0: the code does not say what this " +
				"channel reads, so it reads black.\n" +
				"// Take the \"//\" off the line of what it reads:\n" +
				"// #pragma se input \"bufferA.frag\" frame=previous\n" +
				"// #pragma se input \"channel0.png\"\n" +
				"uniform sampler2D iChannel0;\n", unknown.lines());
		assertEquals("iChannel0 is not attached, because the code does " +
				"not say what a channel reads. It reads black; the file " +
				"lists the lines to choose from.", unknown.warning());
	}

	private static Input input(String kind, String source, String filter,
			String wrap, boolean upright) {
		return new Input(0, kind, "", source, filter, wrap, upright);
	}
}

package de.markusfisch.android.shadereditor.project.shadertoy;

import java.util.List;

import de.markusfisch.android.shadereditor.project.shadertoy.ShaderToyShader.Input;

/**
 * One channel of a tab as its file declares it: the lines up to the
 * declaration of the sampler, and what to tell when the channel does
 * not read what it read on ShaderToy.
 *
 * @param number  0 to 3
 * @param sampler the type of its sampler
 * @param lines   comments, an input when there is one, the declaration
 * @param warning what was not carried over, or null
 */
record ShaderToyChannel(int number, String sampler, String lines,
		String warning) {
	/** A channel that reads the buffer in file. */
	static ShaderToyChannel buffer(int number, Input input, String file,
			String label, boolean previous) {
		String name = "iChannel" + number;
		boolean mipmap = input.filter().equals("mipmap");
		return new ShaderToyChannel(number, "sampler2D",
				"// " + name + ": " + label + (previous
						? ", as it was the frame before.\n"
						: ", as drawn in this frame.\n") +
						"#pragma se input \"" + file + "\"" +
						(previous ? " frame=previous" : "") +
						(input.filter().equals("nearest")
								? ""
								: " filter=linear") +
						wrap(input) + "\n" +
						"uniform sampler2D " + name + ";\n",
				mipmap
						? name + " read " + label + " with mipmaps, which " +
						"passes do not have here. It is read with " +
						"filter=linear."
						: null);
	}

	/** A channel that read something the project does not have. */
	static ShaderToyChannel missing(int number, Input input) {
		String name = "iChannel" + number;
		String source = input.source().isEmpty()
				? ""
				: " (" + ShaderToyHeader.line(input.source()) + ")";
		String sampler = "sampler2D";
		String what;
		String why;
		String attach = null;
		switch (input.kind()) {
			case "texture":
				what = "a texture of ShaderToy" + source;
				why = "which is not part of the shader. It reads black " +
						"until you attach a picture; the file says how.";
				attach = (input.filter().equals("nearest")
						? " filter=nearest"
						: input.filter().equals("mipmap")
						? " mipmap=linear"
						: "") + wrap(input);
				break;
			case "cubemap":
				sampler = "samplerCube";
				if (input.source().contains("/previz/cubemap")) {
					what = "Cube A";
					why = "a pass that draws a cubemap, which the app " +
							"cannot do. It reads black.";
				} else {
					what = "a cubemap of ShaderToy" + source;
					why = "which is not part of the shader. It reads " +
							"black until you attach a picture; the file " +
							"says how.";
					attach = "";
				}
				break;
			case "volume":
				sampler = "sampler3D";
				what = "a volume texture of ShaderToy" + source;
				why = "which the app cannot load. It reads black.";
				break;
			case "keyboard":
				what = "the keyboard";
				why = "which the app does not have. It reads black: no " +
						"key is down.";
				break;
			case "webcam":
				what = "the webcam";
				why = "which the import does not attach the app's camera " +
						"to. It reads black.";
				break;
			case "mic":
			case "music":
			case "musicstream":
				what = input.kind().equals("mic")
						? "the microphone"
						: "music" + source;
				why = "which the app does not turn into a picture. It " +
						"reads black.";
				break;
			case "video":
				what = "a video" + source;
				why = "which the app does not play. It reads black.";
				break;
			case "buffer":
				what = "a buffer";
				why = "which this shader does not have. It reads black.";
				break;
			default:
				what = "something of the kind \"" +
						ShaderToyHeader.line(input.kind()) + "\"";
				why = "which the import does not know. It reads black.";
				break;
		}
		StringBuilder sb = new StringBuilder("// " + name + " read " +
				what + ",\n// " + why + "\n");
		if (attach != null) {
			sb.append(sampler.equals("samplerCube")
					? "// A cubemap is one picture of six squares side " +
					"by side: +X, -X, +Y, -Y, +Z, -Z.\n"
					: "");
			sb.append(input.upright()
					? ""
					: "// ShaderToy read it upside down; the app reads a " +
					"picture upright.\n");
			sb.append("// Put a picture into this folder and take the " +
					"\"//\" off the next line:\n" +
					"// #pragma se input \"channel" + number + ".png\"" +
					attach + "\n");
		}
		return new ShaderToyChannel(number, sampler,
				sb.append("uniform ").append(sampler).append(' ')
						.append(name).append(";\n").toString(),
				name + " read " + what + ", " + why);
	}

	/** A channel the code names and ShaderToy had nothing on. */
	static ShaderToyChannel unattached(int number) {
		String name = "iChannel" + number;
		return new ShaderToyChannel(number, "sampler2D",
				"// " + name + ": nothing was attached to it on " +
						"ShaderToy. It reads black.\n" +
						"uniform sampler2D " + name + ";\n", null);
	}

	/**
	 * A channel of code that came without what its channels read;
	 * choices are the input lines it may get.
	 */
	static ShaderToyChannel unknown(int number, List<String> choices) {
		String name = "iChannel" + number;
		StringBuilder sb = new StringBuilder("// " + name + ": the code " +
				"does not say what this channel reads, so it reads " +
				"black.\n// Take the \"//\" off the line of what it " +
				"reads:\n");
		for (String choice : choices) {
			sb.append("// ").append(choice).append('\n');
		}
		sb.append("// #pragma se input \"channel").append(number)
				.append(".png\"\n");
		return new ShaderToyChannel(number, "sampler2D",
				sb.append("uniform sampler2D ").append(name).append(";\n")
						.toString(),
				name + " is not attached, because the code does not say " +
						"what a channel reads. It reads black; the file " +
						"lists the lines to choose from.");
	}

	private static String wrap(Input input) {
		return input.wrap().equals("repeat") || input.wrap().equals("mirror")
				? " wrap=" + input.wrap()
				: "";
	}
}

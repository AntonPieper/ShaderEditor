package de.markusfisch.android.shadereditor.project.shadertoy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ShaderToyReadmeTest {
	static final String DEFAULT = "This shader is its author's work. The " +
			"import found no licence at the top of its code, so it is " +
			"under Creative Commons Attribution-NonCommercial-ShareAlike " +
			"3.0 Unported " +
			"(https://creativecommons.org/licenses/by-nc-sa/3.0/), " +
			"ShaderToy's default for shaders without a licence of their " +
			"own: name the author, no commercial use, share changes under " +
			"the same terms.\n\n";

	@Test
	public void theAuthorTheFilesAndWhatWasNotCarriedOver() {
		Map<String, String> files = new LinkedHashMap<>();
		files.put("main.frag", "Image: what you see");
		files.put("my tab.glsl", "Kept from ShaderToy, not run");
		assertEquals("# Ink Drop\n\n" +
				"By someone on ShaderToy: " +
				"https://www.shadertoy.com/view/4cVSRw\n\n" +
				DEFAULT +
				"## Files\n" +
				"- [main.frag](main.frag) Image: what you see\n" +
				"- [my tab.glsl](my%20tab.glsl) Kept from ShaderToy, not " +
				"run\n\n" +
				"Buffers keep 16-bit floats here; ShaderToy keeps 32-bit " +
				"ones. A shader that packs data into the bits of a float " +
				"may differ.\n\n" +
				"## Not carried over\n" +
				"- one\n" +
				"- two\n",
				ShaderToyReadme.text(shader(" Ink\nDrop ", "someone",
						"4cVSRw"), files, true, null, List.of("one", "two")));
	}

	// Nothing is made up: no name, no author, and no link from an id
	// that is none of ShaderToy's.
	@Test
	public void withoutANameAnAuthorOrAnId() {
		Map<String, String> files = Map.of("main.frag", "Image");
		String rest = DEFAULT + "## Files\n- [main.frag](main.frag) Image\n";
		assertEquals("# A shader from ShaderToy\n\n" + rest,
				ShaderToyReadme.text(shader(null, null, null), files, false,
						null, List.of()));
		assertEquals("# A shader from ShaderToy\n\n" +
				"From ShaderToy: https://www.shadertoy.com/view/Xs2\n\n" +
				rest,
				ShaderToyReadme.text(shader(" ", null, "Xs2"), files, false,
						null, List.of()));
		assertEquals("# N\n\nBy a b on ShaderToy\n\n" + rest,
				ShaderToyReadme.text(shader("N", "a\tb",
						"x/../../evil?"), files, false, null, List.of()));
	}

	// An author states a licence in the comment the code begins with:
	// the README repeats it instead of naming the default.
	@Test
	public void theLicenceTheCodeBeginsWith() {
		String mit = "// The MIT License\n" +
				"// Copyright \u00a9 2020 Someone\n" +
				"//   Permission is hereby granted.\n" +
				"\n" +
				"/* More of it,\n" +
				" * in a block. */\n" +
				"void mainImage(out vec4 c, in vec2 p) {\n" +
				"\t// Not a licence: MIT is named in the code.\n}\n";
		assertEquals("The MIT License\n" +
				"Copyright \u00a9 2020 Someone\n" +
				"Permission is hereby granted.\n" +
				"More of it,\n" +
				"in a block.", ShaderToyReadme.licence(mit));
		assertEquals("# N\n\n" +
				"This shader is its author's work. Its code begins with " +
				"this licence:\n\n" +
				"> CC0: do what you like\n" +
				"> with it\n\n" +
				"## Files\n- [main.frag](main.frag) Image\n",
				ShaderToyReadme.text(shader("N", null, null),
						Map.of("main.frag", "Image"), false,
						ShaderToyReadme.licence(
								"/* CC0: do what you like\n   with it */\n" +
										"void mainImage() {}"), List.of()));
		// A comment that says nothing of a licence, a licence further
		// down, and no comment at all.
		assertNull(ShaderToyReadme.licence(
				"// Rings around the middle.\nvoid mainImage() {}"));
		assertNull(ShaderToyReadme.licence(
				"void mainImage() {}\n// MIT License\n"));
		assertNull(ShaderToyReadme.licence(""));
		// A long one is cut and says so.
		String cut = ShaderToyReadme.licence(
				"// License\n" + "// line\n".repeat(40));
		assertEquals(25, cut.split("\n").length);
		assertEquals("\u2026", cut.split("\n")[24]);
	}

	private static ShaderToyShader shader(String name, String author,
			String id) {
		return new ShaderToyShader(name, author, id, true, List.of());
	}
}

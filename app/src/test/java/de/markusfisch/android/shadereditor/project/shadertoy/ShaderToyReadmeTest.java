package de.markusfisch.android.shadereditor.project.shadertoy;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ShaderToyReadmeTest {
	private static final String LICENCE = "This shader is its author's " +
			"work, under the licence they chose. Look for one in the " +
			"comments of the code. Where a shader states none, " +
			"ShaderToy's default is reported to be CC BY-NC-SA 3.0: name " +
			"the author, no commercial use, share changes under the same " +
			"terms.\n\n";

	@Test
	public void theAuthorTheFilesAndWhatWasNotCarriedOver() {
		Map<String, String> files = new LinkedHashMap<>();
		files.put("main.frag", "Image: what you see");
		files.put("my tab.glsl", "Kept from ShaderToy, not run");
		assertEquals("# Ink Drop\n\n" +
				"By someone on ShaderToy: " +
				"https://www.shadertoy.com/view/4cVSRw\n\n" +
				LICENCE +
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
						"4cVSRw"), files, true, List.of("one", "two")));
	}

	// Nothing is made up: no name, no author, and no link from an id
	// that is none of ShaderToy's.
	@Test
	public void withoutANameAnAuthorOrAnId() {
		Map<String, String> files = Map.of("main.frag", "Image");
		String rest = LICENCE + "## Files\n- [main.frag](main.frag) Image\n";
		assertEquals("# A shader from ShaderToy\n\n" + rest,
				ShaderToyReadme.text(shader(null, null, null), files, false,
						List.of()));
		assertEquals("# A shader from ShaderToy\n\n" +
				"From ShaderToy: https://www.shadertoy.com/view/Xs2\n\n" +
				rest,
				ShaderToyReadme.text(shader(" ", null, "Xs2"), files, false,
						List.of()));
		assertEquals("# N\n\nBy a b on ShaderToy\n\n" + rest,
				ShaderToyReadme.text(shader("N", "a\tb",
						"x/../../evil?"), files, false, List.of()));
	}

	private static ShaderToyShader shader(String name, String author,
			String id) {
		return new ShaderToyShader(name, author, id, true, List.of());
	}
}

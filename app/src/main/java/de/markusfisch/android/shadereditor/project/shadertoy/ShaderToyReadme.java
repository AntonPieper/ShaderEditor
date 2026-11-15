package de.markusfisch.android.shadereditor.project.shadertoy;

import java.util.List;
import java.util.Map;

/**
 * The README of an imported shader: whose work it is, what each file
 * of the folder is, in the order the drawer lists them by, and what
 * the import did not carry over.
 */
final class ShaderToyReadme {
	private ShaderToyReadme() {
	}

	/**
	 * @param files   the files of the folder in the order to list them,
	 *                each with what it is
	 * @param buffers whether a file is a buffer
	 */
	static String text(ShaderToyShader shader, Map<String, String> files,
			boolean buffers, List<String> warnings) {
		String name = shader.name() != null &&
				!shader.name().trim().isEmpty()
				? ShaderToyHeader.line(shader.name())
				: "A shader from ShaderToy";
		StringBuilder sb = new StringBuilder("# " + name + "\n\n");
		String author = shader.author() != null
				? ShaderToyHeader.line(shader.author())
				: "";
		// Only an id as ShaderToy has them is made a link.
		String link = shader.id() != null &&
				shader.id().matches("[A-Za-z0-9]{1,16}")
				? "https://www.shadertoy.com/view/" + shader.id()
				: null;
		if (!author.isEmpty() || link != null) {
			sb.append(author.isEmpty() ? "From" : "By " + author + " on")
					.append(" ShaderToy")
					.append(link != null ? ": " + link : "")
					.append("\n\n");
		}
		sb.append("This shader is its author's work, under the licence " +
				"they chose. Look for one in the comments of the code. " +
				"Where a shader states none, ShaderToy's default is " +
				"reported to be CC BY-NC-SA 3.0: name the author, no " +
				"commercial use, share changes under the same terms.\n\n" +
				"## Files\n");
		for (Map.Entry<String, String> file : files.entrySet()) {
			sb.append("- [").append(file.getKey()).append("](")
					.append(file.getKey().replace(" ", "%20")).append(") ")
					.append(file.getValue()).append('\n');
		}
		if (buffers) {
			sb.append("\nBuffers keep 16-bit floats here; ShaderToy " +
					"keeps 32-bit ones. A shader that packs data into " +
					"the bits of a float may differ.\n");
		}
		if (!warnings.isEmpty()) {
			sb.append("\n## Not carried over\n");
			for (String warning : warnings) {
				sb.append("- ").append(warning).append('\n');
			}
		}
		return sb.toString();
	}
}

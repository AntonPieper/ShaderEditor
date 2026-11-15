package de.markusfisch.android.shadereditor.project.shadertoy;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * A shader as ShaderToy has it: its tabs with their code, and what
 * each channel of a tab reads. It comes from ShaderToy's JSON
 * ({@link ShaderToyJson}) or from the code of the tabs alone, which
 * does not say what the channels read.
 *
 * @param name     its title, or null
 * @param author   who wrote it, or null
 * @param id       the id in its link, or null
 * @param wired    whether the inputs of the passes are known; false
 *                 for tabs given as code
 * @param passes   its tabs, buffers in the order they are drawn
 */
public record ShaderToyShader(String name, String author, String id,
		boolean wired, List<Pass> passes) {
	public ShaderToyShader {
		passes = List.copyOf(passes);
	}

	public enum Kind {IMAGE, BUFFER, COMMON, CUBEMAP, SOUND, OTHER}

	/**
	 * One tab.
	 *
	 * @param name   as ShaderToy shows it: "Image", "Buffer A"
	 * @param output for a buffer, the id inputs read it by, else ""
	 */
	public record Pass(Kind kind, String name, String code,
			List<Input> inputs, String output) {
		public Pass {
			inputs = List.copyOf(inputs);
		}
	}

	/**
	 * What a channel of a tab reads.
	 *
	 * @param channel 0 to 3
	 * @param kind    "buffer", "texture", "cubemap", "volume",
	 *                "keyboard", "webcam", "mic", "music",
	 *                "musicstream" or "video"
	 * @param id      for a buffer, the output of its pass
	 * @param source  the path of the medium on ShaderToy, or ""
	 * @param filter  "nearest", "linear" or "mipmap"
	 * @param wrap    "clamp", "repeat" or "mirror"
	 * @param upright false when the picture is read upside down
	 */
	public record Input(int channel, String kind, String id, String source,
			String filter, String wrap, boolean upright) {
	}

	/**
	 * A shader from the code of its tabs by their names on ShaderToy:
	 * "Image", "Common", "Buffer A" to "Buffer D", "Cube A", "Sound",
	 * in any case. Buffers are drawn in the order of their letters. A
	 * buffer's output is its name.
	 */
	public static ShaderToyShader ofTabs(String name,
			Map<String, String> tabs) {
		Map<String, Pass> sorted = new TreeMap<>();
		for (Map.Entry<String, String> tab : tabs.entrySet()) {
			String tabName = tab.getKey().trim();
			Kind kind = kindOf(tabName);
			sorted.put(kind.ordinal() + tabName.toLowerCase(Locale.ROOT),
					new Pass(kind, tabName, tab.getValue(), List.of(),
							kind == Kind.BUFFER ? tabName : ""));
		}
		return new ShaderToyShader(name, null, null, false,
				new ArrayList<>(sorted.values()));
	}

	/** The kind of a tab named name on ShaderToy. */
	static Kind kindOf(String name) {
		String lower = name.toLowerCase(Locale.ROOT);
		return lower.equals("image") ? Kind.IMAGE
				: lower.equals("common") ? Kind.COMMON
				: lower.equals("sound") ? Kind.SOUND
				: lower.startsWith("buf") ? Kind.BUFFER
				: lower.startsWith("cube") ? Kind.CUBEMAP
				: Kind.OTHER;
	}
}

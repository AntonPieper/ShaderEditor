package de.markusfisch.android.shadereditor.project;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The text of one program as the driver gets it, and the origin of
 * each of its lines: the one place that makes it. The engine compiles
 * this text, and whoever has to know what the compiler reads, as the
 * uses of a declaration do, reads this text and no description of it.
 *
 * It is made of the lines of a pass with its includes expanded and its
 * host directives blanked ({@link PassSource#lines}), in this order:
 * the characters a compiler cannot read are left out where they end a
 * line of code right behind a backslash, so that the line goes on as
 * it was written to; the lines a temporary build adds are put in; the
 * lines the engine generates are put behind the head; and every
 * character a compiler cannot read becomes a space.
 *
 * @param text      the text, each line ended by a line feed
 * @param sourceMap the origin of every line of it
 * @param version   the version of the language it is written in, 100
 *                  when it says none
 */
public record CompiledSource(String text, SourceMap sourceMap, int version) {
	/** What the engine defines before the first line of code. */
	public static final String SHADER_EDITOR = "SHADER_EDITOR";

	private static final Pattern VERSION = Pattern.compile("\\d{1,9}");
	private static final Pattern EXTERNAL_SAMPLER = Pattern.compile(
			"\\bsamplerExternalOES\\b");

	/**
	 * Return what the driver gets for the lines of a program.
	 *
	 * @param head how many of the lines must stay first
	 */
	public static CompiledSource of(List<String> lines, int head,
			SourceMap sourceMap) {
		return of(lines, PreprocessorScan.scan(lines, null), head, sourceMap,
				0, List.of());
	}

	/**
	 * As {@link #of}, with the scan of the lines that the caller has,
	 * and with lines a temporary build adds behind line after.
	 */
	public static CompiledSource of(List<String> lines,
			PreprocessorScan scan, int head, SourceMap sourceMap, int after,
			List<String> added) {
		int version = version(scan);
		List<String> generated = new ArrayList<>();
		generated.add("#define " + SHADER_EDITOR + " 1");
		if (EXTERNAL_SAMPLER.matcher(String.join("\n", scan.code())).find()) {
			generated.add(version >= 300
					? "#extension GL_OES_EGL_image_external_essl3 : require"
					: "#extension GL_OES_EGL_image_external : require");
		}
		// A line of code that ends behind a backslash in characters
		// a compiler cannot read goes on in the next line, as it was
		// written to: they are left out, where a space would end it.
		List<String> text = new ArrayList<>(lines.size() + 4);
		for (int i = 0; i < lines.size(); ++i) {
			String line = lines.get(i);
			int tail = SourceText.afterContinuation(line,
					scan.code().get(i));
			text.add(tail < 0 ? line : line.substring(0, tail));
		}
		if (!added.isEmpty()) {
			text.addAll(after, added);
			sourceMap = sourceMap.insertGenerated(after, added.size());
		}
		text.addAll(head, generated);
		return new CompiledSource(
				removeNonAscii(String.join("\n", text) + "\n"),
				sourceMap.insertGenerated(head, generated.size()), version);
	}

	/** Return the version the lines of a scan say, 100 if none. */
	public static int version(PreprocessorScan scan) {
		for (PreprocessorScan.Directive directive : scan.directives()) {
			if (directive.kind() == PreprocessorScan.Kind.VERSION) {
				Matcher matcher = VERSION.matcher(directive.argument());
				return matcher.lookingAt()
						? Integer.parseInt(matcher.group())
						: 100;
			}
		}
		return 100;
	}

	// Non-ASCII characters crash some shader compilers, so each becomes
	// a space here, at the last step before the compiler, and never in
	// the stored text. In a comment that changes nothing; in code
	// preparation warned of it. Line breaks stay, so lines still map.
	private static String removeNonAscii(String text) {
		return text.replaceAll("[^\\x0A\\x09\\x20-\\x7E]", " ");
	}
}

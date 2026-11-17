package de.markusfisch.android.shadereditor.project;

import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.markusfisch.android.shadereditor.syntax.GlslKnowledge;
import de.markusfisch.android.shadereditor.syntax.Knowledge.Symbol;

/**
 * For the tests of uses: files in memory, asked as a project asks, so
 * that every test reads the text the build makes for the driver.
 */
final class UsesHarness {
	static final String MAIN = "main.frag";
	static final String REVISION = "r1";
	static final Builtins BUILTINS = new Builtins(Set.of(), Map.of());

	private UsesHarness() {
	}

	/** Return a folder of text files by their paths. */
	static ProjectFiles files(Map<String, String> texts) {
		return new ProjectFiles() {
			@Override
			public Read read(ProjectPath path) {
				String text = texts.get(path.toString());
				return text == null ? null : new Content(text.getBytes(
						StandardCharsets.UTF_8), REVISION);
			}

			@Override
			public Listing list(ProjectPath folder) {
				String prefix = folder == null ? "" : folder + "/";
				Set<String> names = new LinkedHashSet<>();
				List<Entry> entries = new ArrayList<>();
				for (String path : texts.keySet()) {
					if (path.startsWith(prefix)) {
						String rest = path.substring(prefix.length());
						int slash = rest.indexOf('/');
						String name = slash < 0 ? rest
								: rest.substring(0, slash);
						if (names.add(name)) {
							entries.add(new Entry(name, slash >= 0));
						}
					}
				}
				return new Entries(entries);
			}
		};
	}

	/** Return one text as the folder of a shader of one file. */
	static Map<String, String> one(String text) {
		Map<String, String> texts = new LinkedHashMap<>();
		texts.put(MAIN, text);
		return texts;
	}

	/**
	 * Return the uses of what the editor knows to be declared at
	 * offset of file, or null if they cannot be told.
	 */
	static ProjectKnowledge.Uses uses(Map<String, String> texts, String file,
			int offset, String name) {
		ProjectKnowledge knowledge = ProjectKnowledge.read(files(texts));
		return knowledge.uses(ProjectPath.parse(file).value(), REVISION,
				declared(texts.get(file), offset), name, BUILTINS);
	}

	static ProjectKnowledge.Uses uses(String text, int offset) {
		return uses(one(text), MAIN, offset, null);
	}

	/**
	 * Return what the editor knows to be declared at an offset of
	 * text: a declaration, a part of one, or a local.
	 */
	static Symbol declared(String text, int at) {
		GlslKnowledge known = GlslKnowledge.of(text);
		for (Symbol declared : known.symbols()) {
			if (declared.start() == at) {
				return declared;
			}
			for (Symbol part : declared.parts()) {
				if (part.start() == at) {
					return part;
				}
			}
		}
		int end = at;
		while (end < text.length() && (Character.isLetterOrDigit(
				text.charAt(end)) || text.charAt(end) == '_')) {
			++end;
		}
		List<Symbol> found = known.at(at, text.substring(at, end));
		assertTrue(text, !found.isEmpty() && found.get(0).start() == at);
		return found.get(0);
	}

	/** Return each place as its file, where it starts and what it is. */
	static List<String> told(ProjectKnowledge.Uses uses) {
		List<String> told = new ArrayList<>();
		for (ProjectKnowledge.Place place : uses.places()) {
			told.add(place.file() + ":" + place.offset() + " " + place.why() +
					(place.about() == null ? "" : " (" + place.about() +
							")"));
		}
		return told;
	}
}

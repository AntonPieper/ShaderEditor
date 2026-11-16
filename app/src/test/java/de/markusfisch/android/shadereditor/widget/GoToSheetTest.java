package de.markusfisch.android.shadereditor.widget;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import de.markusfisch.android.shadereditor.editor.Text;
import de.markusfisch.android.shadereditor.syntax.GlslKnowledge;
import de.markusfisch.android.shadereditor.syntax.Knowledge;

public class GoToSheetTest {
	private static final String MAIN = "uniform vec2 resolution;\n" +
			"#define SAT(x) clamp(x, 0.0, 1.0)\n" +
			"struct Ray { vec3 origin; };\n" +
			"float disc(vec2 p, float radius) { return radius; }\n" +
			"void main(void) {\n" +
			"\tvec3 origin;\n" +
			"}\n";
	private static final String SDF = "// Distances.\n" +
			"float sdRoundBox(vec2 p, vec2 b, float r) { return 0.; }\n" +
			"float sdCircle(vec2 p, float r) { return length(p) - r; }\n";

	@Test
	public void theFileComesFirstThenEachIncludedFileUnderItsPath() {
		assertEquals(List.of("resolution 1", "SAT(x) 2", "Ray 3",
				"disc(p, radius) 4", "main() 5", "# lib/sdf.glsl",
				"sdRoundBox(p, b, r) 2 lib/sdf.glsl",
				"sdCircle(p, r) 3 lib/sdf.glsl"), shown(outline()));
	}

	@Test
	public void aNameNarrowsStartingNamesFirstInEachFile() {
		assertEquals(List.of("# lib/sdf.glsl",
				"sdCircle(p, r) 3 lib/sdf.glsl"), shown(narrow(" Ci")));
		assertEquals(List.of("resolution 1", "Ray 3"),
				shown(narrow("r").subList(0, 2)));
		// A name that starts with the text comes before one that holds
		// it, though it stands later in the file.
		assertEquals(List.of("SAT(x) 2", "resolution 1"),
				shown(narrow("s").subList(0, 2)));
		assertEquals(List.of(), shown(narrow("zzz")));
	}

	@Test
	public void aNumberIsThatLineAndTheLastForMore() {
		assertEquals(List.of("line 6: vec3 origin;"), shown(narrow("6")));
		assertEquals(List.of("line 8: "), shown(narrow("400")));
		assertEquals(List.of("line 1: uniform vec2 resolution;"),
				shown(narrow("0")));
	}

	private static List<GoToSheet.Row> narrow(String typed) {
		return GoToSheet.narrow(outline(), typed, Text.of(MAIN));
	}

	private static List<GoToSheet.Row> outline() {
		GlslKnowledge sdf = GlslKnowledge.of(SDF);
		Text sdfText = Text.of(SDF);
		Knowledge.Scope scope = new Knowledge.Scope() {
			@Override
			public List<String> names() {
				return List.of("sdCircle", "sdRoundBox");
			}

			@Override
			public List<Knowledge.Symbol> find(CharSequence word) {
				List<Knowledge.Symbol> found = new ArrayList<>();
				for (Knowledge.Symbol symbol : sdf.symbols()) {
					if (symbol.name().contentEquals(word)) {
						found.add(symbol.in("lib/sdf.glsl"));
					}
				}
				return found;
			}

			@Override
			public int line(Knowledge.Symbol symbol) {
				return sdfText.lineOf(symbol.start());
			}
		};
		return GoToSheet.outline(GlslKnowledge.of(MAIN), Text.of(MAIN),
				scope);
	}

	private static List<String> shown(List<GoToSheet.Row> rows) {
		List<String> shown = new ArrayList<>();
		for (GoToSheet.Row row : rows) {
			switch (row.kind()) {
				case HEADING -> shown.add("# " + row.name());
				case LINE -> shown.add("line " + (row.line() + 1) + ": " +
						row.name());
				default -> shown.add(row.name() + row.parameters() + " " +
						(row.line() + 1) + (row.file() != null
						? " " + row.file()
						: ""));
			}
		}
		return shown;
	}
}

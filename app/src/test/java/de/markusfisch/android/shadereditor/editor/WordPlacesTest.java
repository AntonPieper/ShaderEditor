package de.markusfisch.android.shadereditor.editor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.markusfisch.android.shadereditor.syntax.GlslKnowledge;

public class WordPlacesTest {
	private static final String MAIN = """
			#include "radius.glsl"
			uniform float radius; // the radius of the disc
			float radius2 = 1.0;
			/* radius
			   and radius_b */
			#define R radius
			float disc(vec2 p, float radius) {
				return length(p) - radius;
			}
			float ring(vec2 p) {
				float radius = 0.5;
				return abs(length(p) - radius);
			}
			void main() {
				float d = disc(vec2(0.0), radius) + R;
			}
			""";

	@Test
	public void findsTheWholeWordInCodeAndApartInComments() {
		List<WordPlaces.Place> places = WordPlaces.in("main.frag",
				Text.of(MAIN), "radius");
		assertEquals(List.of(2, 2, 4, 6, 7, 8, 11, 12, 15), lines(places));
		assertEquals(List.of(false, true, true, false, false, false, false,
				false, false), comments(places));
		WordPlaces.Place first = places.get(0);
		assertEquals(MAIN.indexOf("radius;"), first.start());
		assertEquals("uniform float radius; // the radius of the disc",
				first.code());
		assertTrue(WordPlaces.in("main.frag", Text.of(MAIN), "radiu")
				.isEmpty());
	}

	@Test
	public void countsInTheTextAsTheEditorHoldsIt() {
		// Lines end in "\r\n", and a name stands behind other alphabets.
		String crlf = "// große Ä\r\nfloat größe = r;\r\nfloat r;\r\n";
		Text text = Text.of(crlf);
		List<WordPlaces.Place> places = WordPlaces.in("a.frag", text, "r");
		assertEquals(2, places.size());
		for (WordPlaces.Place place : places) {
			assertEquals("r", text.substring(place.start(),
					place.start() + 1));
		}
		assertEquals(List.of(2, 3), lines(places));
	}

	@Test
	public void saysWhenCodeGluesNames() {
		assertTrue(WordPlaces.glues(Text.of("#define F(n) layer##n\n")));
		assertFalse(WordPlaces.glues(Text.of("// a##b\nfloat a;\n")));
		assertFalse(WordPlaces.glues(Text.of(MAIN)));
	}

	@Test
	public void aLocalOrParameterIsInItsFunction() {
		GlslKnowledge known = GlslKnowledge.of(MAIN);
		WordPlaces.Function disc = WordPlaces.functionOf(known,
				MAIN.indexOf("- radius"), "radius");
		assertEquals("disc", disc.name());
		WordPlaces.Function ring = WordPlaces.functionOf(known,
				MAIN.indexOf("float radius = 0.5") + 6, "radius");
		assertEquals("ring", ring.name());
		// The uniform and a function are no locals.
		assertNull(WordPlaces.functionOf(known, MAIN.indexOf("radius) + R"),
				"radius"));
		assertNull(WordPlaces.functionOf(known, MAIN.indexOf("disc("),
				"disc"));
	}

	@Test
	public void groupsALocalShadowingAGlobalByItsFunction() {
		List<WordPlaces.Place> places = new ArrayList<>(WordPlaces.in(
				"main.frag", Text.of(MAIN), "radius"));
		places.addAll(WordPlaces.in("lib/radius.glsl",
				Text.of("float radius;\n"), "radius"));
		WordPlaces.Function ring = WordPlaces.functionOf(
				GlslKnowledge.of(MAIN), MAIN.indexOf("radius = 0.5"),
				"radius");
		List<WordPlaces.Group> groups = WordPlaces.group(places, "main.frag",
				null, ring, null);
		assertEquals(3, groups.size());
		assertEquals(WordPlaces.Group.Kind.FUNCTION, groups.get(0).kind());
		assertEquals("ring", groups.get(0).name());
		assertEquals(List.of(11, 12), lines(groups.get(0).places()));
		// The uniform, the other function's parameter, the macro, main's
		// use of the uniform, and the other file's own.
		WordPlaces.Group elsewhere = groups.get(1);
		assertEquals(WordPlaces.Group.Kind.ELSEWHERE, elsewhere.kind());
		assertEquals(List.of(2, 6, 7, 8, 15, 1), lines(elsewhere.places()));
		assertEquals("lib/radius.glsl", elsewhere.places().get(5).file());
		assertEquals(WordPlaces.Group.Kind.COMMENTS, groups.get(2).kind());
		assertEquals(List.of(2, 4), lines(groups.get(2).places()));
		assertTrue(groups.get(0).kind().ticked());
		assertFalse(elsewhere.kind().ticked());
		assertFalse(groups.get(2).kind().ticked());
	}

	@Test
	public void groupsAnyOtherNameByFileTheDeclarationFirst() {
		List<WordPlaces.Place> places = new ArrayList<>();
		places.addAll(WordPlaces.in("b.frag", Text.of("x = sdf(p);\n"),
				"sdf"));
		places.addAll(WordPlaces.in("main.frag",
				Text.of("y = sdf(q);\nz = sdf(r);\n"), "sdf"));
		places.addAll(WordPlaces.in("a.frag", Text.of("w = sdf(p);\n"),
				"sdf"));
		places.addAll(WordPlaces.in("lib/sdf.glsl",
				Text.of("// sdf\nfloat sdf(vec2 p) {}\n"), "sdf"));
		List<WordPlaces.Group> groups = WordPlaces.group(places, "main.frag",
				"lib/sdf.glsl", null, null);
		List<String> names = new ArrayList<>();
		for (WordPlaces.Group group : groups) {
			names.add(group.kind() + " " + group.name());
		}
		assertEquals(List.of("FILE lib/sdf.glsl", "FILE main.frag",
				"FILE a.frag", "FILE b.frag", "COMMENTS null"), names);
		assertEquals(List.of(1, 2), lines(groups.get(1).places()));
	}

	@Test
	public void filesConnectedThroughIncludesAreTheirOwnGroupsTheRestOther() {
		// Rings and Blobs share lib; Blobs has a helper; Lone shares
		// nothing, but writes the same word.
		Map<String, List<String>> names = Map.of(
				"Rings.frag", List.of("lib/sdf.glsl"),
				"Blobs.frag", List.of("lib/sdf.glsl", "blobs.glsl"),
				"Lone.frag", List.of(),
				"lib/sdf.glsl", List.of());
		Set<String> connected = WordPlaces.connected(names, "Rings.frag");
		assertEquals(Set.of("Rings.frag", "lib/sdf.glsl", "Blobs.frag",
				"blobs.glsl"), connected);
		List<WordPlaces.Place> places = new ArrayList<>();
		for (String file : List.of("Rings.frag", "lib/sdf.glsl", "blobs.glsl",
				"Lone.frag")) {
			places.addAll(WordPlaces.in(file, Text.of("float sdf;\n"), "sdf"));
		}
		List<WordPlaces.Group> groups = WordPlaces.group(places, "Rings.frag",
				"lib/sdf.glsl", null, connected);
		List<String> names2 = new ArrayList<>();
		for (WordPlaces.Group group : groups) {
			names2.add(group.kind() + " " + group.name() + " " +
					group.kind().ticked());
		}
		assertEquals(List.of("FILE lib/sdf.glsl true", "FILE Rings.frag true",
				"FILE blobs.glsl true", "OTHER null false"), names2);
		assertEquals("Lone.frag", groups.get(3).places().get(0).file());
		// Lone's own sdf is no name a rename there would clash with.
		assertNull(WordPlaces.firstInScope(groups.get(3).places(),
				"Rings.frag", null, connected));
	}

	@Test
	public void theFirstPlaceInScopeIsInTheFunctionOrTheShownFileFirst() {
		List<WordPlaces.Place> places = new ArrayList<>(WordPlaces.in(
				"lib.glsl", Text.of("float d;\n"), "d"));
		places.addAll(WordPlaces.in("main.frag", Text.of(MAIN), "d"));
		assertEquals("main.frag", WordPlaces.firstInScope(places,
				"main.frag", null, null).file());
		WordPlaces.Function ring = WordPlaces.functionOf(
				GlslKnowledge.of(MAIN), MAIN.indexOf("radius = 0.5"),
				"radius");
		assertNull(WordPlaces.firstInScope(places, "main.frag", ring,
				null));
		WordPlaces.Function main = new WordPlaces.Function("main",
				MAIN.indexOf("void main"), MAIN.length());
		assertEquals(14, WordPlaces.firstInScope(places, "main.frag", main, null)
				.line());
	}

	@Test
	public void aLongFileIsQuick() {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < 5000; ++i) {
			sb.append("float v").append(i).append(" = radius * ")
					.append(i).append(".0; // radius of ring ")
					.append(i).append('\n');
		}
		Text text = Text.of(sb);
		WordPlaces.in("big.frag", text, "radius");
		long start = System.nanoTime();
		List<WordPlaces.Place> places = WordPlaces.in("big.frag", text,
				"radius");
		long millis = (System.nanoTime() - start) / 1_000_000;
		assertEquals(10_000, places.size());
		System.out.println("5,000 lines, 10,000 places: " + millis + " ms");
		assertTrue(millis < 500);
	}

	private static List<Integer> lines(List<WordPlaces.Place> places) {
		List<Integer> lines = new ArrayList<>();
		for (WordPlaces.Place place : places) {
			lines.add(place.line() + 1);
		}
		return lines;
	}

	private static List<Boolean> comments(List<WordPlaces.Place> places) {
		List<Boolean> comments = new ArrayList<>();
		for (WordPlaces.Place place : places) {
			comments.add(place.comment());
		}
		return comments;
	}
}

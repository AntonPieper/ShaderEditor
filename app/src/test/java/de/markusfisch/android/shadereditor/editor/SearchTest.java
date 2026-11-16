package de.markusfisch.android.shadereditor.editor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SearchTest {
	private static final Search.Cancel GO_ON = () -> false;
	private static final Text DISC = Text.of("float disc(vec2 p, float radius) {\n" +
			"\treturn smoothstep(radius + soft, Radius - soft, d);\n" +
			"}\n" +
			"float radiusB = _radius;");

	@Test
	public void anyCaseByDefaultAndExactCaseWhenAsked() {
		assertEquals(5, count("radius", false, false));
		assertEquals(4, count("radius", true, false));
		Search.Matches matches = Search.all(DISC, query("radius", true,
				false), GO_ON);
		assertEquals(DISC.toString().indexOf("radius"), matches.start(0));
		assertEquals(0, matches.indexOf(matches.start(0)));
		assertEquals(-1, matches.indexOf(matches.start(0) + 1));
		assertEquals(1, matches.after(matches.start(0) + 1));
		assertEquals(0, matches.after(0));
		assertEquals(4, matches.after(DISC.length()));
	}

	@Test
	public void aWholeWordHasNoLetterDigitOrUnderscoreBesideIt() {
		assertEquals(3, count("radius", false, true));
		assertEquals(0, count("adius", false, true));
		assertEquals(1, count("radiusB", true, true));
	}

	@Test
	public void matchesDoNotOverlap() {
		Search.Matches matches = Search.all(Text.of("aaaaa\naa"),
				query("aa", true, false), GO_ON);
		assertEquals(3, matches.count());
		assertEquals(0, matches.start(0));
		assertEquals(2, matches.start(1));
		assertEquals(6, matches.start(2));
	}

	@Test
	public void anEmptyQueryOrALineBreakMatchesNothing() {
		assertEquals(0, count("", false, false));
		assertEquals(0, count("radius)\n", false, false));
		assertEquals(-1, Search.next(DISC, Search.Query.NONE, 0, 100));
	}

	@Test
	public void nextAndPreviousGoRoundTheEnds() {
		Search.Query radius = query("radius", true, false);
		String s = DISC.toString();
		int first = s.indexOf("radius");
		int last = s.lastIndexOf("radius");
		assertEquals(first, Search.next(DISC, radius, 0, Integer.MAX_VALUE));
		assertEquals(first, Search.next(DISC, radius, first, 1));
		// From behind the last match, round to the first.
		assertEquals(first, Search.next(DISC, radius, last + 6,
				Integer.MAX_VALUE));
		assertEquals(last, Search.previous(DISC, radius, first));
		assertEquals(first, Search.previous(DISC, radius,
				s.indexOf("radius", first + 1)));
		// One match in one line is found from inside itself, around.
		Text one = Text.of("ab");
		Search.Query a = query("a", true, false);
		assertEquals(0, Search.next(one, a, 1, Integer.MAX_VALUE));
		assertEquals(0, Search.previous(one, a, 0));
	}

	@Test
	public void nextStopsAfterItsLines() {
		Text text = Text.of("a\nb\nc\nradius");
		Search.Query radius = query("radius", true, false);
		assertEquals(Search.UNKNOWN, Search.next(text, radius, 0, 3));
		assertEquals(text.length() - 6, Search.next(text, radius, 0, 4));
		assertEquals(-1, Search.next(text, query("x", true, false), 0,
				Integer.MAX_VALUE));
	}

	@Test
	public void keepsAtMostMaxAndSaysThereAreMore() {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < Search.MAX + 1; ++i) {
			sb.append("x\n");
		}
		Search.Matches matches = Search.all(Text.of(sb),
				query("x", true, false), GO_ON);
		assertEquals(Search.MAX, matches.count());
		assertTrue(matches.more());
		assertFalse(Search.all(DISC, query("x", true, false), GO_ON).more());
	}

	@Test
	public void replaceAllIsOneChangeAndOneUndo() {
		Document document = new Document(Text.of(
				"vec4 a = texture2D(t, uv);\nfloat b;\nvec4 c = TEXTURE2D(u);"));
		String before = document.text().toString();
		Search.Replace all = Search.replaceAll(Search.all(document.text(),
				query("texture2D", false, true), Integer.MAX_VALUE, GO_ON),
				"texture");
		document.replace(all.from(), all.to(), all.text());
		assertEquals("vec4 a = texture(t, uv);\nfloat b;\nvec4 c = texture(u);",
				document.text().toString());
		document.undo();
		assertEquals(before, document.text().toString());
		assertFalse(document.canUndo());
		assertNull(Search.replaceAll(Search.all(document.text(),
				query("zzz", true, false), GO_ON), "y"));
	}

	@Test
	public void aCancelledSearchHasNoResult() {
		assertNull(Search.all(DISC, query("radius", false, false),
				() -> true));
	}

	private static int count(String text, boolean matchCase,
			boolean wholeWord) {
		return Search.all(DISC, query(text, matchCase, wholeWord),
				GO_ON).count();
	}

	private static Search.Query query(String text, boolean matchCase,
			boolean wholeWord) {
		return new Search.Query(text, matchCase, wholeWord);
	}
}

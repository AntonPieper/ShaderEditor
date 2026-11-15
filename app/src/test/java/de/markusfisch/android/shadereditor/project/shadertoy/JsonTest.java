package de.markusfisch.android.shadereditor.project.shadertoy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;
import java.util.Map;

public class JsonTest {
	@Test
	public void valuesOfEveryKind() throws Json.Malformed {
		Object value = Json.parse("\uFEFF {\"a\": [1, -2.5e1, true, false, " +
				"null], \"b\": {\"c\": \"x\\n\\u0041\\/\\\"\"}, \"e\": []}\n");
		Map<?, ?> object = (Map<?, ?>) value;
		List<?> list = (List<?>) object.get("a");
		assertEquals(List.of(1.0, -25.0, true, false),
				list.subList(0, 4));
		assertSame(Json.NULL, list.get(4));
		assertEquals("x\nA/\"", ((Map<?, ?>) object.get("b")).get("c"));
		assertEquals(List.of(), object.get("e"));
	}

	@Test
	public void whatIsNotJsonSaysWhereItStops() {
		for (String text : new String[]{
				"", "{", "[1,", "{\"a\" 1}", "{\"a\": }", "{a: 1}",
				"[1 2]", "\"open", "\"bad \\x\"", "{} {}", "nul", "1.",
				"{\"a\": 1,}"}) {
			Json.Malformed e = assertThrows(text, Json.Malformed.class,
					() -> Json.parse(text));
			assertTrue(e.getMessage(), e.getMessage().startsWith("line 1: "));
		}
		Json.Malformed e = assertThrows(Json.Malformed.class,
				() -> Json.parse("{\n\"a\": [\n1,\n]}"));
		assertTrue(e.getMessage(), e.getMessage().startsWith("line 4: "));
	}

	// Never the stack: a text of brackets only is refused.
	@Test
	public void deepNestingIsRefused() {
		Json.Malformed e = assertThrows(Json.Malformed.class,
				() -> Json.parse("[".repeat(100000)));
		assertTrue(e.getMessage(), e.getMessage().contains("nested"));
	}
}

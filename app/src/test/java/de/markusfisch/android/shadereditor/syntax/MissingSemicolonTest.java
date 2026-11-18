package de.markusfisch.android.shadereditor.syntax;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class MissingSemicolonTest {
	@Test
	public void onlyABareSyntaxErrorAtATokenHasPlaces() {
		String text = "float a = 1.0\nfloat b = a;";
		int after = text.indexOf("\n");
		assertEquals(after, MissingSemicolon.place(text, 1,
				"'float' : syntax error"));
		assertEquals(after, MissingSemicolon.place(text, 1,
				"'float' : Syntax error:  syntax error"));
		assertEquals(-1, MissingSemicolon.place(text, 1,
				"'' : syntax error"));
		assertEquals(-1, MissingSemicolon.place(text, 1,
				"'float' : undeclared identifier"));
	}

	@Test
	public void placesBeforeADeclarationAnAssignmentOrABrace() {
		String text = "\tfloat charge = clamp(battery, 0.0, 1.0)\n" +
				"\t// Read.\n" +
				"\tfloat charging = float(powerConnected);\n";
		assertEquals(text.indexOf(")\n") + 1,
				MissingSemicolon.place(text, 2, "'float' : syntax error"));
		String line = "{ float a = 1.0 float b = 2.0;";
		assertEquals(line.indexOf(" float b"),
				MissingSemicolon.place(line, 0, "'float' : syntax error"));
		String assign = "\tcolor = mix(color, hole, -0.5)\n" +
				"\tshaderToyColor = color;\n";
		assertEquals(assign.indexOf("\n"),
				MissingSemicolon.place(assign, 1,
						"'shaderToyColor' : syntax error"));
		String step = "float c = a + b\nuv *= c;";
		assertEquals(step.indexOf("\n"),
				MissingSemicolon.place(step, 1, "'uv' : syntax error"));
		String brace = "color = vec4(0.0)\n}";
		assertEquals(brace.indexOf("\n"),
				MissingSemicolon.place(brace, 1, "'}' : syntax error"));
	}

	@Test
	public void noPlaceWhereAnOperatorOrAParenthesisIsMissing() {
		assertEquals(-1, place("vec2 uv = gl_FragCoord.xy mx;", "mx"));
		assertEquals(-1, place("float t = time 2.0;", "2.0"));
		assertEquals(-1, place("vec3 c = color vec3(0.5);", "vec3"));
		assertEquals(-1, place("0.25 + 0.5 sin(time));", "sin"));
		assertEquals(-1, place("bool b = a b == c;", "b"));
		assertEquals(-1, place(
				"float mx = max(resolution.x, resolution.y;", ";"));
		// Two places: not sure which.
		assertEquals(-1, place(
				"{ float a = 1.0 float b = 2.0 float c = a;", "float"));
	}

	private static int place(String line, String token) {
		return MissingSemicolon.place(line, 0,
				"'" + token + "' : syntax error");
	}
}

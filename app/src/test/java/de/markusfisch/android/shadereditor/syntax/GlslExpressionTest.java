package de.markusfisch.android.shadereditor.syntax;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import de.markusfisch.android.shadereditor.syntax.Knowledge.Kind;
import de.markusfisch.android.shadereditor.syntax.Knowledge.Symbol;

public class GlslExpressionTest {
	// What the names of the expressions below mean: read from
	// declarations, as a knowledge would tell them.
	private static final GlslExpression.Names NAMES = names(
			"float t; int i; uint n; bool on; vec2 uv; vec3 p; vec4 colour;",
			"mat3 m; float steps[4]; Ray ray; Ray rays[2]; sampler2D noise;",
			"struct Ray { vec3 origin; vec3 dir; float hit[2]; Hit last; };",
			"struct Hit { float d; vec3 n; };",
			"float map(vec3 q);",
			"vec3 map(vec2 q);",
			"Ray march(Ray r, float far);",
			"vec2 pair(float a, float b);",
			"float pair(int a);");

	@Test
	public void valuesAndWhatStandsBehindThem() {
		assertEquals("float", type("t"));
		assertEquals("vec3", type("p"));
		assertEquals("float", type("1.0"));
		assertEquals("int", type("3"));
		assertEquals("bool", type("true"));
		assertEquals("vec2", type("p.xy"));
		assertEquals("float", type("colour.a"));
		assertEquals("vec3", type("ray.origin"));
		assertEquals("float", type("ray.dir.z"));
		assertEquals("vec3", type("ray.last.n"));
		assertEquals("vec2", type("rays[i].last.n.xz"));
		assertEquals("float", type("steps[2]"));
		assertEquals("float", type("ray.hit[1]"));
		assertEquals("vec3", type("m[0]"));
		assertEquals("float", type("m[0][1]"));
		assertEquals("int", type("steps.length()"));
		assertEquals("float", type("(t)"));
		assertEquals("vec2", type("(p).xy"));
		assertEquals("int", type("i++"));
	}

	@Test
	public void operators() {
		assertEquals("vec3", type("p * 2.0"));
		assertEquals("vec3", type("t * p + p"));
		assertEquals("vec3", type("p + m * p"));
		assertEquals("vec3", type("-p"));
		assertEquals("float", type("t / (1.0 + t)"));
		assertEquals("bool", type("t < 1.0"));
		assertEquals("bool", type("i >= 2 && on"));
		assertEquals("bool", type("p == p || !on"));
		assertEquals("bool", type("!on"));
		assertEquals("int", type("i << 2"));
		assertEquals("int", type("i & 3"));
		assertEquals("vec3", type("on ? p : p * 2.0"));
		assertEquals("float", type("t < 0.5 ? t : 1.0 - t"));
		assertEquals("float", type("t = 2.0"));
		assertEquals("vec3", type("p += p * t"));
		assertEquals("float", type("p, t"));
		// An operator with nothing behind it yet.
		assertEquals("vec3", type("p * "));
		assertEquals("float", type("0.5 + "));
	}

	@Test
	public void callsAndConstructors() {
		assertEquals("vec3", type("vec3(1.0)"));
		assertEquals("vec4", type("vec4(p, 1.0)"));
		assertEquals("float[2]", type("float[2](1.0, 2.0)"));
		assertEquals("vec2", type("vec4(p, 1.0).xy"));
		assertEquals("Ray", type("Ray(p, p, steps, Hit(t, p))"));
		assertEquals("vec3", type("Ray(p, p).dir"));
		// Its own functions, the form by the types of the arguments.
		assertEquals("float", type("map(p)"));
		assertEquals("vec3", type("map(uv)"));
		assertEquals("vec3", type("map(p.xy)"));
		assertEquals("vec3", type("march(ray, 9.0).origin"));
		assertEquals("vec2", type("pair(t, 1.0)"));
		assertEquals("float", type("pair(i)"));
		// No form by what is known of the arguments, and forms that
		// differ in what they return.
		assertNull(type("map(unknown)"));
		assertEquals("Ray", type("march(unknown, t)"));
		// Built-in functions.
		assertEquals("vec3", type("normalize(p)"));
		assertEquals("float", type("length(p - p)"));
		assertEquals("vec3", type("mix(p, p, 0.5)"));
		assertEquals("vec4", type("texture2D(noise, uv)"));
		assertEquals("vec3", type("texture2D(noise, uv).rgb * t"));
		assertEquals("float", type("map(normalize(p) * t)"));
		assertEquals("float", type("clamp(dot(p, p), 0.0, 1.0)"));
	}

	@Test
	public void whatCannotBeTold() {
		for (String text : new String[]{"", "unknown", "unknown.x",
				"p.q.r", "ray.missing", "t.x", "* p", "(", ")",
				"p + uv", "f(", "ray[0]", "1.0.x", "?", "p ? ", "= =",
				"return p", "[p]", "p.", ".x"}) {
			assertNull(text, type(text));
		}
		// Brackets deeper than anyone writes are not read, and a
		// hundred thousand of them take no time and no stack.
		StringBuilder deep = new StringBuilder();
		for (int i = 0; i < 100000; ++i) {
			deep.append('(');
		}
		assertNull(type(deep + "t"));
		StringBuilder calls = new StringBuilder();
		for (int i = 0; i < 1000; ++i) {
			calls.append("sin(");
		}
		assertNull(type(calls + "t"));
		StringBuilder choices = new StringBuilder("t");
		for (int i = 0; i < 1000; ++i) {
			choices.append(" ? t");
		}
		type(choices.toString());
	}

	@Test
	public void theArgumentsOfACall() {
		GlslStatement s = GlslStatementTest.statement(
				"mix(p, vec3(t, 1.0, 0.0), 0.5 + ");
		assertEquals(Arrays.asList("vec3", "vec3", "float"),
				GlslExpression.arguments(s, 2, s.size(), NAMES));
		s = GlslStatementTest.statement("map(");
		assertEquals(List.of(), GlslExpression.arguments(s, 2, s.size(),
				NAMES));
		s = GlslStatementTest.statement("map(unknown, p");
		assertEquals(Arrays.asList(null, "vec3"),
				GlslExpression.arguments(s, 2, s.size(), NAMES));
	}

	@Test
	public void whereAValueStarts() {
		assertEquals("ray", value("x = ray|.dir"));
		assertEquals("rays[i].last", value("x = t * rays[i].last|.n"));
		assertEquals("normalize(p)", value("x = t + normalize(p)|.x"));
		assertEquals("vec4(p, 1.0)", value("return vec4(p, 1.0)|.xy"));
		assertEquals("(p + p)", value("f((p + p)|.x"));
		assertEquals("m[0]", value("m[0]|.x"));
		assertEquals("march(ray, t).last", value("a + march(ray, t).last|"));
		assertEquals("", value("x = |"));
		assertEquals("", value("|"));
	}

	private static String value(String text) {
		int at = text.indexOf('|');
		GlslStatement s = GlslStatementTest.statement(text.substring(0, at));
		int start = GlslExpression.valueStart(s, s.size());
		return start < s.size() ? text.substring(s.start(start), at) : "";
	}

	@Test
	public void theFormThatTakesTheArguments() {
		List<Symbol> forms = NAMES.functions("map");
		assertEquals("vec3", GlslExpression.form(forms, Arrays.asList("vec2"),
				true).type());
		assertEquals("float", GlslExpression.form(forms, List.of(), false)
				.type());
		assertNull(GlslExpression.form(forms, List.of(), true));
		assertNull(GlslExpression.form(forms, Arrays.asList("float"),
				false));
		forms = NAMES.functions("pair");
		assertEquals("vec2", GlslExpression.form(forms, Arrays.asList(
				"float"), false).type());
		assertEquals("float", GlslExpression.form(forms, Arrays.asList(
				"int"), false).type());
		assertEquals(Arrays.asList("float", "float"),
				GlslExpression.parameters(forms.get(0)));
	}

	private static String type(String text) {
		GlslStatement s = GlslStatementTest.statement(text);
		return GlslExpression.type(s, 0, s.size(), NAMES);
	}

	// The names the declarations in lines declare.
	static GlslExpression.Names names(String... lines) {
		Map<String, String> variables = new HashMap<>();
		Map<String, List<Symbol>> functions = new HashMap<>();
		Map<String, Symbol> structs = new HashMap<>();
		for (String line : lines) {
			for (String text : line.contains("{") ? new String[]{line}
					: line.split(";")) {
				GlslStatement s = GlslStatementTest.statement(text.trim());
				int size = s.size();
				if (size > 0 && s.is(size - 1, ';')) {
					--size;
				}
				List<GlslSymbols.Draft> out = new ArrayList<>();
				GlslSymbols.of(s, size, 0, null, out);
				for (GlslSymbols.Draft draft : out) {
					Symbol symbol = draft.symbol();
					if (symbol.kind() == Kind.FUNCTION) {
						functions.computeIfAbsent(symbol.name(),
								k -> new ArrayList<>()).add(symbol);
					} else if (symbol.kind() == Kind.TYPE) {
						structs.put(symbol.name(), symbol);
					} else {
						variables.put(symbol.name(), symbol.type());
					}
				}
			}
		}
		return new GlslExpression.Names() {
			@Override
			public String variable(String name) {
				return variables.get(name);
			}

			@Override
			public List<Symbol> functions(String name) {
				List<Symbol> forms = functions.get(name);
				return forms == null ? List.of() : forms;
			}

			@Override
			public Symbol struct(String name) {
				return structs.get(name);
			}
		};
	}
}

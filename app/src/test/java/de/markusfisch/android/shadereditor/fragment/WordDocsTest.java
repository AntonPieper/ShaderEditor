package de.markusfisch.android.shadereditor.fragment;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.List;

import de.markusfisch.android.shadereditor.syntax.GlslKnowledge;
import de.markusfisch.android.shadereditor.syntax.Knowledge;

/** What Look up and the row's fix add for the app's uniforms. */
public class WordDocsTest {
	@Test
	public void addsWhatAddUniformAdds() {
		assertEquals("uniform float time;",
				WordDocs.statement(WordDocs.uniform("time")));
		assertEquals("uniform sampler2D backbuffer;",
				WordDocs.statement(WordDocs.uniform("backbuffer")));
		assertNull(WordDocs.uniform("smoothstep"));
	}

	@Test
	public void fixesAnUndeclaredUniformOfTheApp() {
		assertEquals("time", WordDocs.undeclaredUniform(List.of(
				"'time' : undeclared identifier"), Knowledge.NONE).getName());
		assertEquals("touch", WordDocs.undeclaredUniform(List.of(
				"'x' : syntax error",
				"use of undeclared identifier 'touch'"), Knowledge.NONE)
				.getName());
		// Not one of the app's, not undeclared, or declared after all.
		assertNull(WordDocs.undeclaredUniform(List.of(
				"'timeScale' : undeclared identifier"), Knowledge.NONE));
		assertNull(WordDocs.undeclaredUniform(List.of(
				"'time' : redefinition"), Knowledge.NONE));
		String text = "float time;\n";
		assertNull(WordDocs.undeclaredUniform(List.of(
						"'time' : undeclared identifier"),
				GlslKnowledge.of(text)));
		// A parameter of the first function is no declaration.
		assertEquals("time", WordDocs.undeclaredUniform(List.of(
						"'time' : undeclared identifier"),
				GlslKnowledge.of("float f(float time) { return time; }\n" +
						"void main() { gl_FragColor = vec4(time); }\n"))
				.getName());
	}
}

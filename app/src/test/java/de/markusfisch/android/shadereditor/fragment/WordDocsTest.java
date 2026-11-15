package de.markusfisch.android.shadereditor.fragment;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.List;

import de.markusfisch.android.shadereditor.syntax.GlslLexer;
import de.markusfisch.android.shadereditor.syntax.Outline;

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
				"'time' : undeclared identifier"), Outline.NONE).getName());
		assertEquals("touch", WordDocs.undeclaredUniform(List.of(
				"'x' : syntax error",
				"use of undeclared identifier 'touch'"), Outline.NONE)
				.getName());
		// Not one of the app's, not undeclared, or declared after all.
		assertNull(WordDocs.undeclaredUniform(List.of(
				"'timeScale' : undeclared identifier"), Outline.NONE));
		assertNull(WordDocs.undeclaredUniform(List.of(
				"'time' : redefinition"), Outline.NONE));
		String text = "float time;\n";
		assertNull(WordDocs.undeclaredUniform(List.of(
						"'time' : undeclared identifier"),
				Outline.of(GlslLexer.lex(text), text)));
	}
}

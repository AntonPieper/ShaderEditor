package de.markusfisch.android.shadereditor.fragment;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** What Look up adds for the app's uniforms. */
public class WordDocsTest {
	@Test
	public void addsWhatAddUniformAdds() {
		assertEquals("uniform float time;",
				WordDocs.statement(WordDocs.uniform("time")));
		assertEquals("uniform sampler2D backbuffer;",
				WordDocs.statement(WordDocs.uniform("backbuffer")));
		assertNull(WordDocs.uniform("smoothstep"));
	}
}

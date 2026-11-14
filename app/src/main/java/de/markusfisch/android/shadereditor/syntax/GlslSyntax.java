package de.markusfisch.android.shadereditor.syntax;

import java.util.List;

/**
 * GLSL as the editor treats it.
 */
public final class GlslSyntax implements Syntax {
	public static final GlslSyntax INSTANCE = new GlslSyntax();

	private GlslSyntax() {
	}

	@Override
	public int lexLine(CharSequence line, boolean breakFollows, int state,
			LineTokens out) {
		return GlslLexer.lexLine(line, breakFollows, state, out);
	}

	@Override
	public Outline.Builder outline(int maxDeclarations, int maxNameChars) {
		return Outline.builder(maxDeclarations, maxNameChars);
	}

	@Override
	public void complete(LexedLine line, int column, int lineStart,
			Outline declared, boolean current, List<String> extraWords,
			Offer offer) {
		GlslCompletion.Completions completions = GlslCompletion.atColumn(
				line, column, lineStart, GlslCompletion.Names.of(declared),
				current, extraWords);
		offer.offer(completions.words(), completions.position());
	}

	@Override
	public Indentation indentation() {
		return Indentation.C;
	}
}

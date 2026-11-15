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
	public Knowledge.Builder knowledge(int maxSymbols, int maxChars) {
		return GlslKnowledge.builder(maxSymbols, maxChars);
	}

	@Override
	public void complete(LexedLine line, int column, int lineStart,
			Knowledge known, boolean current, List<String> extraWords,
			Offer offer) {
		// Knowledge of another language declares nothing GLSL completes.
		GlslCompletion.Completions completions = GlslCompletion.atColumn(
				line, column, lineStart, GlslCompletion.Names.of(
						known instanceof GlslKnowledge glsl
								? glsl
								: GlslKnowledge.NONE),
				current, extraWords);
		offer.offer(completions.words(), completions.position());
	}

	@Override
	public Call call(Lines lines, int line, int column) {
		return GlslCompletion.call(lines, line, column);
	}

	@Override
	public Indentation indentation() {
		return Indentation.C;
	}
}

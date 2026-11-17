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
		complete(line, column, lineStart, known, current, extraWords,
				Knowledge.Scope.NONE, offer);
	}

	@Override
	public void complete(LexedLine line, int column, int lineStart,
			Knowledge known, boolean current, List<String> extraWords,
			Knowledge.Scope outside, Offer offer) {
		// Knowledge of another language declares nothing GLSL completes.
		GlslCompletion.Completions completions = GlslCompletion.atColumn(
				line, column, lineStart, GlslCompletion.Names.of(
						known instanceof GlslKnowledge glsl
								? glsl
								: GlslKnowledge.NONE),
				current, extraWords, outside);
		offer.offer(completions.words(), completions.position());
	}

	@Override
	public Call call(Lines lines, int line, int column) {
		return GlslCompletion.call(lines, line, column);
	}

	@Override
	public List<String> argumentTypes(Lines lines, Call call, int line,
			int column, int lineStart, Knowledge known, boolean current,
			Knowledge.Scope outside) {
		return GlslCompletion.argumentTypes(lines, call, line, column,
				lineStart, known instanceof GlslKnowledge glsl ? glsl
						: GlslKnowledge.NONE, current, outside);
	}

	@Override
	public Knowledge.Answer uses(List<Program> programs,
			Knowledge.Symbol symbol, String name) {
		return GlslUses.of(programs, symbol, name);
	}

	@Override
	public boolean isName(String word) {
		int length = word.length();
		if (length == 0 || word.startsWith("gl_") || word.contains("__") ||
				Character.isDigit(word.charAt(0))) {
			return false;
		}
		for (int i = 0; i < length; ++i) {
			char c = word.charAt(i);
			if (c != '_' && !(c >= 'a' && c <= 'z') &&
					!(c >= 'A' && c <= 'Z') && !(c >= '0' && c <= '9')) {
				return false;
			}
		}
		return GlslWords.style(word, 0, length) == Style.IDENTIFIER;
	}

	@Override
	public Indentation indentation() {
		return Indentation.C;
	}
}

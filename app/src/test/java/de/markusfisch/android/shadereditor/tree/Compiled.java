package de.markusfisch.android.shadereditor.tree;

/** A whole text run through the lexer and the preprocessor, for tests. */
final class Compiled {
	final Symbols symbols = new Symbols();
	final RawTokens raw = new RawTokens();
	final CompiledTokens tokens = new CompiledTokens();
	final Environment env = new Environment();
	final Preprocessor preprocessor;
	/** How often the preprocessor stopped where an item may end. */
	int stops;

	Compiled(String text) {
		preprocessor = new Preprocessor(new Lexer(text, 0, symbols, raw), raw,
				tokens, env, symbols);
		while (preprocessor.run()) {
			++stops;
		}
	}

	/** The compiled tokens as they are spelled, a space between them. */
	String text() {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < tokens.size; ++i) {
			sb.append(i > 0 ? " " : "").append(symbols.name(tokens.symbol[i]));
		}
		return sb.toString();
	}

	/** The raw tokens with a flag, as they are spelled. */
	String flagged(int flag) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < raw.size; ++i) {
			if (raw.is(i, flag)) {
				sb.append(sb.length() > 0 ? " " : "")
						.append(symbols.name(raw.symbol[i]));
			}
		}
		return sb.toString();
	}

	boolean doubts(int why) {
		for (int i = 1; i < preprocessor.doubts.size; i += 2) {
			if (preprocessor.doubts.at[i] == why) {
				return true;
			}
		}
		return false;
	}
}

package de.markusfisch.android.shadereditor.syntax;

import java.util.Collections;
import java.util.List;

/**
 * Text without a language: nothing is coloured or completed, and a new
 * line keeps the indentation of the line before.
 */
public final class PlainSyntax implements Syntax {
	public static final PlainSyntax INSTANCE = new PlainSyntax();

	static final Indentation INDENTATION = new Indentation(null, "", "",
			"");

	private PlainSyntax() {
	}

	@Override
	public int lexLine(CharSequence line, boolean breakFollows, int state,
			LineTokens out) {
		out.clear();
		return TEXT_START;
	}

	@Override
	public Outline.Builder outline(int maxDeclarations, int maxNameChars) {
		return null;
	}

	@Override
	public void complete(LexedLine line, int column, int lineStart,
			Outline declared, boolean current, List<String> extraWords,
			Offer offer) {
		offer.offer(Collections.emptyList(), 0);
	}

	@Override
	public Indentation indentation() {
		return INDENTATION;
	}
}

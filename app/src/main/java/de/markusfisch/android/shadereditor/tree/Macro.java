package de.markusfisch.android.shadereditor.tree;

import java.util.Arrays;

/**
 * What a #define line defines. The tokens of its text are the raw
 * tokens from {@link #bodyAt} on in the piece of text that has the
 * line; that place is only good together with that piece.
 */
final class Macro {
	final int name;
	/** Whether it takes arguments: a "(" right behind its name. */
	final boolean function;
	final int[] parameters;
	/** The symbols and kinds of the tokens of its text. */
	final int[] body;
	final byte[] kinds;
	final int bodyAt;
	/** Whether its text has "#" or "##", which GLSL ES does not have. */
	final boolean pastes;
	/**
	 * Whether this is only a guess of what the name is: a conditional
	 * that could not be decided defines or undefines it.
	 */
	final boolean guess;

	Macro(int name, boolean function, int[] parameters, int[] body,
			byte[] kinds, int bodyAt, boolean guess) {
		this.name = name;
		this.guess = guess;
		this.function = function;
		this.parameters = parameters;
		this.body = body;
		this.kinds = kinds;
		this.bodyAt = bodyAt;
		boolean pastes = false;
		for (int i = 0; body != null && i < body.length; ++i) {
			pastes |= body[i] == Words.HASH || body[i] == Words.PASTE;
		}
		this.pastes = pastes;
	}

	/** What #undef leaves of a name: nothing; it has no text. */
	static Macro undefined(int name, boolean guess) {
		return new Macro(name, false, null, null, null, -1, guess);
	}

	/** The same, as a guess. */
	Macro guessed() {
		return new Macro(name, function, parameters, body, kinds, bodyAt, true);
	}

	boolean isDefined() {
		return body != null;
	}

	/** Which parameter a symbol names, or -1. */
	int parameter(int symbol) {
		for (int i = 0; i < parameters.length; ++i) {
			if (parameters[i] == symbol) {
				return i;
			}
		}
		return -1;
	}

	/** Whether other expands as this does, wherever each is written. */
	boolean expandsAs(Macro other) {
		return other != null && name == other.name &&
				guess == other.guess && function == other.function &&
				Arrays.equals(parameters, other.parameters) &&
				Arrays.equals(body, other.body);
	}
}

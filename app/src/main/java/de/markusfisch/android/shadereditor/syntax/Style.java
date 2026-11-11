package de.markusfisch.android.shadereditor.syntax;

/**
 * The colour class of a token. The editor maps each style to a colour.
 */
public enum Style {
	INVALID,
	KEYWORD,
	DIRECTIVE,
	PRIMITIVE,
	TYPE_VECTOR,
	TYPE_MATRIX,
	ATOMIC_COUNTER,
	TYPE_SAMPLER,
	TYPE_IMAGE,
	TYPE,
	IDENTIFIER,
	BUILTIN_FUNCTION,
	FIELD_SELECTION,
	NUMBER,
	BOOL,
	OPERATOR,
	PRECISION,
	COMMENT,
	CONTROL_FLOW,
	TYPE_QUALIFIER,
	STRING
}

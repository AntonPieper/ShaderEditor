package de.markusfisch.android.shadereditor.opengl;

import androidx.annotation.NonNull;

public enum GlslType {
	INT("int"),
	FLOAT("float"),
	VEC2("vec2"),
	VEC3("vec3");

	@NonNull
	private final String keyword;

	GlslType(@NonNull String keyword) {
		this.keyword = keyword;
	}

	@NonNull
	public String getKeyword() {
		return keyword;
	}
}

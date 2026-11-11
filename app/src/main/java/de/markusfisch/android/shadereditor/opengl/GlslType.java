package de.markusfisch.android.shadereditor.opengl;

import androidx.annotation.NonNull;

public enum GlslType {
	INT("int"),
	FLOAT("float"),
	VEC2("vec2"),
	VEC3("vec3"),
	VEC4("vec4"),
	MAT2("mat2"),
	MAT3("mat3"),
	SAMPLER_2D("sampler2D"),
	SAMPLER_EXTERNAL_OES("samplerExternalOES");

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

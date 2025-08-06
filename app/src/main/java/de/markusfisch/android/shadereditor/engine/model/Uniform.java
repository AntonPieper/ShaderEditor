package de.markusfisch.android.shadereditor.engine.model;

import androidx.annotation.NonNull;

import org.jetbrains.annotations.Contract;

public sealed interface Uniform {
	record FloatScalar(@NonNull float[] value) implements Uniform {
	}

	record FloatVec2(@NonNull float[] value) implements Uniform {
	}

	record FloatVec3(@NonNull float[] value) implements Uniform {
	}

	record FloatVec4(@NonNull float[] value) implements Uniform {
	}

	record FloatMat2(@NonNull float[] value) implements Uniform {
	}

	record FloatMat3(@NonNull float[] value) implements Uniform {
	}

	record FloatMat4(@NonNull float[] value) implements Uniform {
	}

	record IntScalar(@NonNull int[] value) implements Uniform {
	}

	@NonNull
	@Contract("_ -> new")
	static Uniform.FloatScalar floatScalar(float value) {
		return new Uniform.FloatScalar(new float[]{value});
	}

	@NonNull
	@Contract("_ -> new")
	static Uniform.FloatVec2 floatVec2(@NonNull float[] value) {
		return new Uniform.FloatVec2(value);
	}

	@NonNull
	@Contract("_ -> new")
	static Uniform.FloatVec3 floatVec3(@NonNull float[] value) {
		return new Uniform.FloatVec3(value);
	}

	@NonNull
	@Contract("_ -> new")
	static Uniform.FloatVec4 floatVec4(@NonNull float[] value) {
		return new Uniform.FloatVec4(value);
	}

	@NonNull
	@Contract("_ -> new")
	static Uniform.IntScalar intScalar(int value) {
		return new Uniform.IntScalar(new int[]{value});
	}

	@NonNull
	@Contract("_ -> new")
	static Uniform.FloatMat2 floatMat2(float[] value) {
		return new Uniform.FloatMat2(value);
	}

	@NonNull
	@Contract("_ -> new")
	static Uniform.FloatMat3 floatMat3(float[] value) {
		return new Uniform.FloatMat3(value);
	}

	@NonNull
	@Contract("_ -> new")
	static Uniform.FloatMat4 floatMat4(float[] value) {
		return new Uniform.FloatMat4(value);
	}
}

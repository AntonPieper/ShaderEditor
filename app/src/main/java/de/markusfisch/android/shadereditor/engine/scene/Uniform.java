package de.markusfisch.android.shadereditor.engine.scene;

import androidx.annotation.NonNull;

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

	record Sampler2D(@NonNull Image2D image) implements Uniform {
	}
}

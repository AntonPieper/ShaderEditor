package de.markusfisch.android.shadereditor.engine.scene;

import androidx.annotation.NonNull;

public sealed interface Uniform {

	record FloatScalar(@NonNull float[] value) implements Uniform {
		public FloatScalar(float value) {
			this(new float[]{value});
		}
	}

	record FloatVec2(@NonNull float[] value) implements Uniform {
		public FloatVec2(float x, float y) {
			this(new float[]{x, y});
		}
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
		public IntScalar(int value) {
			this(new int[]{value});
		}
	}

	record Sampler2D(@NonNull TextureSource source) implements Uniform {
	}
}
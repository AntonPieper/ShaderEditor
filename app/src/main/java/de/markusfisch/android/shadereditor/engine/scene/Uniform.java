package de.markusfisch.android.shadereditor.engine.scene;

import androidx.annotation.NonNull;

import de.markusfisch.android.shadereditor.engine.asset.TextureAsset;
import de.markusfisch.android.shadereditor.engine.asset.TextureParameters;

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

	record Sampler(
			@NonNull TextureAsset texture,
			@NonNull TextureParameters params
	) implements Uniform {
	}
}

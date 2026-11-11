package de.markusfisch.android.shadereditor.opengl;

import androidx.annotation.NonNull;

interface UniformOutput {
	void setInt(int value);

	void setFloat(float value);

	void setVec2(float x, float y);

	void setVec3Array(@NonNull float[] values, int count);
}

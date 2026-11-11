package de.markusfisch.android.shadereditor.opengl;

import androidx.annotation.NonNull;

interface UniformOutput {
	void setInt(int value);

	void setFloat(float value);

	void setVec2(float x, float y);

	void setVec3(float x, float y, float z);

	void setVec4(float x, float y, float z, float w);

	void setVec3Array(@NonNull float[] values, int count);

	void setMat2(boolean transpose, @NonNull float[] values);

	void setMat3(boolean transpose, @NonNull float[] values);
}

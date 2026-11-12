package de.markusfisch.android.shadereditor.activity.managers;

/** The drawer's list of shaders as the activity and its managers use it. */
public interface ShaderList {
	void loadShadersAsync();

	void setSelectedShaderId(long id);

	void destroy();
}

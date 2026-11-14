package de.markusfisch.android.shadereditor.activity.managers;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Nothing is open: where the shaders are files, before the library is
 * known, between two files, and in a library without one. There is no
 * text, so nothing is typed, saved, copied, deleted, shared or run,
 * and nothing is offered. It is a state of its own, so that no shader
 * of the database has to stand in for it.
 */
public final class NothingOpen implements OpenItem {
	@NonNull
	private final String title;

	/** @param title what the toolbar says while nothing is open */
	public NothingOpen(@NonNull String title) {
		this.title = title;
	}

	@NonNull
	@Override
	public String getTitle() {
		return title;
	}

	@Nullable
	@Override
	public String getSubtitle() {
		return null;
	}

	@Override
	public boolean isModified() {
		return false;
	}

	@Override
	public void onTextModified() {
	}

	@Override
	public float getQuality() {
		return 1f;
	}

	@Override
	public void setQuality(float quality) {
	}

	@Override
	public boolean can(@NonNull Action action) {
		return false;
	}

	@Override
	public boolean offers(@NonNull Action action) {
		return false;
	}

	@Override
	public boolean hasText() {
		return false;
	}

	@Override
	public void save(boolean explicit) {
	}

	@Override
	public void saveCopy() {
	}

	@Override
	public void delete() {
	}

	@Override
	public void share() {
	}

	@Override
	public boolean isWallpaper() {
		return false;
	}

	@Override
	public void setAsWallpaper() {
	}
}

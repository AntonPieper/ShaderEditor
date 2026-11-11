package de.markusfisch.android.shadereditor.opengl;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;

public final class UniformDef {
	interface Writer {
		void write(@NonNull FrameInputs in, @NonNull UniformOutput out);
	}

	@NonNull
	private final String name;
	@NonNull
	private final GlslType type;
	private final int arrayLength;
	@StringRes
	private final int description;
	@NonNull
	private final Writer writer;

	UniformDef(
			@NonNull String name,
			@NonNull GlslType type,
			int arrayLength,
			@StringRes int description,
			@NonNull Writer writer) {
		this.name = name;
		this.type = type;
		this.arrayLength = arrayLength;
		this.description = description;
		this.writer = writer;
	}

	@NonNull
	public String getName() {
		return name;
	}

	@NonNull
	public GlslType getType() {
		return type;
	}

	@NonNull
	public String getDeclarator() {
		return arrayLength > 0 ? name + "[" + arrayLength + "]" : name;
	}

	@StringRes
	public int getDescription() {
		return description;
	}

	@NonNull
	Writer getWriter() {
		return writer;
	}
}

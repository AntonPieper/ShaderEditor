package de.markusfisch.android.shadereditor.opengl;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

public final class UniformDef {
	interface Writer {
		void write(@NonNull FrameInputs in, @NonNull UniformOutput out);
	}

	interface SourceWriter<S extends UniformSource> {
		void write(@NonNull S source, @NonNull UniformOutput out);
	}

	private interface Body {
		void write(
				@NonNull FrameInputs in,
				@Nullable UniformSource source,
				@NonNull UniformOutput out);
	}

	@NonNull
	private final String name;
	@NonNull
	private final GlslType type;
	private final int arrayLength;
	@StringRes
	private final int description;
	@Nullable
	private final UniformSource.Key<?> source;
	@NonNull
	private final Body body;

	UniformDef(
			@NonNull String name,
			@NonNull GlslType type,
			int arrayLength,
			@StringRes int description,
			@NonNull Writer writer) {
		this(name, type, arrayLength, description, null,
				(in, source, out) -> writer.write(in, out));
	}

	<S extends UniformSource> UniformDef(
			@NonNull String name,
			@NonNull GlslType type,
			int arrayLength,
			@StringRes int description,
			@NonNull UniformSource.Key<S> source,
			@NonNull SourceWriter<S> writer) {
		this(name, type, arrayLength, description, source,
				(in, s, out) -> writer.write(source.cast(s), out));
	}

	private UniformDef(
			@NonNull String name,
			@NonNull GlslType type,
			int arrayLength,
			@StringRes int description,
			@Nullable UniformSource.Key<?> source,
			@NonNull Body body) {
		this.name = name;
		this.type = type;
		this.arrayLength = arrayLength;
		this.description = description;
		this.source = source;
		this.body = body;
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

	@Nullable
	UniformSource.Key<?> getSource() {
		return source;
	}

	void write(
			@NonNull FrameInputs in,
			@Nullable UniformSource source,
			@NonNull UniformOutput out) {
		body.write(in, source, out);
	}
}

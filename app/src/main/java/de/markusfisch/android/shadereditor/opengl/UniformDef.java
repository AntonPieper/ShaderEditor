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
	// Null for a declaration only, which the engine never writes.
	@Nullable
	private final Body body;
	// The same builtin declared with another type, written its own way.
	@Nullable
	private final UniformDef alternative;

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

	UniformDef(
			@NonNull String name,
			@NonNull GlslType type,
			@StringRes int description,
			@Nullable UniformSource.Key<?> source) {
		this(name, type, 0, description, source, (Body) null);
	}

	private UniformDef(
			@NonNull String name,
			@NonNull GlslType type,
			int arrayLength,
			@StringRes int description,
			@Nullable UniformSource.Key<?> source,
			@Nullable Body body) {
		this(name, type, arrayLength, description, source, body, null);
	}

	private UniformDef(
			@NonNull String name,
			@NonNull GlslType type,
			int arrayLength,
			@StringRes int description,
			@Nullable UniformSource.Key<?> source,
			@Nullable Body body,
			@Nullable UniformDef alternative) {
		this.name = name;
		this.type = type;
		this.arrayLength = arrayLength;
		this.description = description;
		this.source = source;
		this.body = body;
		this.alternative = alternative;
	}

	// This builtin, that also takes a declaration of type, which writer
	// writes. The table and the uniform list show only this type.
	@NonNull
	UniformDef orAs(@NonNull GlslType type, @NonNull Writer writer) {
		return new UniformDef(name, this.type, arrayLength, description,
				source, body, new UniformDef(name, type, arrayLength,
				description, source, (in, s, out) -> writer.write(in, out)));
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

	boolean fits(@NonNull GlUniform uniform) {
		return fitting(uniform) != null;
	}

	// The entry that writes uniform: this, its alternative, or null when
	// the declaration fits neither.
	@Nullable
	UniformDef fitting(@NonNull GlUniform uniform) {
		if (fitsType(uniform)) {
			return this;
		}
		return alternative != null && alternative.fitsType(uniform)
				? alternative
				: null;
	}

	// A builtin of one value takes only a declaration of one value.
	private boolean fitsType(@NonNull GlUniform uniform) {
		return type.accepts(uniform.type()) && (arrayLength > 0 || uniform.size() == 1);
	}

	boolean isDeclarationOnly() {
		return body == null;
	}

	void write(
			@NonNull FrameInputs in,
			@Nullable UniformSource source,
			@NonNull UniformOutput out) {
		if (body == null) {
			throw new IllegalStateException(name + " is a declaration only");
		}
		body.write(in, source, out);
	}
}

package de.markusfisch.android.shadereditor.opengl;

import android.opengl.GLES20;
import android.opengl.GLES30;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;

import de.markusfisch.android.shadereditor.project.HostDirective.Format;

// What a context can do with the formats of targets, from its version
// and the extensions it names, never from its version alone. Whether a
// target of a format can be drawn into is only certain once its
// framebuffer is complete.
record TargetFormats(
		int glesVersion,
		boolean halfFloatTextures,
		boolean halfFloatRendering,
		boolean halfFloatFiltering) {
	// OES_texture_half_float.
	private static final int GL_HALF_FLOAT_OES = 0x8D61;

	@NonNull
	static TargetFormats of(int glesVersion, @NonNull String extensions) {
		var names = new HashSet<>(Arrays.asList(extensions.trim().split("\\s+")));
		boolean es3 = glesVersion >= 3;
		boolean textures = es3 || names.contains("GL_OES_texture_half_float");
		return new TargetFormats(glesVersion, textures,
				textures && (names.contains("GL_EXT_color_buffer_half_float") ||
						(es3 && names.contains("GL_EXT_color_buffer_float"))),
				textures && (es3 ||
						names.contains("GL_OES_texture_half_float_linear")));
	}

	// Why this context cannot draw into a target of this format, or
	// null.
	@Nullable
	String refusal(@NonNull Format format) {
		if (format == Format.RGBA8 || halfFloatRendering) {
			return null;
		}
		return "format=" + name(format) + " needs " + (halfFloatTextures
				? "a device that draws into half-float textures " +
				"(EXT_color_buffer_half_float" +
				(glesVersion >= 3 ? " or EXT_color_buffer_float)" : ")")
				: "half-float textures (OES_texture_half_float)") +
				", which this device does not have; use format=rgba8";
	}

	// Whether a target of this format can be read with a linear filter.
	boolean filters(@NonNull Format format) {
		return format == Format.RGBA8 || halfFloatFiltering;
	}

	// What a target of this format is allocated as.
	int internalFormat(@NonNull Format format) {
		return format == Format.RGBA16F && glesVersion >= 3
				? GLES30.GL_RGBA16F
				: GLES20.GL_RGBA;
	}

	int type(@NonNull Format format) {
		if (format == Format.RGBA8) {
			return GLES20.GL_UNSIGNED_BYTE;
		}
		return glesVersion >= 3 ? GLES30.GL_HALF_FLOAT : GL_HALF_FLOAT_OES;
	}

	@NonNull
	static String name(@NonNull Format format) {
		return format.name().toLowerCase(Locale.ROOT);
	}
}

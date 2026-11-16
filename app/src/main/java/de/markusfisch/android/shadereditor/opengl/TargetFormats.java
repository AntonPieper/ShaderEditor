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
// and the extensions it names, never from its version alone. A device
// may name an extension for drawing into half-float textures and still
// not draw into them, so the device tries that once per context
// (GlDevice.targetFormats) and a failure takes it back.
record TargetFormats(
		int glesVersion,
		boolean halfFloatTextures,
		boolean halfFloatFiltering,
		// The extension that names drawing into half-float textures, or
		// null.
		@Nullable String drawingExtension,
		// Whether drawing into a half-float texture failed all the same.
		boolean drawingFailed,
		// Whether OpenGL ES 2.0 makes mipmaps of a size that is not a
		// power of two (OES_texture_npot).
		boolean npotMipmaps) {
	// OES_texture_half_float.
	private static final int GL_HALF_FLOAT_OES = 0x8D61;

	@NonNull
	static TargetFormats of(int glesVersion, @NonNull String extensions) {
		var names = new HashSet<>(Arrays.asList(extensions.trim().split("\\s+")));
		boolean es3 = glesVersion >= 3;
		boolean textures = es3 || names.contains("GL_OES_texture_half_float");
		String drawing = null;
		if (textures && names.contains("GL_EXT_color_buffer_half_float")) {
			drawing = "EXT_color_buffer_half_float";
		} else if (es3 && names.contains("GL_EXT_color_buffer_float")) {
			drawing = "EXT_color_buffer_float";
		}
		return new TargetFormats(glesVersion, textures,
				textures && (es3 ||
						names.contains("GL_OES_texture_half_float_linear")),
				drawing, false, names.contains("GL_OES_texture_npot"));
	}

	// The same context, after drawing into a half-float texture failed.
	@NonNull
	TargetFormats withoutHalfFloatDrawing() {
		return new TargetFormats(glesVersion, halfFloatTextures,
				halfFloatFiltering, drawingExtension, true, npotMipmaps);
	}

	// Whether the context names drawing into half-float textures and did
	// not fail at it.
	boolean halfFloatRendering() {
		return drawingExtension != null && !drawingFailed;
	}

	// Why this context cannot draw into a target of this format, or
	// null.
	@Nullable
	String refusal(@NonNull Format format) {
		if (format == Format.RGBA8 || halfFloatRendering()) {
			return null;
		} else if (drawingFailed) {
			return "format=" + name(format) + " needs a device that " +
					"draws into half-float textures, which this device " +
					"cannot do: it names " + drawingExtension + ", but " +
					"drawing into a half-float texture failed; use " +
					"format=rgba8";
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

	// Why a target of this format and size has no mipmaps made after
	// each frame where an input reads it with them, or null when it has
	// them. OpenGL ES 3.0 makes them of every target: a target is drawn
	// into, and both formats filter there. 2.0 makes them only of a size
	// that is a power of two, unless the device names OES_texture_npot,
	// and of a half-float target only where half floats filter.
	@Nullable
	String noMipmaps(@NonNull Format format, int width, int height) {
		if (glesVersion >= 3) {
			return null;
		} else if (!filters(format)) {
			return "OpenGL ES 2.0 makes mipmaps of format=" +
					name(format) + " only where half floats filter " +
					"(OES_texture_half_float_linear), which this device " +
					"does not";
		} else if (!npotMipmaps &&
				!(isPowerOfTwo(width) && isPowerOfTwo(height))) {
			return "OpenGL ES 2.0 makes mipmaps only of a size that is a " +
					"power of two, and the target is " + width + "x" +
					height;
		}
		return null;
	}

	private static boolean isPowerOfTwo(int n) {
		return n > 0 && (n & (n - 1)) == 0;
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

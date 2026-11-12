package de.markusfisch.android.shadereditor.opengl;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.Map;

import de.markusfisch.android.shadereditor.project.ProjectPath;
import de.markusfisch.android.shadereditor.project.RenderPlan;

// The images of a plan, decoded off the GL thread from the bytes its
// preparation captured: RGBA8 with straight alpha and the channel values
// the file stores, without colour conversion, turned by their orientation
// so the first row is the bottom one.
final class DecodedImages implements TextureHandOver.Recyclable {
	record Image(int width, int height, @NonNull ByteBuffer rgba) {
	}

	@NonNull
	final Map<ProjectPath, Image> images;
	// Why an image could not be decoded, by path.
	@NonNull
	final Map<ProjectPath, String> problems;

	private DecodedImages(
			@NonNull Map<ProjectPath, Image> images,
			@NonNull Map<ProjectPath, String> problems) {
		this.images = images;
		this.problems = problems;
	}

	// Never throws: what fails is a problem of its image.
	@NonNull
	static DecodedImages decode(
			@NonNull Map<ProjectPath, byte[]> files,
			int maxPixels) {
		var images = new HashMap<ProjectPath, Image>();
		var problems = new HashMap<ProjectPath, String>();
		for (var file : files.entrySet()) {
			var path = file.getKey();
			try {
				var image = decode(path, file.getValue(), maxPixels, problems);
				if (image != null) {
					images.put(path, image);
				}
			} catch (OutOfMemoryError | RuntimeException e) {
				problems.put(path, "Cannot decode " + path + ": " + e);
			}
		}
		return new DecodedImages(images, problems);
	}

	@NonNull
	Map<ProjectPath, RenderPlan.Size> sizes() {
		var sizes = new HashMap<ProjectPath, RenderPlan.Size>();
		for (var image : images.entrySet()) {
			sizes.put(image.getKey(), new RenderPlan.Size(
					image.getValue().width(), image.getValue().height()));
		}
		return sizes;
	}

	// The buffers are plain memory that the collector frees.
	@Override
	public void recycle() {
	}

	// Returns null after adding why.
	@Nullable
	private static Image decode(
			@NonNull ProjectPath path,
			@NonNull byte[] bytes,
			int maxPixels,
			@NonNull Map<ProjectPath, String> problems) {
		var options = new BitmapFactory.Options();
		options.inJustDecodeBounds = true;
		BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
		long pixels = (long) options.outWidth * options.outHeight;
		if (options.outWidth < 1 || options.outHeight < 1) {
			problems.put(path, "Cannot decode " + path + ", it is not a " +
					"PNG or JPEG image");
			return null;
		} else if (pixels > maxPixels) {
			problems.put(path, path + " has " + pixels + " pixels, more " +
					"than the limit of " + maxPixels);
			return null;
		}
		options = new BitmapFactory.Options();
		options.inPreferredConfig = Bitmap.Config.ARGB_8888;
		options.inPremultiplied = false;
		options.inScaled = false;
		Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length,
				options);
		if (bitmap == null) {
			problems.put(path, "Cannot decode " + path);
			return null;
		}
		int width = bitmap.getWidth();
		int height = bitmap.getHeight();
		var stored = new int[width * height];
		try {
			if (bitmap.getConfig() != Bitmap.Config.ARGB_8888 ||
					bitmap.getRowBytes() != width * 4) {
				problems.put(path, "Cannot decode " + path + " to RGBA8");
				return null;
			}
			// The bytes as stored, unlike getPixels(), which converts to
			// sRGB. Native order keeps each pixel's bytes in place.
			bitmap.copyPixelsToBuffer(IntBuffer.wrap(stored));
		} finally {
			bitmap.recycle();
		}
		int orientation = ImageOrientation.of(bytes);
		var rgba = ByteBuffer.allocateDirect(stored.length * 4)
				.order(ByteOrder.nativeOrder());
		ImageOrientation.turn(stored, width, height, orientation,
				rgba.asIntBuffer());
		return ImageOrientation.swapsAxes(orientation)
				? new Image(height, width, rgba)
				: new Image(width, height, rgba);
	}
}

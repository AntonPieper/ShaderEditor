package de.markusfisch.android.shadereditor.opengl;

import android.graphics.Bitmap;
import android.opengl.GLES20;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

import de.markusfisch.android.shadereditor.project.ProjectDiagnostic;
import de.markusfisch.android.shadereditor.project.RenderPlan.Sampling;

final class ShaderRenderPipeline {
	record SurfaceProgram(
			@NonNull GlProgram program,
			int resolutionLocation,
			int frameUnit) {
		@NonNull
		static SurfaceProgram of(@NonNull GlDevice device, @NonNull GlProgram program) {
			return new SurfaceProgram(
					program,
					device.getUniformLocation(program, SURFACE_RESOLUTION),
					program.getTextureUnit(SURFACE_FRAME));
		}
	}

	private static final int THUMBNAIL_WIDTH = 144;
	private static final int THUMBNAIL_HEIGHT = 144;
	private static final String SURFACE_RESOLUTION = "resolution";
	private static final String SURFACE_FRAME = "frame";
	private static final String SURFACE_FRAGMENT_SHADER = """
			#ifdef GL_FRAGMENT_PRECISION_HIGH
			precision highp float;
			#else
			precision mediump float;
			#endif
			
			uniform vec2 resolution;
			uniform sampler2D frame;
			void main(void) {
				gl_FragColor = texture2D(frame,gl_FragCoord.xy / resolution.xy).rgba;
			}
			""";

	private final float[] thumbnailResolution =
			new float[]{THUMBNAIL_WIDTH, THUMBNAIL_HEIGHT};
	private final float[] drawResolution = new float[2];
	@NonNull
	private final GlDevice device;
	private final FullScreenQuad quad = new FullScreenQuad();
	@Nullable
	private GlFramebuffer thumbnailFramebuffer;
	@Nullable
	private GlTexture2D thumbnailTexture;
	@Nullable
	private SurfaceProgram surfaceProgram;
	// What the surface shows, for thumbnails.
	@Nullable
	private GlTexture2D presented;

	ShaderRenderPipeline(@NonNull GlDevice device) {
		this.device = device;
	}

	@NonNull
	List<ProjectDiagnostic> createContextResources() {
		ArrayList<ProjectDiagnostic> errors = new ArrayList<>();
		var surfaceResult = device.createProgram(
				FullScreenQuad.VERTEX_SHADER,
				SURFACE_FRAGMENT_SHADER,
				null);
		if (surfaceResult.getProgram() != null) {
			surfaceProgram = SurfaceProgram.of(device, surfaceResult.getProgram());
		}
		errors.addAll(surfaceResult.getInfoLog());
		thumbnailTexture = device.createTexture2D();
		device.applySampling(thumbnailTexture, Sampling.IMAGE);
		device.allocateTexture2D(
				thumbnailTexture,
				THUMBNAIL_WIDTH,
				THUMBNAIL_HEIGHT);
		thumbnailFramebuffer = device.createFramebuffer();
		device.attachColor(thumbnailFramebuffer, thumbnailTexture);
		int status = device.checkFramebufferStatus(thumbnailFramebuffer);
		if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
			errors.add(ProjectDiagnostic.error(
					"Thumbnail framebuffer incomplete: 0x" +
							Integer.toHexString(status)));
		}
		device.bindFramebuffer(null);
		return errors;
	}

	void discardContextResources() {
		surfaceProgram = null;
		presented = null;
		thumbnailFramebuffer = null;
		thumbnailTexture = null;
	}

	boolean hasSurfaceProgram() {
		return surfaceProgram != null;
	}

	// Stretches the texture over the surface, sampled as it is set up.
	void present(
			@Nullable GlTexture2D texture,
			int surfaceWidth,
			int surfaceHeight) {
		presented = texture;
		drawSurface(texture, surfaceWidth, surfaceHeight, null);
	}

	// Stretches the texture over the framebuffer, sampled as it is set up.
	void stretch(@NonNull GlTexture2D texture, @NonNull GlFramebuffer framebuffer) {
		drawSurface(texture, framebuffer.getWidth(), framebuffer.getHeight(),
				framebuffer);
	}

	// Of the frame presented last.
	@Nullable
	byte[] captureThumbnail() {
		if (thumbnailFramebuffer == null || presented == null ||
				!presented.isValid()) {
			return null;
		}

		drawSurface(
				presented,
				(int) thumbnailResolution[0],
				(int) thumbnailResolution[1],
				thumbnailFramebuffer);

		final int pixels = THUMBNAIL_WIDTH * THUMBNAIL_HEIGHT;
		final int[] rgba = new int[pixels];
		final IntBuffer buffer = IntBuffer.wrap(rgba);
		device.readPixels(
				0,
				0,
				THUMBNAIL_WIDTH,
				THUMBNAIL_HEIGHT,
				buffer);
		device.bindFramebuffer(null);

		int[] argb = new int[pixels];
		for (int y = 0; y < THUMBNAIL_HEIGHT; ++y) {
			for (int x = 0; x < THUMBNAIL_WIDTH; ++x) {
				int srcIdx = y * THUMBNAIL_WIDTH + x;
				int destIdx = (THUMBNAIL_HEIGHT - y - 1) * THUMBNAIL_WIDTH + x;
				int pixel = rgba[srcIdx];
				argb[destIdx] = 0xff000000
						| ((pixel << 16) & 0x00ff0000)
						| (pixel & 0x0000ff00)
						| ((pixel >> 16) & 0x000000ff);
			}
		}

		Bitmap bitmap = null;
		try (var out = new ByteArrayOutputStream()) {
			bitmap = Bitmap.createBitmap(
					argb,
					THUMBNAIL_WIDTH,
					THUMBNAIL_HEIGHT,
					Bitmap.Config.ARGB_8888);
			bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
			return out.toByteArray();
		} catch (OutOfMemoryError | IllegalArgumentException | IOException e) {
			return null;
		} finally {
			if (bitmap != null) {
				bitmap.recycle();
			}
		}
	}

	private void drawSurface(
			@Nullable GlTexture2D sourceTexture,
			int drawWidth,
			int drawHeight,
			@Nullable GlFramebuffer targetFramebuffer) {
		if (sourceTexture == null || surfaceProgram == null) {
			return;
		}

		device.bindFramebuffer(targetFramebuffer);
		device.setViewport(0, 0, drawWidth, drawHeight);
		drawResolution[0] = drawWidth;
		drawResolution[1] = drawHeight;
		GlProgram program = surfaceProgram.program();
		device.useProgram(program);
		device.uniform2fv(surfaceProgram.resolutionLocation(), 1, drawResolution);
		device.bindTexture(surfaceProgram.frameUnit(), sourceTexture);
		device.clear(GLES20.GL_COLOR_BUFFER_BIT);
		quad.draw();
	}
}

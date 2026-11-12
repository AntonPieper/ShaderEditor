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

	// The two targets the main pass draws into in turn.
	static final class Targets {
		private final GlFramebuffer[] framebuffers = new GlFramebuffer[2];
		private final GlTexture2D[] textures = new GlTexture2D[2];
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
	private final GlFramebuffer[] framebuffers = new GlFramebuffer[2];
	private final GlTexture2D[] targetTextures = new GlTexture2D[2];
	private int frontTarget;
	private int backTarget = 1;
	@Nullable
	private GlFramebuffer thumbnailFramebuffer;
	@Nullable
	private GlTexture2D thumbnailTexture;
	@Nullable
	private SurfaceProgram surfaceProgram;

	ShaderRenderPipeline(@NonNull GlDevice device) {
		this.device = device;
	}

	@NonNull
	List<ProjectDiagnostic> createContextResources() {
		ArrayList<ProjectDiagnostic> errors = new ArrayList<>();
		var surfaceResult = device.createProgram(
				FullScreenQuad.VERTEX_SHADER,
				SURFACE_FRAGMENT_SHADER,
				ShaderLineMapping.identity());
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
		thumbnailFramebuffer = null;
		thumbnailTexture = null;
		discardTargets();
	}

	void releaseTargets() {
		delete(framebuffers, targetTextures);
		frontTarget = 0;
		backTarget = 1;
	}

	boolean hasSurfaceProgram() {
		return surfaceProgram != null;
	}

	boolean hasTargets() {
		return framebuffers[0] != null && targetTextures[0] != null;
	}

	// Creates both targets with the seed, or cleared without one. Returns
	// null, and deletes what it created, when the seed does not upload or
	// a framebuffer is incomplete.
	@Nullable
	Targets createTargets(
			int width,
			int height,
			@NonNull Sampling sampling,
			@Nullable Bitmap seed,
			@NonNull List<ProjectDiagnostic> errors) {
		var targets = new Targets();
		boolean complete =
				createTarget(targets, 0, width, height, sampling, seed, errors) &&
						createTarget(targets, 1, width, height, sampling, seed, errors);
		device.bindFramebuffer(null);
		if (!complete) {
			delete(targets.framebuffers, targets.textures);
			return null;
		}
		return targets;
	}

	// Draws into these targets from now on, instead of the ones before.
	void useTargets(@NonNull Targets targets) {
		releaseTargets();
		System.arraycopy(targets.framebuffers, 0, framebuffers, 0, 2);
		System.arraycopy(targets.textures, 0, targetTextures, 0, 2);
	}

	void renderMainPass(@NonNull GlProgram program) {
		GlFramebuffer framebuffer = framebuffers[frontTarget];
		GlTexture2D targetTexture = targetTextures[frontTarget];
		if (framebuffer == null || targetTexture == null) {
			return;
		}

		device.bindFramebuffer(framebuffer);
		device.setViewport(0, 0, targetTexture.getWidth(), targetTexture.getHeight());
		device.useProgram(program);
		quad.draw();
	}

	void renderSurfacePass(int surfaceWidth, int surfaceHeight) {
		drawSurface(
				targetTextures[frontTarget],
				surfaceWidth,
				surfaceHeight,
				null);
	}

	@Nullable
	GlTexture2D getBackTexture() {
		return targetTextures[backTarget];
	}

	void swapTargets() {
		int target = frontTarget;
		frontTarget = backTarget;
		backTarget = target;
	}

	// Of the frame drawn last, after swapTargets().
	@Nullable
	byte[] captureThumbnail() {
		if (thumbnailFramebuffer == null || targetTextures[backTarget] == null) {
			return null;
		}

		drawSurface(
				targetTextures[backTarget],
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

	private void discardTargets() {
		framebuffers[0] = null;
		framebuffers[1] = null;
		targetTextures[0] = null;
		targetTextures[1] = null;
		frontTarget = 0;
		backTarget = 1;
	}

	private void delete(
			@NonNull GlFramebuffer[] framebuffers,
			@NonNull GlTexture2D[] textures) {
		for (int i = 0; i < framebuffers.length; ++i) {
			device.deleteFramebuffer(framebuffers[i]);
			framebuffers[i] = null;
			device.deleteTexture(textures[i]);
			textures[i] = null;
		}
	}

	private boolean createTarget(
			@NonNull Targets targets,
			int index,
			int width,
			int height,
			@NonNull Sampling sampling,
			@Nullable Bitmap seed,
			@NonNull List<ProjectDiagnostic> errors) {
		GlTexture2D texture = device.createTexture2D();
		targets.textures[index] = texture;

		if (seed != null) {
			String message = device.uploadTexture2D(texture, seed, true);
			if (message != null) {
				errors.add(ProjectDiagnostic.error(message));
				return false;
			}
		} else {
			device.allocateTexture2D(texture, width, height);
		}

		device.applySampling(texture, sampling);
		device.generateMipmap(texture);

		GlFramebuffer framebuffer = device.createFramebuffer();
		targets.framebuffers[index] = framebuffer;
		device.attachColor(framebuffer, texture);
		int status = device.checkFramebufferStatus(framebuffer);
		if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
			errors.add(ProjectDiagnostic.error(
					"Framebuffer incomplete: 0x" + Integer.toHexString(status)));
			return false;
		}

		if (seed == null) {
			device.bindFramebuffer(framebuffer);
			device.clear(GLES20.GL_COLOR_BUFFER_BIT |
					GLES20.GL_DEPTH_BUFFER_BIT);
		}
		return true;
	}
}

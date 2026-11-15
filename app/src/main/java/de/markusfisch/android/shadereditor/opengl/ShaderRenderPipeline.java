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
import java.util.Objects;

import de.markusfisch.android.shadereditor.project.ProjectDiagnostic;
import de.markusfisch.android.shadereditor.project.RenderPlan.Sampling;

final class ShaderRenderPipeline {
	record SurfaceProgram(
			@NonNull GlProgram program,
			int resolutionLocation,
			int limitLocation,
			int frameUnit) {
		@NonNull
		static SurfaceProgram of(@NonNull GlDevice device, @NonNull GlProgram program) {
			return new SurfaceProgram(
					program,
					device.getUniformLocation(program, SURFACE_RESOLUTION),
					device.getUniformLocation(program, SURFACE_LIMIT),
					program.getTextureUnit(SURFACE_FRAME));
		}
	}

	private static final int THUMBNAIL_WIDTH = 144;
	private static final int THUMBNAIL_HEIGHT = 144;
	private static final String SURFACE_RESOLUTION = "resolution";
	private static final String SURFACE_LIMIT = "limit";
	private static final String SURFACE_FRAME = "frame";
	// Samplers are lowp unless told otherwise, which would lose what an
	// rgba16f target holds.
	static final String SURFACE_FRAGMENT_SHADER = """
			#ifdef GL_FRAGMENT_PRECISION_HIGH
			precision highp float;
			precision highp sampler2D;
			#else
			precision mediump float;
			precision mediump sampler2D;
			#endif
			
			uniform vec2 resolution;
			uniform float limit;
			uniform sampler2D frame;
			void main(void) {
				vec4 c = texture2D(frame, gl_FragCoord.xy / resolution.xy);
				// PictureLimit.apply; only presenting has a limit, a
				// stretch into a history keeps values above 1.
				if (limit > 0.0) {
					c = clamp(c, 0.0, 1.0);
					c.rgb /= 1.0 + limit * max(c.r, max(c.g, c.b));
				}
				gl_FragColor = c;
			}
			""";

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
	private float limit;
	// A fit for what present() draws, or null; the program that maps it,
	// made when it is first asked for.
	@Nullable
	private Fit fit;
	@Nullable
	private GlProgram fitProgram;
	private int fitOrigin;
	private int fitResolution;
	private int fitScale;
	private int fitOffset;
	private int fitLimit;
	private int fitFrame;

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
		var message = device.attachColor(thumbnailFramebuffer,
				thumbnailTexture);
		int status = device.checkFramebufferStatus(thumbnailFramebuffer);
		if (message != null) {
			errors.add(ProjectDiagnostic.error("Thumbnail framebuffer: " +
					message));
		} else if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
			errors.add(ProjectDiagnostic.error(
					"Thumbnail framebuffer incomplete: 0x" +
							Integer.toHexString(status)));
		}
		device.bindFramebuffer(null);
		return errors;
	}

	void discardContextResources() {
		surfaceProgram = null;
		fitProgram = null;
		presented = null;
		thumbnailFramebuffer = null;
		thumbnailTexture = null;
	}

	boolean hasSurfaceProgram() {
		return surfaceProgram != null;
	}

	// The strength of PictureLimit for what present() draws from now on.
	void setLimit(float strength) {
		limit = strength;
	}

	// Stretches the texture over the surface, sampled as it is set up,
	// limited by setLimit(), and mapped by setFit(). picture is what a
	// thumbnail is of: the running pass, never fitted, also while
	// another one is shown.
	void present(
			@Nullable GlTexture2D texture,
			@Nullable GlTexture2D picture,
			int surfaceWidth,
			int surfaceHeight) {
		presented = picture;
		if (fit != null && texture != null && prepareFit()) {
			drawFitted(texture, surfaceWidth, surfaceHeight);
			return;
		}
		drawSurface(texture, surfaceWidth, surfaceHeight, null, limit);
	}

	// The fit for what present() draws from now on, or none.
	void setFit(@Nullable Fit fit) {
		this.fit = fit;
	}

	// What a thumbnail is of.
	@Nullable
	GlTexture2D picture() {
		return presented;
	}

	// Stretches the texture over the framebuffer, sampled as it is set up.
	void stretch(@NonNull GlTexture2D texture, @NonNull GlFramebuffer framebuffer) {
		drawSurface(texture, framebuffer.getWidth(), framebuffer.getHeight(),
				framebuffer, 0f);
	}

	// Of the frame presented last, without the limit.
	@Nullable
	byte[] captureThumbnail() {
		if (thumbnailFramebuffer == null || presented == null ||
				!presented.isValid()) {
			return null;
		}

		drawSurface(
				presented,
				THUMBNAIL_WIDTH,
				THUMBNAIL_HEIGHT,
				thumbnailFramebuffer,
				0f);

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

	// Whether the program of a fit is there.
	private boolean prepareFit() {
		if (fitProgram != null) {
			return true;
		}
		var made = device.createProgram(FullScreenQuad.VERTEX_SHADER,
				PassInspector.FRAGMENT_SHADER, null).getProgram();
		if (made == null) {
			return false;
		}
		fitProgram = made;
		fitOrigin = device.getUniformLocation(made, "origin");
		fitResolution = device.getUniformLocation(made, "resolution");
		fitScale = device.getUniformLocation(made, "scale");
		fitOffset = device.getUniformLocation(made, "offset");
		fitLimit = device.getUniformLocation(made, "limit");
		fitFrame = made.getTextureUnit("frame");
		return true;
	}

	private void drawFitted(@NonNull GlTexture2D texture, int width,
			int height) {
		var fit = Objects.requireNonNull(this.fit);
		device.bindFramebuffer(null);
		device.setViewport(0, 0, width, height);
		device.useProgram(fitProgram);
		drawResolution[0] = 0;
		drawResolution[1] = 0;
		device.uniform2fv(fitOrigin, 1, drawResolution);
		drawResolution[0] = width;
		drawResolution[1] = height;
		device.uniform2fv(fitResolution, 1, drawResolution);
		device.uniform1f(fitScale, fit.scale());
		device.uniform1f(fitOffset, fit.offset());
		device.uniform1f(fitLimit, limit);
		device.bindTexture(fitFrame, texture);
		device.clear(GLES20.GL_COLOR_BUFFER_BIT);
		device.draw(quad);
	}

	private void drawSurface(
			@Nullable GlTexture2D sourceTexture,
			int drawWidth,
			int drawHeight,
			@Nullable GlFramebuffer targetFramebuffer,
			float limit) {
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
		device.uniform1f(surfaceProgram.limitLocation(), limit);
		device.bindTexture(surfaceProgram.frameUnit(), sourceTexture);
		device.clear(GLES20.GL_COLOR_BUFFER_BIT);
		device.draw(quad);
	}
}

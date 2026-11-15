package de.markusfisch.android.shadereditor.opengl;

import android.opengl.GLES20;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

import de.markusfisch.android.shadereditor.project.ProjectPath;
import de.markusfisch.android.shadereditor.project.RenderPlan.Sampling;

// GL thread. Small pictures of passes, made only while a host watches
// them: every pass is drawn into its own cell of one atlas, each with
// its own pixels, cut to 0..1 as the screen would show it, and the
// atlas is read at once, so a round waits for the GPU once. Its GL
// objects are made when it is first asked and go with release().
final class PassInspector {
	// The longer side of a picture.
	static final int PICTURE = 96;
	// Cells per row of the atlas.
	private static final int COLUMNS = 8;
	static final String FRAGMENT_SHADER = """
			#ifdef GL_FRAGMENT_PRECISION_HIGH
			precision highp float;
			precision highp sampler2D;
			#else
			precision mediump float;
			precision mediump sampler2D;
			#endif

			uniform vec2 origin;
			uniform vec2 resolution;
			uniform sampler2D frame;
			void main(void) {
				gl_FragColor = texture2D(frame,
						(gl_FragCoord.xy - origin) / resolution);
			}
			""";

	// What to take a picture of: the texture a pass drew, at its size.
	record Source(@NonNull ProjectPath file, @NonNull GlTexture2D texture,
			int width, int height) {
	}

	@NonNull
	private final GlDevice device;
	private final FullScreenQuad quad = new FullScreenQuad();
	private final float[] vector = new float[2];
	@Nullable
	private GlProgram program;
	private int originLocation;
	private int resolutionLocation;
	private int frameUnit;
	@Nullable
	private GlTexture2D atlas;
	@Nullable
	private GlFramebuffer framebuffer;
	private int atlasWidth;
	private int atlasHeight;
	// Whether a program could not be made: nothing is tried again.
	private boolean failed;

	PassInspector(@NonNull GlDevice device) {
		this.device = device;
	}

	// The size of the picture of a target of width × height, its longer
	// side PICTURE, the other at least 1.
	static int pictureWidth(int width, int height) {
		return width >= height
				? PICTURE
				: Math.max(1, Math.round(PICTURE * width / (float) height));
	}

	static int pictureHeight(int width, int height) {
		return height >= width
				? PICTURE
				: Math.max(1, Math.round(PICTURE * height / (float) width));
	}

	// Pictures of sources, in their order; empty when they cannot be
	// made in this context.
	@NonNull
	List<PassPicture> pictures(@NonNull List<Source> sources) {
		var pictures = new ArrayList<PassPicture>();
		if (sources.isEmpty() || !prepare(sources.size())) {
			return pictures;
		}
		var program = this.program;
		device.bindFramebuffer(framebuffer);
		device.useProgram(program);
		var cells = new int[sources.size() * 4];
		for (int i = 0; i < sources.size(); ++i) {
			var source = sources.get(i);
			int x = (i % COLUMNS) * PICTURE;
			int y = (i / COLUMNS) * PICTURE;
			int width = pictureWidth(source.width(), source.height());
			int height = pictureHeight(source.width(), source.height());
			cells[i * 4] = x;
			cells[i * 4 + 1] = y;
			cells[i * 4 + 2] = width;
			cells[i * 4 + 3] = height;
			device.setViewport(x, y, width, height);
			vector[0] = x;
			vector[1] = y;
			device.uniform2fv(originLocation, 1, vector);
			vector[0] = width;
			vector[1] = height;
			device.uniform2fv(resolutionLocation, 1, vector);
			// Its own pixels, as a pass reads another.
			device.applySampling(source.texture(), Sampling.PASS);
			device.bindTexture(frameUnit, source.texture());
			device.draw(quad);
		}
		int[] rgba = new int[atlasWidth * atlasHeight];
		device.readPixels(0, 0, atlasWidth, atlasHeight, IntBuffer.wrap(rgba));
		device.bindFramebuffer(null);
		for (int i = 0; i < sources.size(); ++i) {
			int x = cells[i * 4];
			int y = cells[i * 4 + 1];
			int width = cells[i * 4 + 2];
			int height = cells[i * 4 + 3];
			int[] argb = new int[width * height];
			for (int row = 0; row < height; ++row) {
				int from = (y + row) * atlasWidth + x;
				int to = (height - 1 - row) * width;
				for (int column = 0; column < width; ++column) {
					argb[to + column] = argb(rgba[from + column]);
				}
			}
			pictures.add(new PassPicture(sources.get(i).file(), width, height,
					argb));
		}
		return pictures;
	}

	// GL thread. Deletes what it made.
	void release() {
		device.deleteProgram(program);
		device.deleteFramebuffer(framebuffer);
		device.deleteTexture(atlas);
		discard();
	}

	// After the context was lost with its objects.
	void discard() {
		program = null;
		framebuffer = null;
		atlas = null;
		atlasWidth = 0;
		atlasHeight = 0;
	}

	// Bytes R, G, B, A in memory, as glReadPixels writes them, read as a
	// little-endian int, to a colour int.
	static int argb(int rgba) {
		return (rgba & 0xff00ff00) |
				((rgba << 16) & 0x00ff0000) |
				((rgba >> 16) & 0x000000ff);
	}

	// Whether the program and an atlas for count pictures are there.
	private boolean prepare(int count) {
		if (failed) {
			return false;
		}
		if (program == null) {
			var built = device.createProgram(FullScreenQuad.VERTEX_SHADER,
					FRAGMENT_SHADER, null);
			var made = built.getProgram();
			if (made == null) {
				failed = true;
				return false;
			}
			program = made;
			originLocation = device.getUniformLocation(made, "origin");
			resolutionLocation = device.getUniformLocation(made, "resolution");
			frameUnit = made.getTextureUnit("frame");
		}
		int width = Math.min(count, COLUMNS) * PICTURE;
		int height = ((count + COLUMNS - 1) / COLUMNS) * PICTURE;
		if (atlas != null && width <= atlasWidth && height <= atlasHeight) {
			return true;
		}
		device.deleteFramebuffer(framebuffer);
		device.deleteTexture(atlas);
		width = Math.max(width, atlasWidth);
		height = Math.max(height, atlasHeight);
		var texture = device.createTexture2D();
		device.allocateTexture2D(texture, width, height);
		var made = device.createFramebuffer();
		var message = device.attachColor(made, texture);
		if (message != null || device.checkFramebufferStatus(made) !=
				GLES20.GL_FRAMEBUFFER_COMPLETE) {
			device.bindFramebuffer(null);
			device.deleteFramebuffer(made);
			device.deleteTexture(texture);
			atlas = null;
			framebuffer = null;
			failed = true;
			return false;
		}
		atlas = texture;
		framebuffer = made;
		atlasWidth = width;
		atlasHeight = height;
		return true;
	}
}

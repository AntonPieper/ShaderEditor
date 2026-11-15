package de.markusfisch.android.shadereditor.opengl;

import android.opengl.GLES20;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import de.markusfisch.android.shadereditor.project.HostDirective;
import de.markusfisch.android.shadereditor.project.ProjectPath;
import de.markusfisch.android.shadereditor.project.RenderPlan.Sampling;

// GL thread. Small pictures of passes and their facts, made only while
// a host watches them: every pass is drawn into its own cell of one
// atlas, each with its own pixels, cut to 0..1 as the screen would show
// it, and the atlas is read at once, so a round waits for the GPU once.
// Its GL objects are made when it is first asked and go with release().
// A round allocates nothing once its buffers fit: sources, cells,
// pixels and the pictures handed out are kept for the next round.
//
// What a round changes of the context's state, and what it leaves:
// - restored: the texture parameters of every source, to the sampling
//   it had (GlTexture2D.sampling()); GL_DITHER, which is off while it
//   draws, as dithering may change the bytes of a float's bits; the
//   framebuffer, the default one, which was bound when it ran, after
//   the frame was presented;
// - not restored: the program, the viewport and the texture bound to its
//   sampler's unit, which every draw of the engine sets before it draws
//   (PlanPass.bind, ShaderRenderPipeline.drawSurface);
// - never changed: blending (off in the engine's context), the colour
//   mask, scissor and pixel store; its targets are RGBA8, never sRGB.
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
			// A fit: value times scale plus offset, alpha 1; scale 0 for
			// none. limit as in ShaderRenderPipeline.
			uniform float scale;
			uniform float offset;
			uniform float limit;
			void main(void) {
				vec4 c = texture2D(frame,
						(gl_FragCoord.xy - origin) / resolution);
				if (scale != 0.0) {
					c = vec4(clamp(c.rgb * scale + offset, 0.0, 1.0), 1.0);
				}
				if (limit > 0.0) {
					c = clamp(c, 0.0, 1.0);
					c.rgb /= 1.0 + limit * max(c.r, max(c.g, c.b));
				}
				gl_FragColor = c;
			}
			""";

	// The side of the sample of a float target.
	static final int FLOAT_SAMPLE = 32;
	// Each of FLOAT_SAMPLE × FLOAT_SAMPLE texels of frame, spread evenly
	// over it, as four pixels: the 32 bits of its red, green, blue and
	// alpha, low byte first, which 8 bits per channel carry exactly.
	static final String ENCODE_SHADER = """
			#version 300 es
			precision highp float;
			precision highp int;
			precision highp sampler2D;

			uniform vec2 origin;
			uniform sampler2D frame;
			out vec4 color;
			void main(void) {
				ivec2 cell = ivec2(gl_FragCoord.xy - origin);
				ivec2 size = textureSize(frame, 0);
				// "sample" is a reserved word in GLSL ES 3.00.
				ivec2 taken = ivec2(cell.x / 4, cell.y);
				ivec2 texel = min((2 * taken + 1) * size / 64, size - 1);
				vec4 value = texelFetch(frame, texel, 0);
				int channel = cell.x - taken.x * 4;
				float f = channel == 0 ? value.r
						: channel == 1 ? value.g
						: channel == 2 ? value.b
						: value.a;
				uint bits = floatBitsToUint(f);
				color = vec4(uvec4(bits, bits >> 8u, bits >> 16u,
						bits >> 24u) & 255u) / 255.0;
			}
			""";

	// What to take a picture of: the texture a pass drew, at its size
	// and in its format. Kept for the next round and filled again.
	static final class Source {
		@NonNull
		ProjectPath file;
		@NonNull
		GlTexture2D texture;
		int width;
		int height;
		@NonNull
		HostDirective.Format format;

		Source(@NonNull ProjectPath file, @NonNull GlTexture2D texture,
				int width, int height, @NonNull HostDirective.Format format) {
			this.file = file;
			this.texture = texture;
			this.width = width;
			this.height = height;
			this.format = format;
		}
	}

	// The sources of a round, in the rows' order; filled by
	// PlanRuntime.lastFrames() into the slots of the round before.
	static final class Sources {
		private final List<Source> slots = new ArrayList<>();
		private int size;

		void clear() {
			size = 0;
		}

		void add(@NonNull ProjectPath file, @NonNull GlTexture2D texture,
				int width, int height, @NonNull HostDirective.Format format) {
			if (size < slots.size()) {
				var slot = slots.get(size);
				slot.file = file;
				slot.texture = texture;
				slot.width = width;
				slot.height = height;
				slot.format = format;
			} else {
				slots.add(new Source(file, texture, width, height, format));
			}
			++size;
		}

		int size() {
			return size;
		}

		@NonNull
		Source get(int i) {
			return slots.get(i);
		}
	}

	// A sample a fact was last made of, kept as a copy, and since when it
	// is so.
	private static final class Seen {
		@NonNull
		int[] values = new int[0];
		long since;
	}

	@NonNull
	private final GlDevice device;
	private final FullScreenQuad quad = new FullScreenQuad();
	private final float[] vector = new float[2];
	private final Sources sources = new Sources();
	// Per slot of sources: its picture's pixels and the picture handed
	// out with them, kept while its size stays.
	private final List<int[]> pixels = new ArrayList<>();
	private final List<PassPicture> made = new ArrayList<>();
	private final List<PassPicture> pictures = new ArrayList<>();
	// The pixels of the fitted pass as its target holds them, which its
	// facts are of, and its slot, or -1.
	@NonNull
	private int[] unfitted = new int[0];
	private int fittedSlot = -1;
	// Cells by slot, as x, y, width, height; the unfitted cell last.
	@NonNull
	private int[] cells = new int[0];
	@NonNull
	private int[] rgba = new int[0];
	// What each source's texture was sampled with before a round.
	private final List<Sampling> samplings = new ArrayList<>();
	private final Map<ProjectPath, Seen> seen = new HashMap<>();
	@Nullable
	private GlProgram program;
	private int originLocation;
	private int resolutionLocation;
	private int scaleLocation;
	private int offsetLocation;
	private int frameUnit;
	@Nullable
	private GlTexture2D atlas;
	@Nullable
	private GlFramebuffer framebuffer;
	private int atlasWidth;
	private int atlasHeight;
	// Whether a program could not be made: nothing is tried again.
	private boolean failed;
	// The float sample: its program, target and buffers.
	@Nullable
	private GlProgram encoder;
	private int encoderOrigin;
	private int encoderFrame;
	@Nullable
	private GlTexture2D encoded;
	@Nullable
	private GlFramebuffer encodedFramebuffer;
	private int encodedHeight;
	private boolean encoderFailed;
	@NonNull
	private int[] read = new int[0];
	private final List<int[]> bits = new ArrayList<>();

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

	// The sources of the next round, for PlanRuntime.lastFrames().
	@NonNull
	Sources sources() {
		return sources;
	}

	// Pictures of sources(), in their order, the pass of fit mapped as it
	// says; empty when they cannot be made in this context. The list, its
	// pictures and their pixels are this round's: the next round fills
	// them again.
	@NonNull
	List<PassPicture> pictures(@Nullable Fit fit) {
		pictures.clear();
		fittedSlot = -1;
		int count = sources.size();
		for (int i = 0; i < count && fit != null; ++i) {
			if (fit.file().equals(sources.get(i).file)) {
				fittedSlot = i;
			}
		}
		// The fitted pass once more, as its target holds it.
		int cellCount = count + (fittedSlot > -1 ? 1 : 0);
		if (count == 0 || !prepare(cellCount)) {
			return pictures;
		}
		if (cells.length < cellCount * 4) {
			cells = new int[cellCount * 4];
		}
		boolean dither = device.isEnabled(GLES20.GL_DITHER);
		if (dither) {
			device.disable(GLES20.GL_DITHER);
		}
		device.bindFramebuffer(framebuffer);
		device.useProgram(program);
		samplings.clear();
		for (int i = 0; i < count; ++i) {
			var texture = sources.get(i).texture;
			samplings.add(texture.sampling());
			// Its own pixels, as a pass reads another.
			device.applySampling(texture, Sampling.PASS);
		}
		for (int i = 0; i < cellCount; ++i) {
			int slot = i < count ? i : fittedSlot;
			drawCell(i, sources.get(slot),
					i == fittedSlot ? Objects.requireNonNull(fit) : null);
		}
		int size = atlasWidth * atlasHeight;
		if (rgba.length < size) {
			rgba = new int[size];
		}
		device.readPixels(0, 0, atlasWidth, atlasHeight, IntBuffer.wrap(rgba));
		device.bindFramebuffer(null);
		for (int i = 0; i < count; ++i) {
			var before = samplings.get(i);
			if (before != null && before != Sampling.PASS) {
				device.applySampling(sources.get(i).texture, before);
			}
		}
		if (dither) {
			device.enable(GLES20.GL_DITHER);
		}
		for (int i = 0; i < count; ++i) {
			pictures.add(picture(i));
		}
		if (fittedSlot > -1) {
			int length = cells[count * 4 + 2] * cells[count * 4 + 3];
			if (unfitted.length != length) {
				unfitted = new int[length];
			}
			copyCell(count, unfitted);
		}
		return pictures;
	}

	// Facts of every row of running from the round pictures() drew, at
	// now: of the targets, never of a fitted view. A float target is
	// sampled in its own values where the context is OpenGL ES 3;
	// anything else from its 8-bit picture.
	@NonNull
	List<PassFacts> facts(@NonNull RunningPasses running, long now,
			boolean es3) {
		boolean[] isFloat = new boolean[sources.size()];
		for (int i = 0; i < sources.size(); ++i) {
			isFloat[i] = es3 && i < pictures.size() &&
					sources.get(i).format == HostDirective.Format.RGBA16F;
		}
		boolean encodedFloats = encode(isFloat);
		var facts = new ArrayList<PassFacts>();
		for (var row : running.rows()) {
			var file = row.file();
			int slot = -1;
			for (int i = 0; i < sources.size() && i < pictures.size(); ++i) {
				if (sources.get(i).file.equals(file)) {
					slot = i;
				}
			}
			if (slot < 0) {
				facts.add(new PassFacts(file, row.drawn(), row.problems(),
						0, 0, null, 0, null, null, null, false, 0));
				continue;
			}
			var source = sources.get(slot);
			boolean sampled = encodedFloats && isFloat[slot];
			int[] values = sampled
					? bits.get(slot)
					: slot == fittedSlot ? unfitted : pixels.get(slot);
			long unchanged = unchanged(file, values, now);
			if (sampled) {
				facts.add(floatFacts(row, source, values, unchanged));
			} else {
				facts.add(new PassFacts(file, row.drawn(), row.problems(),
						source.width, source.height, source.format,
						PICTURE, same(values), null, null, false,
						unchanged));
			}
		}
		seen.keySet().retainAll(filesOf(running));
		return facts;
	}

	// GL thread. Deletes what it made.
	void release() {
		device.deleteProgram(program);
		device.deleteFramebuffer(framebuffer);
		device.deleteTexture(atlas);
		device.deleteProgram(encoder);
		device.deleteFramebuffer(encodedFramebuffer);
		device.deleteTexture(encoded);
		discard();
	}

	// After the context was lost with its objects.
	void discard() {
		program = null;
		framebuffer = null;
		atlas = null;
		atlasWidth = 0;
		atlasHeight = 0;
		encoder = null;
		encodedFramebuffer = null;
		encoded = null;
		encodedHeight = 0;
		seen.clear();
	}

	// The value every pixel of argb holds, as red, green, blue and alpha
	// of 0..1, or null when they differ.
	@Nullable
	static float[] same(@NonNull int[] argb) {
		for (int pixel : argb) {
			if (pixel != argb[0]) {
				return null;
			}
		}
		int pixel = argb[0];
		return new float[]{
				((pixel >> 16) & 0xff) / 255f,
				((pixel >> 8) & 0xff) / 255f,
				(pixel & 0xff) / 255f,
				(pixel >>> 24) / 255f};
	}

	// The facts of a float sample: bits of red, green, blue and alpha of
	// each sampled texel.
	@NonNull
	static PassFacts floatFacts(@NonNull RunningPasses.Row row,
			@NonNull Source source, @NonNull int[] bits, long unchanged) {
		var low = new float[4];
		var high = new float[4];
		Arrays.fill(low, Float.POSITIVE_INFINITY);
		Arrays.fill(high, Float.NEGATIVE_INFINITY);
		boolean nonFinite = false;
		boolean same = true;
		for (int i = 0; i < bits.length; ++i) {
			float value = Float.intBitsToFloat(bits[i]);
			int channel = i % 4;
			same &= bits[i] == bits[channel];
			if (Float.isNaN(value) || Float.isInfinite(value)) {
				nonFinite = true;
				continue;
			}
			low[channel] = Math.min(low[channel], value);
			high[channel] = Math.max(high[channel], value);
		}
		boolean finite = true;
		for (int c = 0; c < 4; ++c) {
			finite &= low[c] <= high[c];
		}
		float[] value = same
				? new float[]{
						Float.intBitsToFloat(bits[0]),
						Float.intBitsToFloat(bits[1]),
						Float.intBitsToFloat(bits[2]),
						Float.intBitsToFloat(bits[3])}
				: null;
		return new PassFacts(row.file(), row.drawn(), row.problems(),
				source.width, source.height, source.format,
				FLOAT_SAMPLE, value, finite ? low : null,
				finite ? high : null, nonFinite, unchanged);
	}

	// Bytes R, G, B, A in memory, as glReadPixels writes them, read as a
	// little-endian int, to a colour int.
	static int argb(int rgba) {
		return (rgba & 0xff00ff00) |
				((rgba << 16) & 0x00ff0000) |
				((rgba >> 16) & 0x000000ff);
	}

	// Draws source into cell i of the atlas, mapped by fit, if any.
	private void drawCell(int i, @NonNull Source source, @Nullable Fit fit) {
		int x = (i % COLUMNS) * PICTURE;
		int y = (i / COLUMNS) * PICTURE;
		int width = pictureWidth(source.width, source.height);
		int height = pictureHeight(source.width, source.height);
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
		device.uniform1f(scaleLocation, fit != null ? fit.scale() : 0f);
		device.uniform1f(offsetLocation, fit != null ? fit.offset() : 0f);
		device.bindTexture(frameUnit, source.texture);
		device.draw(quad);
	}

	// The picture of slot i, its pixels and the picture itself kept from
	// the round before while its file and size stay.
	@NonNull
	private PassPicture picture(int i) {
		int width = cells[i * 4 + 2];
		int height = cells[i * 4 + 3];
		while (pixels.size() <= i) {
			pixels.add(new int[0]);
			made.add(null);
		}
		int[] argb = pixels.get(i);
		if (argb.length != width * height) {
			argb = new int[width * height];
			pixels.set(i, argb);
		}
		copyCell(i, argb);
		var file = sources.get(i).file;
		var picture = made.get(i);
		if (picture == null || picture.argb() != argb ||
				!picture.file().equals(file) || picture.width() != width) {
			picture = new PassPicture(file, width, height, argb);
			made.set(i, picture);
		}
		return picture;
	}

	// The pixels of cell i, the top row first, into argb.
	private void copyCell(int i, @NonNull int[] argb) {
		int x = cells[i * 4];
		int y = cells[i * 4 + 1];
		int width = cells[i * 4 + 2];
		int height = cells[i * 4 + 3];
		for (int row = 0; row < height; ++row) {
			int from = (y + row) * atlasWidth + x;
			int to = (height - 1 - row) * width;
			for (int column = 0; column < width; ++column) {
				argb[to + column] = argb(rgba[from + column]);
			}
		}
	}

	// How long file's sample has stayed values, kept as a copy.
	private long unchanged(@NonNull ProjectPath file, @NonNull int[] values,
			long now) {
		var kept = seen.get(file);
		if (kept == null) {
			kept = new Seen();
			seen.put(file, kept);
		}
		if (!Arrays.equals(kept.values, values)) {
			if (kept.values.length != values.length) {
				kept.values = new int[values.length];
			}
			System.arraycopy(values, 0, kept.values, 0, values.length);
			kept.since = now;
		}
		return now - kept.since;
	}

	@NonNull
	private static List<ProjectPath> filesOf(@NonNull RunningPasses running) {
		var files = new ArrayList<ProjectPath>();
		for (var row : running.rows()) {
			files.add(row.file());
		}
		return files;
	}

	// Samples the slots that are float, as their bits into bits(slot):
	// four ints a texel, red, green, blue, alpha, the bottom row first.
	// False when the encoder cannot be made.
	private boolean encode(@NonNull boolean[] isFloat) {
		int count = 0;
		for (boolean f : isFloat) {
			count += f ? 1 : 0;
		}
		if (count == 0 || !prepareEncoder(count)) {
			return false;
		}
		int width = FLOAT_SAMPLE * 4;
		boolean dither = device.isEnabled(GLES20.GL_DITHER);
		if (dither) {
			device.disable(GLES20.GL_DITHER);
		}
		device.bindFramebuffer(encodedFramebuffer);
		device.useProgram(encoder);
		for (int i = 0, n = 0; i < isFloat.length; ++i) {
			if (!isFloat[i]) {
				continue;
			}
			int y = n++ * FLOAT_SAMPLE;
			device.setViewport(0, y, width, FLOAT_SAMPLE);
			vector[0] = 0;
			vector[1] = y;
			device.uniform2fv(encoderOrigin, 1, vector);
			// texelFetch reads the texel as it is, whatever the sampling.
			device.bindTexture(encoderFrame, sources.get(i).texture);
			device.draw(quad);
		}
		int size = width * count * FLOAT_SAMPLE;
		if (read.length < size) {
			read = new int[size];
		}
		device.readPixels(0, 0, width, count * FLOAT_SAMPLE,
				IntBuffer.wrap(read));
		device.bindFramebuffer(null);
		if (dither) {
			device.enable(GLES20.GL_DITHER);
		}
		int sample = width * FLOAT_SAMPLE;
		while (bits.size() < isFloat.length) {
			bits.add(new int[sample]);
		}
		for (int i = 0, n = 0; i < isFloat.length; ++i) {
			if (isFloat[i]) {
				System.arraycopy(read, n++ * sample, bits.get(i), 0, sample);
			}
		}
		return true;
	}

	// Whether the encoder and its target for count samples are there.
	private boolean prepareEncoder(int count) {
		if (encoderFailed) {
			return false;
		}
		if (encoder == null) {
			var built = device.createProgram("#version 300 es\n" +
					FullScreenQuad.VERTEX_SHADER_3, ENCODE_SHADER, null);
			var made = built.getProgram();
			if (made == null) {
				encoderFailed = true;
				return false;
			}
			encoder = made;
			encoderOrigin = device.getUniformLocation(made, "origin");
			encoderFrame = made.getTextureUnit("frame");
		}
		int height = count * FLOAT_SAMPLE;
		if (encoded != null && height <= encodedHeight) {
			return true;
		}
		device.deleteFramebuffer(encodedFramebuffer);
		device.deleteTexture(encoded);
		var texture = device.createTexture2D();
		device.allocateTexture2D(texture, FLOAT_SAMPLE * 4, height);
		var made = device.createFramebuffer();
		var message = device.attachColor(made, texture);
		if (message != null || device.checkFramebufferStatus(made) !=
				GLES20.GL_FRAMEBUFFER_COMPLETE) {
			device.bindFramebuffer(null);
			device.deleteFramebuffer(made);
			device.deleteTexture(texture);
			encoded = null;
			encodedFramebuffer = null;
			encoderFailed = true;
			return false;
		}
		encoded = texture;
		encodedFramebuffer = made;
		encodedHeight = height;
		return true;
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
			scaleLocation = device.getUniformLocation(made, "scale");
			offsetLocation = device.getUniformLocation(made, "offset");
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

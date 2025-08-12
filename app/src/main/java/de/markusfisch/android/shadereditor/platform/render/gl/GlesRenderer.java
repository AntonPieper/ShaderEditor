package de.markusfisch.android.shadereditor.platform.render.gl;

import android.opengl.GLES32;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jetbrains.annotations.Contract;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import de.markusfisch.android.shadereditor.engine.Renderer;
import de.markusfisch.android.shadereditor.engine.ShaderIntrospector;
import de.markusfisch.android.shadereditor.engine.asset.ShaderAsset;
import de.markusfisch.android.shadereditor.engine.graphics.Primitive;
import de.markusfisch.android.shadereditor.engine.pipeline.CommandBuffer;
import de.markusfisch.android.shadereditor.engine.pipeline.GpuCommand;
import de.markusfisch.android.shadereditor.engine.scene.Geometry;
import de.markusfisch.android.shadereditor.engine.scene.Uniform;

public class GlesRenderer implements Renderer, ShaderIntrospector, AutoCloseable {
	private static final String TAG = "GlesRenderer";
	// Fallback blit shader (ES2 friendly)
	private static final ShaderAsset BLIT_SHADER = new ShaderAsset(
			/* VS */ """
			attribute vec4 a_Position;
			attribute vec2 a_TexCoord;
			varying vec2 v_TexCoord;
			void main(){ gl_Position=a_Position; v_TexCoord=a_TexCoord; }
			""",
			/* FS */ """
			precision mediump float;
			varying vec2 v_TexCoord;
			uniform sampler2D uTex;
			void main(){ gl_FragColor = texture2D(uTex, v_TexCoord); }
			"""
	);
	private final Map<ShaderAsset, GlesProgram> shaderCache = new HashMap<>();
	private final GlesGpuObjectManager gpu = new GlesGpuObjectManager();
	private final Geometry fsq = Geometry.fullscreenQuad();
	@Nullable
	private GlesProgram blitProgram; // lazily compiled

	@Override
	public void onSurfaceCreated() {
		shaderCache.clear();
		gpu.destroy();
		GLES32.glClearColor(0.1f, 0.1f, 0.1f, 1.0f);
	}

	@Override
	public void onSurfaceChanged(int width, int height) {
		GLES32.glViewport(0, 0, width, height);
	}

	@Override
	public void execute(@NonNull CommandBuffer cb) {
		var binder = new GlesBinder(gpu);
		GlesProgram current = null;

		for (var cmd : cb.cmds()) {
			switch (cmd) {
				case GpuCommand.BeginPass bp -> handleBeginPass(bp, binder);
				case GpuCommand.EndPass ignored -> handleEndPass();
				case GpuCommand.BindProgram bp -> current = handleBindProgram(bp);
				case GpuCommand.SetUniforms su -> {
					if (current != null) handleSetUniforms(current, su, binder);
				}
				case GpuCommand.BindGeometry bg -> handleBindGeometry(bg);
				case GpuCommand.Draw d -> handleDraw(d);
				case GpuCommand.Blit bl -> handleBlit(bl, binder);
			}
		}
	}

	@Override
	public void close() {
		shaderCache.clear();
	}

	@NonNull
	@Override
	public ShaderMetadata introspect(@NonNull ShaderAsset asset) {
		return prepareShader(asset);
	}

	private void handleBeginPass(@NonNull GpuCommand.BeginPass bp, @NonNull GlesBinder binder) {
		int fbo = gpu.getFramebufferHandle(bp.target());
		GLES32.glBindFramebuffer(GLES32.GL_FRAMEBUFFER, fbo);
		if (bp.viewport() != null) {
			var v = bp.viewport();
			GLES32.glViewport(v.x(), v.y(), v.width(), v.height());
		}
		if (bp.clearColor() != null) {
			var c = bp.clearColor();
			GLES32.glClearColor(c.r(), c.g(), c.b(), c.a());
			GLES32.glClear(GLES32.GL_COLOR_BUFFER_BIT);
		}
		binder.resetTextureUnits();
	}

	private void handleEndPass() {
		GLES32.glBindFramebuffer(GLES32.GL_FRAMEBUFFER, 0);
	}

	@NonNull
	private GlesProgram handleBindProgram(@NonNull GpuCommand.BindProgram bp) {
		GlesProgram p = prepareShader(bp.material().shader());
		GLES32.glUseProgram(p.programId());
		return p;
	}

	private void handleSetUniforms(
			@NonNull GlesProgram program,
			@NonNull GpuCommand.SetUniforms su,
			@NonNull GlesBinder binder
	) {
		for (var e : su.material().uniforms().entrySet()) {
			int loc = program.locate(e.getKey());
			if (loc >= 0) binder.bind(loc, e.getValue());
		}
	}

	private void handleBindGeometry(@NonNull GpuCommand.BindGeometry bg) {
		int vao = gpu.getGeometryHandle(bg.geometry());
		GLES32.glBindVertexArray(vao);
	}

	private void handleDraw(@NonNull GpuCommand.Draw d) {
		GLES32.glDrawArrays(toGL(d.primitive()), d.first(), d.vertexCount());
	}

	private void handleBlit(@NonNull GpuCommand.Blit bl, @NonNull GlesBinder binder) {
		// Bind destination
		GLES32.glBindFramebuffer(GLES32.GL_FRAMEBUFFER, gpu.getFramebufferHandle(bl.dst()));

		// (Optional) set viewport to dst size for offscreen FBOs
		if (!bl.dst().isDefault()) {
			GLES32.glViewport(0, 0, bl.dst().width(), bl.dst().height());
		}

		// Lazy-compile simple blit shader
		if (blitProgram == null) {
			blitProgram = prepareShader(BLIT_SHADER);
		}
		GLES32.glUseProgram(blitProgram.programId());
		binder.resetTextureUnits();

		int loc = blitProgram.locate("uTex");
		if (loc >= 0) {
			binder.bind(loc, new Uniform.Sampler2D(bl.src()));
		}

		// Draw FSQ
		int vao = gpu.getGeometryHandle(fsq);
		GLES32.glBindVertexArray(vao);
		GLES32.glDrawArrays(GLES32.GL_TRIANGLE_STRIP, 0, fsq.vertexCount());
	}

	@NonNull
	private GlesProgram prepareShader(@NonNull ShaderAsset shader) {
		return shaderCache.computeIfAbsent(shader, (s) -> {
			int vertexShader = compileShader(GLES32.GL_VERTEX_SHADER,
					s.vertexSource());
			if (vertexShader == 0) return GlesProgram.INVALID;
			int fragmentShader = compileShader(GLES32.GL_FRAGMENT_SHADER,
					s.fragmentSource());
			if (fragmentShader == 0) return GlesProgram.INVALID;
			int programId = linkProgram(vertexShader, fragmentShader);
			var activeUniforms = introspectActiveUniforms(programId);
			return new GlesProgram(
					programId,
					Collections.unmodifiableMap(activeUniforms));
		});
	}

	@NonNull
	private Map<String, UniformInfo> introspectActiveUniforms(int programId) {
		Map<String, UniformInfo> activeUniforms = new HashMap<>();
		int[] count = new int[1];
		GLES32.glGetProgramiv(programId, GLES32.GL_ACTIVE_UNIFORMS, count, 0);

		if (count[0] == 0) {
			return Map.of();
		}

		int[] length = new int[1];
		int[] size = new int[1];
		int[] type = new int[1];
		byte[] nameBytes = getNameBuffer(programId);

		for (int i = 0; i < count[0]; i++) {
			GLES32.glGetActiveUniform(programId, i, nameBytes.length, length, 0, size, 0, type, 0,
					nameBytes, 0);
			String name = normalizeArrayName(length[0], nameBytes);
			activeUniforms.put(name,
					new UniformInfo(GLES32.glGetUniformLocation(programId, name)));
		}

		return activeUniforms;
	}

	private int compileShader(int type, String source) {
		int shader = GLES32.glCreateShader(type);
		GLES32.glShaderSource(shader, source);
		GLES32.glCompileShader(shader);

		int[] compileStatus = new int[1];
		GLES32.glGetShaderiv(shader, GLES32.GL_COMPILE_STATUS, compileStatus, 0);
		if (compileStatus[0] == 0) {
			Log.e(TAG, "Shader Compile Error: " + GLES32.glGetShaderInfoLog(shader));
			GLES32.glDeleteShader(shader);
			return 0;
		}
		return shader;
	}

	private int linkProgram(int vertexShader, int fragmentShader) {
		int program = GLES32.glCreateProgram();
		GLES32.glAttachShader(program, vertexShader);
		GLES32.glAttachShader(program, fragmentShader);
		GLES32.glLinkProgram(program);

		int[] linkStatus = new int[1];
		GLES32.glGetProgramiv(program, GLES32.GL_LINK_STATUS, linkStatus, 0);
		if (linkStatus[0] == 0) {
			Log.e(TAG, "Program Link Error: " + GLES32.glGetProgramInfoLog(program));
			GLES32.glDeleteProgram(program);
			return 0;
		}
		return program;
	}

	@Contract(pure = true)
	private static int toGL(@NonNull Primitive p) {
		return switch (p) {
			case TRIANGLES -> GLES32.GL_TRIANGLES;
			case TRIANGLE_STRIP -> GLES32.GL_TRIANGLE_STRIP;
			case TRIANGLE_FAN -> GLES32.GL_TRIANGLE_FAN;
			case LINES -> GLES32.GL_LINES;
			case LINE_STRIP -> GLES32.GL_LINE_STRIP;
			case POINTS -> GLES32.GL_POINTS;
		};
	}

	@NonNull
	private static String normalizeArrayName(int length, byte[] nameBytes) {
		// For uniform arrays, the name might be "myArray[0]". We want "myArray".
		for (int j = 0; j < length; j++) {
			if (nameBytes[j] == '[') {
				return new String(nameBytes, 0, j);
			}
		}
		return new String(nameBytes, 0, length);
	}

	@NonNull
	private static byte[] getNameBuffer(int programId) {
		int[] nameLength = new int[1];
		GLES32.glGetProgramiv(programId, GLES32.GL_ACTIVE_UNIFORM_MAX_LENGTH, nameLength, 0);
		return new byte[nameLength[0]];
	}

	private record UniformInfo(int location) {
	}

	private record GlesProgram(
			int programId,
			@NonNull Map<String, UniformInfo> activeUniforms)
			implements ShaderIntrospector.ShaderMetadata {
		public static final GlesProgram INVALID = new GlesProgram(0, Map.of());

		/**
		 * Returns the location of the uniform with the given name.
		 * If the uniform is not found, -1 is returned.
		 *
		 * @param name The name of the uniform.
		 * @return The location of the uniform, or -1 if not found.
		 */
		public int locate(@NonNull String name) {
			var info = activeUniforms().get(name);
			if (info == null) {
				return -1;
			}
			return info.location();
		}

		@NonNull
		@Override
		public Set<String> getActiveUniformNames() {
			return activeUniforms().keySet();
		}
	}
}
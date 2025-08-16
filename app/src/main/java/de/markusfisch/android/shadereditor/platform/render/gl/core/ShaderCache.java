package de.markusfisch.android.shadereditor.platform.render.gl.core;

import android.opengl.GLES32;
import android.util.Log;

import androidx.annotation.NonNull;

import org.jetbrains.annotations.Contract;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import de.markusfisch.android.shadereditor.engine.ShaderIntrospector;
import de.markusfisch.android.shadereditor.engine.asset.ShaderAsset;
import de.markusfisch.android.shadereditor.engine.error.EngineError;
import de.markusfisch.android.shadereditor.engine.error.EngineException;
import de.markusfisch.android.shadereditor.engine.error.ShaderErrorParser;

public final class ShaderCache implements ShaderIntrospector {
	private static final String TAG = "ShaderCache";
	private final Map<ShaderAsset, Program> cache = new HashMap<>();

	@NonNull
	public Program get(@NonNull ShaderAsset s) {
		return cache.computeIfAbsent(s, this::build);
	}

	public void clear() {
		cache.clear();
	}

	@NonNull
	@Override
	public ShaderMetadata introspect(@NonNull ShaderAsset asset) {
		return get(asset);
	}

	@NonNull
	private Program build(@NonNull ShaderAsset s) {
		int vs = compile(GLES32.GL_VERTEX_SHADER, s.vertexSource());
		int fs = compile(GLES32.GL_FRAGMENT_SHADER, s.fragmentSource());
		int pid = link(vs, fs);
		Map<String, Integer> act = activeUniforms(pid);
		return new Program(pid, Collections.unmodifiableMap(act));
	}

	private static int compile(int type, String src) {
		int id = GLES32.glCreateShader(type);
		GLES32.glShaderSource(id, src);
		GLES32.glCompileShader(id);
		int[] ok = new int[1];
		GLES32.glGetShaderiv(id, GLES32.GL_COMPILE_STATUS, ok, 0);
		if (ok[0] == 0) {
			String log = GLES32.glGetShaderInfoLog(id);
			Log.e(TAG, "Compile error: " + log);
			GLES32.glDeleteShader(id);
			throw new EngineException(new EngineError.ShaderCompilationError(
					"Shader compilation failed.",
					log,
					ShaderErrorParser.parseInfoLog(log),
					null
			));
		}
		return id;
	}

	private static int link(int vs, int fs) {
		int p = GLES32.glCreateProgram();
		GLES32.glAttachShader(p, vs);
		GLES32.glAttachShader(p, fs);
		GLES32.glLinkProgram(p);
		int[] ok = new int[1];
		GLES32.glGetProgramiv(p, GLES32.GL_LINK_STATUS, ok, 0);
		if (ok[0] == 0) {
			String log = GLES32.glGetProgramInfoLog(p);
			throw new EngineException(new EngineError.ShaderCompilationError(
					"Shader program linking failed.",
					log,
					ShaderErrorParser.parseInfoLog(log),
					null
			));
		}
		return p;
	}

	@NonNull
	private static Map<String, Integer> activeUniforms(int program) {
		int[] cnt = new int[1];
		GLES32.glGetProgramiv(program, GLES32.GL_ACTIVE_UNIFORMS, cnt, 0);
		if (cnt[0] == 0) return Map.of();

		var len = new int[1];
		var size = new int[1];
		var type = new int[1];
		var max = new int[1];
		GLES32.glGetProgramiv(program, GLES32.GL_ACTIVE_UNIFORM_MAX_LENGTH, max, 0);
		var name = new byte[max[0]];
		Map<String, Integer> out = new HashMap<>();
		for (int i = 0; i < cnt[0]; i++) {
			GLES32.glGetActiveUniform(program, i, name.length, len, 0, size, 0, type, 0, name, 0);
			String n = normalize(len[0], name);
			out.put(n, GLES32.glGetUniformLocation(program, n));
		}
		return out;
	}

	@NonNull
	@Contract("_, _ -> new")
	private static String normalize(int n, byte[] bytes) {
		for (int i = 0; i < n; i++) if (bytes[i] == '[') return new String(bytes, 0, i);
		return new String(bytes, 0, n);
	}

	/**
	 * Program is the introspection result and a light handle.
	 */
	public record Program(int programId, Map<String, Integer> uniforms)
			implements ShaderIntrospector.ShaderMetadata, GlesRenderContext.GlesProgramHandle {
		@SuppressWarnings("DataFlowIssue")
		public int locate(@NonNull String name) {
			return uniforms.getOrDefault(name, -1);
		}

		@NonNull
		@Override
		public Set<String> getActiveUniformNames() {
			return uniforms.keySet();
		}
	}
}
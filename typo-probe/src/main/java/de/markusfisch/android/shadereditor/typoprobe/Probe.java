package de.markusfisch.android.shadereditor.typoprobe;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.res.AssetManager;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.os.Build;
import android.os.Bundle;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Compiles every text of assets/texts (id.frag with its id.vert) on an
 * EGL pbuffer context, ES 3 where the device has it, else ES 2, as the
 * app does: vertex shader, fragment shader, then link with "position"
 * bound to 0. Writes a header line with the GL strings and one JSON line
 * per text, in the order of the ids, to results.jsonl in its own external
 * files folder. Nothing else is read or written.
 *
 * Run: adb shell am instrument -w
 * de.markusfisch.android.shadereditor.typoprobe/.Probe
 */
public final class Probe extends Instrumentation {
	private static final int EGL_OPENGL_ES3_BIT_KHR = 0x40;

	@Override
	public void onCreate(Bundle arguments) {
		super.onCreate(arguments);
		start();
	}

	@Override
	public void onStart() {
		Bundle result = new Bundle();
		try {
			File out = run();
			result.putString("results", out.getAbsolutePath());
			result.putString(Instrumentation.REPORT_KEY_STREAMRESULT,
					"typo probe: wrote " + out + "\n");
			finish(Activity.RESULT_OK, result);
		} catch (Throwable t) {
			result.putString(Instrumentation.REPORT_KEY_STREAMRESULT,
					"typo probe failed: " + t + "\n");
			finish(Activity.RESULT_CANCELED, result);
		}
	}

	private File run() throws IOException {
		AssetManager assets = getContext().getAssets();
		String[] names = assets.list("texts");
		List<String> ids = new ArrayList<>();
		for (String n : names == null ? new String[0] : names) {
			if (n.endsWith(".frag")) {
				ids.add(n.substring(0, n.length() - 5));
			}
		}
		java.util.Collections.sort(ids);
		File dir = getContext().getExternalFilesDir(null);
		if (dir == null) {
			throw new IOException("no external files folder");
		}
		dir.mkdirs();
		File out = new File(dir, "results.jsonl");
		Gl gl = Gl.open();
		try (Writer w = new OutputStreamWriter(new FileOutputStream(out),
				StandardCharsets.UTF_8)) {
			StringBuilder head = new StringBuilder("{");
			field(head, "header", "typo-probe 1").append(',');
			field(head, "manufacturer", Build.MANUFACTURER).append(',');
			field(head, "model", Build.MODEL).append(',');
			field(head, "device", Build.DEVICE).append(',');
			field(head, "release", Build.VERSION.RELEASE).append(',');
			head.append("\"sdk\":").append(Build.VERSION.SDK_INT).append(',');
			head.append("\"contextVersion\":").append(gl.version).append(',');
			field(head, "GL_VENDOR", GLES20.glGetString(GLES20.GL_VENDOR))
					.append(',');
			field(head, "GL_RENDERER", GLES20.glGetString(GLES20.GL_RENDERER))
					.append(',');
			field(head, "GL_VERSION", GLES20.glGetString(GLES20.GL_VERSION))
					.append(',');
			field(head, "GL_SHADING_LANGUAGE_VERSION", GLES20.glGetString(
					GLES20.GL_SHADING_LANGUAGE_VERSION)).append(',');
			field(head, "EGL_VENDOR", EGL14.eglQueryString(gl.display,
					EGL14.EGL_VENDOR)).append(',');
			field(head, "EGL_VERSION", EGL14.eglQueryString(gl.display,
					EGL14.EGL_VERSION)).append(',');
			head.append("\"texts\":").append(ids.size());
			w.write(head.append("}\n").toString());
			int done = 0;
			for (String id : ids) {
				String frag = read(assets, "texts/" + id + ".frag");
				String vert = read(assets, "texts/" + id + ".vert");
				w.write(compile(id, vert, frag));
				if (++done % 25 == 0) {
					Bundle status = new Bundle();
					status.putString(Instrumentation.REPORT_KEY_STREAMRESULT,
							done + " of " + ids.size() + "\n");
					sendStatus(1, status);
				}
			}
		} finally {
			gl.close();
		}
		return out;
	}

	private static String compile(String id, String vert, String frag) {
		StringBuilder j = new StringBuilder("{");
		field(j, "id", id).append(',');
		long t0 = System.nanoTime();
		int vs = GLES20.glCreateShader(GLES20.GL_VERTEX_SHADER);
		GLES20.glShaderSource(vs, vert);
		GLES20.glCompileShader(vs);
		int[] ok = new int[1];
		GLES20.glGetShaderiv(vs, GLES20.GL_COMPILE_STATUS, ok, 0);
		boolean vertexOk = ok[0] != 0;
		String vertexLog = GLES20.glGetShaderInfoLog(vs);
		long t1 = System.nanoTime();
		int fs = GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER);
		GLES20.glShaderSource(fs, frag);
		GLES20.glCompileShader(fs);
		GLES20.glGetShaderiv(fs, GLES20.GL_COMPILE_STATUS, ok, 0);
		boolean fragmentOk = ok[0] != 0;
		String fragmentLog = GLES20.glGetShaderInfoLog(fs);
		long t2 = System.nanoTime();
		Boolean linkOk = null;
		String linkLog = null;
		if (vertexOk && fragmentOk) {
			int program = GLES20.glCreateProgram();
			GLES20.glAttachShader(program, vs);
			GLES20.glAttachShader(program, fs);
			GLES20.glBindAttribLocation(program, 0, "position");
			GLES20.glLinkProgram(program);
			GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, ok, 0);
			linkOk = ok[0] != 0;
			linkLog = GLES20.glGetProgramInfoLog(program);
			GLES20.glDeleteProgram(program);
		}
		long t3 = System.nanoTime();
		GLES20.glDeleteShader(vs);
		GLES20.glDeleteShader(fs);
		j.append("\"vertexOk\":").append(vertexOk).append(',');
		field(j, "vertexLog", vertexLog).append(',');
		j.append("\"fragmentOk\":").append(fragmentOk).append(',');
		field(j, "fragmentLog", fragmentLog).append(',');
		j.append("\"linkOk\":").append(linkOk).append(',');
		field(j, "linkLog", linkLog).append(',');
		j.append("\"micros\":[").append((t1 - t0) / 1000).append(',')
				.append((t2 - t1) / 1000).append(',')
				.append((t3 - t2) / 1000).append(']');
		return j.append("}\n").toString();
	}

	private static String read(AssetManager assets, String name)
			throws IOException {
		try (InputStream in = assets.open(name)) {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			byte[] buffer = new byte[8192];
			for (int n; (n = in.read(buffer)) > 0; ) {
				bytes.write(buffer, 0, n);
			}
			return bytes.toString("UTF-8");
		}
	}

	private static StringBuilder field(StringBuilder j, String k, String v) {
		j.append('"').append(k).append("\":");
		if (v == null) {
			return j.append("null");
		}
		j.append('"');
		for (char c : v.toCharArray()) {
			switch (c) {
				case '"':
					j.append("\\\"");
					break;
				case '\\':
					j.append("\\\\");
					break;
				case '\n':
					j.append("\\n");
					break;
				case '\r':
					j.append("\\r");
					break;
				case '\t':
					j.append("\\t");
					break;
				default:
					if (c < 0x20) {
						j.append(String.format("\\u%04x", (int) c));
					} else {
						j.append(c);
					}
			}
		}
		return j.append('"');
	}

	/** A current EGL context on a 1x1 pbuffer. */
	private static final class Gl {
		EGLDisplay display;
		EGLContext context;
		EGLSurface surface;
		int version;

		static Gl open() throws IOException {
			Gl gl = new Gl();
			gl.display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
			int[] v = new int[2];
			if (!EGL14.eglInitialize(gl.display, v, 0, v, 1)) {
				throw new IOException("eglInitialize failed");
			}
			for (int version : new int[]{3, 2}) {
				int bit = version == 3 ? EGL_OPENGL_ES3_BIT_KHR
						: EGL14.EGL_OPENGL_ES2_BIT;
				int[] attribs = {
						EGL14.EGL_RENDERABLE_TYPE, bit,
						EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
						EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
						EGL14.EGL_BLUE_SIZE, 8,
						EGL14.EGL_NONE};
				EGLConfig[] configs = new EGLConfig[1];
				int[] count = new int[1];
				if (!EGL14.eglChooseConfig(gl.display, attribs, 0, configs, 0,
						1, count, 0) || count[0] == 0) {
					continue;
				}
				EGLContext context = EGL14.eglCreateContext(gl.display,
						configs[0], EGL14.EGL_NO_CONTEXT, new int[]{
								EGL14.EGL_CONTEXT_CLIENT_VERSION, version,
								EGL14.EGL_NONE}, 0);
				if (context == null || context == EGL14.EGL_NO_CONTEXT) {
					continue;
				}
				EGLSurface surface = EGL14.eglCreatePbufferSurface(gl.display,
						configs[0], new int[]{EGL14.EGL_WIDTH, 1,
								EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE}, 0);
				if (!EGL14.eglMakeCurrent(gl.display, surface, surface,
						context)) {
					EGL14.eglDestroyContext(gl.display, context);
					continue;
				}
				gl.context = context;
				gl.surface = surface;
				gl.version = version;
				return gl;
			}
			throw new IOException("no ES 2 or ES 3 context: " +
					Arrays.toString(v));
		}

		void close() {
			EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE,
					EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
			EGL14.eglDestroySurface(display, surface);
			EGL14.eglDestroyContext(display, context);
			EGL14.eglTerminate(display);
		}
	}
}

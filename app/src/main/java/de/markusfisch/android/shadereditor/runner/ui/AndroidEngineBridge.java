package de.markusfisch.android.shadereditor.runner.ui;

import android.content.Context;
import android.opengl.GLSurfaceView;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

import de.markusfisch.android.shadereditor.R;
import de.markusfisch.android.shadereditor.engine.AssetStreamProvider;
import de.markusfisch.android.shadereditor.engine.EngineController;
import de.markusfisch.android.shadereditor.engine.Plugin;
import de.markusfisch.android.shadereditor.engine.asset.AssetProvider;
import de.markusfisch.android.shadereditor.engine.asset.ShaderAsset;
import de.markusfisch.android.shadereditor.engine.asset.TextureAsset;
import de.markusfisch.android.shadereditor.engine.data.DataProviderManager;
import de.markusfisch.android.shadereditor.engine.util.observer.ObservableValue;
import de.markusfisch.android.shadereditor.opengl.ShaderError;
import de.markusfisch.android.shadereditor.opengl.ShaderRenderer;
import de.markusfisch.android.shadereditor.platform.asset.AndroidAssetStreamProvider;
import de.markusfisch.android.shadereditor.platform.asset.AndroidTextureLoader;
import de.markusfisch.android.shadereditor.platform.asset.ShaderAssetLoader;
import de.markusfisch.android.shadereditor.platform.plugin.AndroidDataPlugin;
import de.markusfisch.android.shadereditor.platform.render.gl.GlesRenderer;
import de.markusfisch.android.shadereditor.runner.plugin.ShaderRunnerPlugin;
import de.markusfisch.android.shadereditor.widget.ShaderView;

public class AndroidEngineBridge {

	/**
	 * Listener for events related to the execution and performance from a shader.
	 * Implement this interface to receive updates about frame rates, shader compilation
	 * status, and rendering quality adjustments, regardless from where the shader is being
	 * displayed.
	 */
	public interface ShaderExecutionListener {

		/**
		 * Called periodically to report the current rendering frame rate.
		 *
		 * @param fps The number from frames rendered in the last measurement interval.
		 */
		void onFramesPerSecond(int fps);

		/**
		 * Called after a shader compilation attempt.
		 *
		 * @param compilationIssues A list from {@link ShaderError} objects detailing any issues
		 *                          found during compilation. The list is empty if compilation
		 *                          was successful without any reportable issues.
		 *                          Implementers should not modify this list.
		 */
		void onShaderCompilationResult(@NonNull List<ShaderError> compilationIssues);

		/**
		 * Called when the rendering quality for the shader has been changed or is requested to
		 * change.
		 * The listener implementation should react accordingly if it's responsible for applying
		 * the quality setting.
		 *
		 * @param quality The new or requested rendering quality factor (e.g., resolution scale).
		 */
		void onRenderQualityChanged(float quality);
	}

	// A simple default vertex shader
	private static final String DEFAULT_VERTEX_SHADER_ES3 = """
			precision mediump float;
			layout (location = 0) in vec4 a_Position;
			layout (location = 1) in vec2 a_TexCoord;
			out vec2 v_TexCoord;
			void main() {
			    gl_Position = a_Position;
			    v_TexCoord = a_TexCoord;
			}
			""";
	private static final String DEFAULT_VERTEX_SHADER_ES2 = """
			attribute vec4 a_Position;
			attribute vec2 a_TexCoord;
			varying vec2 v_TexCoord;
			void main() {
			    gl_Position = a_Position;
			    v_TexCoord = a_TexCoord;
			}
			""";

	// A simple default fragment shader that uses time and resolution
	private static final String DEFAULT_FRAGMENT_SHADER_ES3 = """
			#version 320 es
			precision mediump float;
			in vec2 v_TexCoord;
			out vec4 fragColor;
			uniform vec2 u_resolution;
			uniform float u_time;
			
			void main() {
			    vec2 st = gl_FragCoord.xy/u_resolution.xy;
			    fragColor = vec4(st.x, st.y, 0.5 + 0.5 * sin(u_time), 1.0);
			}
			""";
	private static final String DEFAULT_FRAGMENT_SHADER_ES2 = """
			precision mediump float;
			varying vec2 v_TexCoord;
			uniform vec2 u_resolution;
			uniform float u_time;
			
			void main() {
			    vec2 st = gl_FragCoord.xy/u_resolution.xy;
			    gl_FragColor = vec4(st.x, st.y, 0.5 + 0.5 * sin(u_time), 1.0);
			}
			""";
	// Pattern to match any character that is not a newline (\n), tab (\t),
	// or a printable ASCII character (space to ~).
	private static final Pattern NON_PRINTABLE_ASCII_PATTERN =
			Pattern.compile("[^\\t\\n\\x20-\\x7E]");
	@NonNull
	private final ShaderView shaderView;
	@NonNull
	private final Spinner qualitySpinner;
	@NonNull
	private final ShaderExecutionListener shaderExecutionListener;
	@NonNull
	private final Context context;
	@NonNull
	private final RendererBridge rendererBridge;
	private final ObservableValue<ShaderAsset> shaderAsset = ObservableValue.of(new ShaderAsset(
			DEFAULT_VERTEX_SHADER_ES2,
			DEFAULT_FRAGMENT_SHADER_ES2
	));
	@NonNull
	private float[] qualityValues;
	private float quality = 1f;

	public AndroidEngineBridge(@NonNull Context context, @NonNull ShaderView shaderView,
			@NonNull Spinner qualitySpinner,
			@NonNull ShaderExecutionListener shaderExecutionListener) {
		this.context = context;
		this.shaderView = shaderView;
		AssetStreamProvider androidAssetStreamProvider = new AndroidAssetStreamProvider(context);
		AssetStreamProvider assetStreamProvider = identifier -> {
			String requestedPath = identifier.getPath();
			String mainGlslPath = URI.create("./main.frag").getPath();

			if (mainGlslPath.equals(requestedPath)) {
				return new ByteArrayInputStream(shaderAsset.get().fragmentSource().getBytes());
			}
			return androidAssetStreamProvider.openStream(identifier);
		};
		this.rendererBridge = new RendererBridge(
				List.of(
						() -> new AndroidDataPlugin(context),
						ShaderRunnerPlugin::new
				),
				assetStreamProvider);
		shaderView.setEGLContextClientVersion(2);
		// shaderView.setEGLContextFactory(new ContextFactory());
		shaderView.setRenderer(rendererBridge);
		this.qualitySpinner = qualitySpinner;
		this.shaderExecutionListener = shaderExecutionListener;
		initQualitySpinner();
		initShaderView();
		initLifecycleListeners();
	}

	public void onPause() {
		if (shaderView.getVisibility() == View.VISIBLE) {
			shaderView.onPause();
			rendererBridge.destroy();
		}
	}

	public void onResume() {
		if (shaderView.getVisibility() == View.VISIBLE) {
			shaderView.onResume();
		}
	}

	public void setVisibility(boolean visible) {
		shaderView.setVisibility(visible ? View.VISIBLE : View.GONE);
	}

	public byte[] getThumbnail() {
		return shaderView.getRenderer().getThumbnail();
	}

	public void setQuality(float quality) {
		for (int i = 0; i < qualityValues.length; ++i) {
			if (qualityValues[i] == quality) {
				qualitySpinner.setSelection(i);
				this.quality = quality;
				return;
			}
		}
	}

	public void setFragmentShader(@Nullable String src) {
		if (src == null) {
			src = DEFAULT_FRAGMENT_SHADER_ES3;
		}
		shaderAsset.set(
				new ShaderAsset(shaderAsset.get().vertexSource(), removeNonAscii(src)));
		shaderView.setFragmentShader(src, quality);
	}

	private void initLifecycleListeners() {
		shaderView.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
			@Override
			public void onViewAttachedToWindow(@NonNull View v) {
				// No action needed on attach.
			}

			@Override
			public void onViewDetachedFromWindow(@NonNull View v) {
				// The view is being removed from the window.
				// This is the ideal place for final cleanup.
				rendererBridge.destroy();
				// Clean up the listener itself.
				v.removeOnAttachStateChangeListener(this);
			}
		});
	}

	private void initShaderView() {
		shaderView.getRenderer().setOnRendererListener(new ShaderRenderer.OnRendererListener() {
			@Override
			public void onFramesPerSecond(int fps) {
				shaderExecutionListener.onFramesPerSecond(fps);
			}

			@Override
			public void onInfoLog(@NonNull List<ShaderError> infoLog) {
				shaderExecutionListener.onShaderCompilationResult(infoLog);
			}
		});
	}

	private void initQualitySpinner() {
		String[] qualityStringValues =
				context.getResources().getStringArray(R.array.quality_values);
		qualityValues = new float[qualityStringValues.length];
		for (int i = 0; i < qualityStringValues.length; ++i) {
			qualityValues[i] = Float.parseFloat(qualityStringValues[i]);
		}

		ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(context,
				R.array.quality_names, android.R.layout.simple_spinner_item);
		adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
		qualitySpinner.setAdapter(adapter);
		qualitySpinner.setOnItemSelectedListener(new Spinner.OnItemSelectedListener() {
			@Override
			public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
				float q = qualityValues[position];
				if (q == quality) return;
				quality = q;
				shaderExecutionListener.onRenderQualityChanged(quality);
				// Refresh renderer with new quality
				shaderView.getRenderer().setQuality(quality);
				shaderView.onPause();
				shaderView.onResume();
			}

			@Override
			public void onNothingSelected(AdapterView<?> parent) {
			}
		});
	}


	/**
	 * Removes non-ASCII characters from the given string.
	 * <p>
	 * This method uses a pre-compiled pattern to identify and remove any characters
	 * that are not a newline (\n), tab (\t), or a printable ASCII character
	 * (from space to tilde).
	 *
	 * @param text The string to process.
	 * @return A new string with non-ASCII characters removed.
	 */
	@NonNull
	private static String removeNonAscii(@NonNull String text) {
		return NON_PRINTABLE_ASCII_PATTERN.matcher(text).replaceAll("");
	}

	/**
	 * A thin bridge between the Android GLSurfaceView and the rendering EngineController.
	 * Its sole responsibility is to delegate rendering lifecycle events to the engineController.
	 */
	private static class RendererBridge implements GLSurfaceView.Renderer {

		// Define the pattern as a static final constant to avoid recompiling it on every call.
		private static final Pattern SHADER_VERSION_PATTERN = Pattern.compile(
				"^\\s*#version\\s+(\\d+)(?:\\s+(\\w+))?");
		private final Collection<Supplier<Plugin>> plugins;
		private final AssetStreamProvider assetStreamProvider;
		@Nullable
		private EngineController engineController;

		/**
		 * Constructs the bridge.
		 *
		 * @param plugins A list from plugins to be registered with the engineController.
		 */
		public RendererBridge(@NonNull Collection<Supplier<Plugin>> plugins,
				@NonNull AssetStreamProvider assetStreamProvider) {
			this.plugins = plugins;
			this.assetStreamProvider = assetStreamProvider;
		}

		@Override
		public void onSurfaceCreated(GL10 gl, EGLConfig config) {
			if (engineController != null) {
				engineController.shutdown();
			}
			engineController = createEngineController();

			// 2. Forward the setup call to the engineController
			engineController.setup();
		}

		@Override
		public void onSurfaceChanged(GL10 gl, int width, int height) {
			if (engineController != null) {
				engineController.setViewport(width, height);
			}
		}

		@Override
		public void onDrawFrame(GL10 gl) {
			if (engineController != null) {
				engineController.renderFrame();
			}
		}

		public void destroy() {
			if (engineController != null) {
				engineController.shutdown();
				engineController = null;
			}
		}

		@NonNull
		private EngineController createEngineController() {
			var renderer = new GlesRenderer();
			var assetProvider = new AssetProvider(assetStreamProvider);
			assetProvider.setLocator((name, type) -> {
				if (type == TextureAsset.class) return URI.create("db://" + name);
				if (type == ShaderAsset.class) return URI.create("./" + name + ".frag");
				return URI.create(name);
			});
			var engine = new EngineController(
					new DataProviderManager(),
					assetProvider,
					renderer,
					renderer);
			var facade = engine.getFacade();

			facade.registerAssetLoader(ShaderAsset.class,
					new ShaderAssetLoader(this::selectVertexShader));
			facade.registerAssetLoader(TextureAsset.class, new AndroidTextureLoader());

			for (var plugin : plugins) {
				facade.registerPlugin(plugin.get());
			}
			return engine;
		}

		/**
		 * Selects the appropriate vertex shader based on the GLSL version directive
		 * found in the fragment shader's source code.
		 *
		 * @param fragmentSource The source code from the fragment shader.
		 * @return The full source code for the appropriate vertex shader.
		 */
		@NonNull
		private String selectVertexShader(@NonNull String fragmentSource) {
			var matcher = SHADER_VERSION_PATTERN.matcher(fragmentSource);

			if (!matcher.find()) {
				// For shaders with no version directive, fall back to the legacy ES2 template.
				return DEFAULT_VERTEX_SHADER_ES2;
			}

			// If we are here, a match was found. The regex `(\\d+)` ensures group(1) is not null.
			try {
				//noinspection DataFlowIssue
				String versionDirective = matcher.group(0).trim();
				//noinspection DataFlowIssue
				int versionNumber = Integer.parseInt(matcher.group(1));

				// GLSL versions >= 300 support modern 'in'/'out' keywords.
				if (versionNumber >= 300) {
					return versionDirective + "\n" + DEFAULT_VERTEX_SHADER_ES3;
				}
			} catch (NumberFormatException e) {
				// This could only happen if the version number is too large for an int,
				// which is highly unlikely for a GLSL version. We'll safely fall through.
			}

			// Fall back to the legacy ES2 template for older versions (e.g., "100 es").
			return DEFAULT_VERTEX_SHADER_ES2;
		}
	}
}
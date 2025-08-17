// platform/ui/AndroidEngineBridge.java
package de.markusfisch.android.shadereditor.platform.ui;

import android.content.Context;
import android.opengl.GLSurfaceView;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;

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
import de.markusfisch.android.shadereditor.engine.data.Vec2;
import de.markusfisch.android.shadereditor.engine.error.EngineError;
import de.markusfisch.android.shadereditor.engine.error.EngineException;
import de.markusfisch.android.shadereditor.engine.util.observer.ObservableValue;
import de.markusfisch.android.shadereditor.engine.util.observer.ReadOnlyObservable;
import de.markusfisch.android.shadereditor.platform.asset.AndroidAssetStreamProvider;
import de.markusfisch.android.shadereditor.platform.asset.AndroidTextureLoader;
import de.markusfisch.android.shadereditor.platform.asset.ShaderAssetLoader;
import de.markusfisch.android.shadereditor.platform.data.PlatformBindingCatalog;
import de.markusfisch.android.shadereditor.platform.plugin.AndroidDataPlugin;
import de.markusfisch.android.shadereditor.platform.plugin.DeviceStatePlugin;
import de.markusfisch.android.shadereditor.platform.plugin.InteractionPlugin;
import de.markusfisch.android.shadereditor.platform.render.gl.GlesRenderer;

public class AndroidEngineBridge {

	/**
	 * Listener for events related to the execution of a shader.
	 * Implement this interface to receive updates about shader compilation
	 * status and rendering quality adjustments.
	 */
	public interface ShaderExecutionListener {
		void onEngineError(@NonNull List<EngineError> errors);

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
			#version 300 es
			precision mediump float;
			in vec2 v_TexCoord;
			out vec4 fragColor;
			uniform vec2 resolution;
			uniform float time;
			
			void main() {
			    vec2 st = gl_FragCoord.xy/resolution.xy;
			    fragColor = vec4(st.x, st.y, 0.5 + 0.5 * sin(time), 1.0);
			}
			""";
	private static final String DEFAULT_FRAGMENT_SHADER_ES2 = """
			precision mediump float;
			uniform vec2 resolution;
			uniform float time;
			
			void main() {
			    vec2 st = gl_FragCoord.xy/resolution.xy;
			    gl_FragColor = vec4(st.x, st.y, 0.5 + 0.5 * sin(time), 1.0);
			}
			""";
	// Pattern to match any character that is not a newline (\n), tab (\t),
	// or a printable ASCII character (space to ~).
	private static final Pattern NON_PRINTABLE_ASCII_PATTERN =
			Pattern.compile("[^\\t\\n\\x20-\\x7E]");
	@NonNull
	private final GLSurfaceView glSurfaceView;
	@Nullable
	private final Spinner qualitySpinner;
	@NonNull
	private final ShaderExecutionListener shaderExecutionListener;
	@NonNull
	private final Context context;
	@NonNull
	private final RendererBridge rendererBridge;
	private final ObservableValue<Vec2> touchPositionObservable = ObservableValue.of(new Vec2());
	private final ObservableValue<Vec2> wallpaperOffsetObservable = ObservableValue.of(new Vec2());
	private final ObservableValue<ShaderAsset> shaderAsset = ObservableValue.of(new ShaderAsset(
			DEFAULT_VERTEX_SHADER_ES2,
			DEFAULT_FRAGMENT_SHADER_ES2
	));
	private final ObservableValue<Integer> fps = ObservableValue.of(0);
	@NonNull
	private float[] qualityValues;
	private float quality = 1f;

	public AndroidEngineBridge(@NonNull Context context, @NonNull GLSurfaceView glSurfaceView,
			@Nullable Spinner qualitySpinner,
			@NonNull ShaderExecutionListener shaderExecutionListener,
			@NonNull Collection<Supplier<Plugin>> plugins) {
		this.context = context;
		this.glSurfaceView = glSurfaceView;

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
				Stream.concat(
						Stream.of(
								() -> new AndroidDataPlugin(context),
								() -> new DeviceStatePlugin(context),
								() -> new InteractionPlugin(
										touchPositionObservable,
										wallpaperOffsetObservable
								)
						),
						plugins.stream()
				).toList()
				,
				assetStreamProvider,
				this.glSurfaceView,
				shaderExecutionListener,
				this.fps,
				() -> this.quality);

		glSurfaceView.setEGLContextClientVersion(3);
		glSurfaceView.setRenderer(rendererBridge);
		glSurfaceView.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
		glSurfaceView.setOnTouchListener(this::onTouchEvent);

		this.qualitySpinner = qualitySpinner;
		this.shaderExecutionListener = shaderExecutionListener;
		initQualitySpinner();
		initLifecycleListeners();
	}

	public void onPause() {
		if (glSurfaceView.getVisibility() == View.VISIBLE) {
			glSurfaceView.onPause();
			rendererBridge.destroy();
		}
	}

	public void onResume() {
		if (glSurfaceView.getVisibility() == View.VISIBLE) {
			glSurfaceView.onResume();
		}
	}

	public void setVisibility(boolean visible) {
		glSurfaceView.setVisibility(visible ? View.VISIBLE : View.GONE);
	}

	@Nullable
	public byte[] getThumbnail() {
		return rendererBridge.getThumbnail();
	}

	public ReadOnlyObservable<Integer> getFps() {
		return fps;
	}

	public void setQuality(float quality) {
		for (int i = 0; i < qualityValues.length; ++i) {
			if (qualityValues[i] == quality) {
				if (qualitySpinner != null) {
					qualitySpinner.setSelection(i);
				}
				this.quality = quality;
				return;
			}
		}
	}

	public void setFragmentShader(@Nullable String src) {
		if (src == null || src.isBlank()) {
			src = DEFAULT_FRAGMENT_SHADER_ES2;
		}
		shaderAsset.set(
				new ShaderAsset(shaderAsset.get().vertexSource(), removeNonAscii(src)));

		// Trigger a recreation of the GL context and the engine.
		// This is the simplest way to ensure a clean state and is the
		// core of the "destroy and try again" strategy.
		if (glSurfaceView.getVisibility() == View.VISIBLE) {
			glSurfaceView.onPause();
			glSurfaceView.onResume();
		}
	}

	/**
	 * Forwards wallpaper offset changes to the interaction plugin.
	 *
	 * @param xOffset The horizontal offset.
	 * @param yOffset The vertical offset.
	 */
	public void onOffsetsChanged(float xOffset, float yOffset) {
		wallpaperOffsetObservable.set(new Vec2(xOffset, yOffset));
	}

	/**
	 * Forwards touch events to the interaction plugin.
	 *
	 * @param event The motion event from the view.
	 */
	private boolean onTouchEvent(View v, @NonNull MotionEvent event) {
		if (event.getAction() == MotionEvent.ACTION_DOWN ||
				event.getAction() == MotionEvent.ACTION_MOVE) {
			touchPositionObservable.set(new Vec2(event.getX(), event.getY()));
		}
		return true;
	}

	private void initLifecycleListeners() {
		glSurfaceView.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
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
		if (qualitySpinner != null) {
			qualitySpinner.setAdapter(adapter);
			qualitySpinner.setOnItemSelectedListener(new Spinner.OnItemSelectedListener() {
				@Override
				public void onItemSelected(
						AdapterView<?> parent, View view, int position, long id) {
					float q = qualityValues[position];
					if (q == quality) return;
					quality = q;
					shaderExecutionListener.onRenderQualityChanged(quality);
					// Refresh renderer with new quality
					setFragmentShader(shaderAsset.get().fragmentSource());
				}

				@Override
				public void onNothingSelected(AdapterView<?> parent) {
				}
			});
		}
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

		public static final long ONE_SECOND_NANOS = Duration.ofSeconds(1).toNanos();
		// Define the pattern as a static final constant to avoid recompiling it on every call.
		private static final Pattern SHADER_VERSION_PATTERN = Pattern.compile(
				"^\\s*#version\\s+(\\d+)(?:\\s+(\\w+))?");
		private final Collection<Supplier<Plugin>> plugins;
		private final AssetStreamProvider assetStreamProvider;
		private final GLSurfaceView glSurfaceView;
		private final ShaderExecutionListener listener;
		private final List<EngineError> engineErrors = new ArrayList<>();
		private final ObservableValue<Integer> fps;
		private final Supplier<Float> qualityProvider;
		@NonNull
		private final GlesRenderer renderer;
		@Nullable
		private EngineController engineController;
		private int frameCount = 0;
		private long lastFpsTimestamp = 0;

		public RendererBridge(@NonNull Collection<Supplier<Plugin>> plugins,
				@NonNull AssetStreamProvider assetStreamProvider,
				@NonNull GLSurfaceView glSurfaceView,
				@NonNull ShaderExecutionListener listener,
				@NonNull ObservableValue<Integer> fps, Supplier<Float> qualityProvider) {
			this.plugins = plugins;
			this.assetStreamProvider = assetStreamProvider;
			this.glSurfaceView = glSurfaceView;
			this.listener = listener;
			this.renderer = new GlesRenderer();
			this.fps = fps;
			this.qualityProvider = qualityProvider;
		}

		@Override
		public void onSurfaceCreated(GL10 gl, EGLConfig config) {
			destroy(); // Ensure previous engine is gone.
			engineErrors.clear();

			frameCount = 0;
			lastFpsTimestamp = System.nanoTime();

			try {
				engineController = createEngineController();
				engineController.setup();
			} catch (EngineException e) {
				Log.e("AndroidEngineBridge", "Error during engine setup", e);
				engineErrors.add(e.getError());
				destroy();
			}
			var errors = List.copyOf(engineErrors);
			glSurfaceView.post(() -> listener.onEngineError(errors));
		}

		@Override
		public void onSurfaceChanged(GL10 gl, int width, int height) {
			if (engineController != null) {
				engineController.setViewport(width, height);
			}
		}

		@Override
		public void onDrawFrame(GL10 gl) {
			if (engineController == null) {
				return;
			}
			try {
				engineController.renderFrame();

				frameCount++;
				long now = System.nanoTime();
				if (now - lastFpsTimestamp >= ONE_SECOND_NANOS) {
					fps.set(frameCount);
					frameCount = 0;
					lastFpsTimestamp = now;
				}

				glSurfaceView.requestRender();
			} catch (EngineException e) {
				Log.e("AndroidEngineBridge", "Error during render frame", e);
				engineErrors.add(e.getError());
				var errors = List.copyOf(engineErrors);
				glSurfaceView.post(() -> listener.onEngineError(errors));
				destroy(); // Destroy broken engine.
			}
		}

		public void destroy() {
			if (engineController != null) {
				engineController.shutdown();
				engineController = null;
				fps.set(-1);
			}
		}

		@Nullable
		public byte[] getThumbnail() {
			return renderer.getThumbnail();
		}

		@NonNull
		private EngineController createEngineController() {
			var assetProvider = new AssetProvider(assetStreamProvider);
			assetProvider.setLocator((name, type) -> {
				if (type == TextureAsset.class) return URI.create("db://" + name);
				if (type == ShaderAsset.class) return URI.create("./" + name + ".frag");
				return URI.create(name);
			});
			// The bridge gets the bindings from the platform catalog and injects
			// them into the engine controller.
			var defaultBindings = PlatformBindingCatalog.getUniformBindings();
			var engine = new EngineController(
					new DataProviderManager(),
					assetProvider,
					renderer,
					renderer,
					defaultBindings,
					qualityProvider.get());
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
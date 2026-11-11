package de.markusfisch.android.shadereditor.opengl;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;

import de.markusfisch.android.shadereditor.project.ProjectDiagnostic;
import de.markusfisch.android.shadereditor.project.RenderPlan.Sampling;

final class RendererProgramManager {
	record ReloadResult(
			@NonNull List<ProjectDiagnostic> programErrors,
			boolean succeeded) {
		@NonNull
		static ReloadResult success() {
			return new ReloadResult(List.of(), true);
		}

		@NonNull
		static ReloadResult failure(@NonNull List<ProjectDiagnostic> programErrors) {
			return new ReloadResult(List.copyOf(programErrors), false);
		}
	}

	private static final String FULL_SCREEN_VERTEX_SHADER = """
			attribute vec2 position;
			void main() {
				gl_Position = vec4(position, 0., 1.);
			}
			""";
	private static final String FULL_SCREEN_VERTEX_SHADER_3 = """
			in vec2 position;
			void main() {
				gl_Position = vec4(position, 0., 1.);
			}
			""";
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

	@Nullable
	private String sourceText;
	@NonNull
	private PreparedShaderSource preparedShaderSource = PreparedShaderSource.empty();
	@NonNull
	private ShaderTextureResources textureResources =
			ShaderTextureResources.empty();
	private int version = 2;
	@Nullable
	private ShaderRenderPipeline.SurfaceProgram surfaceProgram;
	@Nullable
	private GlProgram mainProgram;

	void setVersion(int version) {
		this.version = version;
		prepareShaderSource(sourceText);
	}

	void setFragmentShader(@Nullable String source) {
		sourceText = source;
		prepareShaderSource(source);
	}

	boolean hasPreparedShader() {
		var fragmentShader = preparedShaderSource.getFragmentShader();
		return fragmentShader != null && !fragmentShader.getSource().isEmpty();
	}

	@NonNull
	ReloadResult reload(@NonNull GlDevice device) {
		clearPrograms();

		textureResources.createExternalTextures(device);
		var surfaceResult = device.createProgram(
				FULL_SCREEN_VERTEX_SHADER,
				SURFACE_FRAGMENT_SHADER,
				ShaderLineMapping.identity());
		if (!surfaceResult.succeeded() || surfaceResult.getProgram() == null) {
			return ReloadResult.failure(surfaceResult.getInfoLog());
		}

		var fragmentShader = preparedShaderSource.getFragmentShader();
		if (fragmentShader == null) {
			device.deleteProgram(surfaceResult.getProgram());
			return ReloadResult.failure(List.of());
		}

		var mainResult = device.createProgram(
				preparedShaderSource.getVertexShader(
						FULL_SCREEN_VERTEX_SHADER,
						FULL_SCREEN_VERTEX_SHADER_3,
						version),
				fragmentShader.getSource(),
				fragmentShader.getLineMapping());
		if (!mainResult.succeeded() || mainResult.getProgram() == null) {
			device.deleteProgram(surfaceResult.getProgram());
			return ReloadResult.failure(mainResult.getInfoLog());
		}

		surfaceProgram = ShaderRenderPipeline.SurfaceProgram.of(
				device,
				surfaceResult.getProgram());
		mainProgram = mainResult.getProgram();
		return ReloadResult.success();
	}

	void discardContextResources() {
		clearPrograms();
		textureResources.discard();
	}

	@Nullable
	ShaderRenderPipeline.SurfaceProgram getSurfaceProgram() {
		return surfaceProgram;
	}

	@Nullable
	GlProgram getMainProgram() {
		return mainProgram;
	}

	@NonNull
	Sampling getBackBufferSampling() {
		return preparedShaderSource.getBackBufferSampling();
	}

	@NonNull
	BackBufferParameters getBackBufferParameters() {
		return preparedShaderSource.getBackBufferParameters();
	}

	float getFTimeMax() {
		return preparedShaderSource.getFTimeMax();
	}

	@NonNull
	ShaderTextureResources getTextureResources() {
		return textureResources;
	}

	private void prepareShaderSource(@Nullable String source) {
		preparedShaderSource = ShaderSourcePreparer.prepare(source, version);
		textureResources = ShaderTextureResources.create(
				preparedShaderSource.getSamplers());
	}

	private void clearPrograms() {
		surfaceProgram = null;
		mainProgram = null;
	}
}

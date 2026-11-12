package de.markusfisch.android.shadereditor.opengl;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;

import de.markusfisch.android.shadereditor.project.RenderPlan.Sampling;

final class PreparedShaderSource {
	@Nullable
	private final PreparedShaderInput fragmentShader;
	private final float fTimeMax;
	@Nullable
	private final String gles3VersionDirective;
	@NonNull
	private final Sampling backBufferSampling;
	@NonNull
	private final BackBufferParameters backBufferParameters;
	@NonNull
	private final List<DiscoveredSampler> samplers;

	PreparedShaderSource(
			@Nullable PreparedShaderInput fragmentShader,
			float fTimeMax,
			@Nullable String gles3VersionDirective,
			@NonNull Sampling backBufferSampling,
			@NonNull BackBufferParameters backBufferParameters,
			@NonNull List<DiscoveredSampler> samplers) {
		this.fragmentShader = fragmentShader;
		this.fTimeMax = fTimeMax;
		this.gles3VersionDirective = gles3VersionDirective;
		this.backBufferSampling = backBufferSampling;
		this.backBufferParameters = backBufferParameters;
		this.samplers = List.copyOf(samplers);
	}

	@Nullable
	PreparedShaderInput getFragmentShader() {
		return fragmentShader;
	}

	float getFTimeMax() {
		return fTimeMax;
	}

	@NonNull
	Sampling getBackBufferSampling() {
		return backBufferSampling;
	}

	@NonNull
	BackBufferParameters getBackBufferParameters() {
		return backBufferParameters;
	}

	@NonNull
	List<DiscoveredSampler> getSamplers() {
		return samplers;
	}

	@NonNull
	String getVertexShader(
			@NonNull String vertexShader,
			@NonNull String vertexShader3,
			int version) {
		return version == 3 && gles3VersionDirective != null
				? gles3VersionDirective + "\n" + vertexShader3
				: vertexShader;
	}
}

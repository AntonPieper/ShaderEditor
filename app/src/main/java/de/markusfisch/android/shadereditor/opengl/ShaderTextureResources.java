package de.markusfisch.android.shadereditor.opengl;

import android.graphics.Bitmap;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

import de.markusfisch.android.shadereditor.project.ProjectDiagnostic;

final class ShaderTextureResources {
	static final class SamplerTextureBinding {
		@NonNull
		private final DiscoveredSampler sampler;
		@Nullable
		private GlTexture texture;
		private int unit = GlUniform.NO_UNIT;

		SamplerTextureBinding(@NonNull DiscoveredSampler sampler) {
			this.sampler = sampler;
		}

		@NonNull
		DiscoveredSampler sampler() {
			return sampler;
		}

		@Nullable
		GlTexture texture() {
			return texture;
		}

		void setTexture(@Nullable GlTexture texture) {
			this.texture = texture;
		}
	}

	@NonNull
	private final List<SamplerTextureBinding> bindings;

	private ShaderTextureResources(@NonNull List<SamplerTextureBinding> bindings) {
		this.bindings = bindings;
	}

	@NonNull
	static ShaderTextureResources create(@NonNull List<DiscoveredSampler> samplers) {
		var bindings = new ArrayList<SamplerTextureBinding>(samplers.size());
		for (var sampler : samplers) {
			bindings.add(new SamplerTextureBinding(sampler));
		}
		return new ShaderTextureResources(bindings);
	}

	@NonNull
	static ShaderTextureResources empty() {
		return new ShaderTextureResources(List.of());
	}

	void discard() {
		for (var binding : bindings) {
			binding.setTexture(null);
		}
	}

	void release(@NonNull GlDevice device) {
		for (var binding : bindings) {
			device.deleteTexture(binding.texture());
			binding.setTexture(null);
		}
	}

	@NonNull
	List<DiscoveredSampler> getSamplers() {
		var samplers = new ArrayList<DiscoveredSampler>(bindings.size());
		for (var binding : bindings) {
			samplers.add(binding.sampler());
		}
		return samplers;
	}

	void createExternalTextures(@NonNull GlDevice device) {
		release(device);
		for (var binding : bindings) {
			if (binding.sampler().target() == GLES11Ext.GL_TEXTURE_EXTERNAL_OES) {
				loadExternalTexture(device, binding);
			}
		}
	}

	// Replaces every 2D and cubemap texture at once with what was decoded
	// from getSamplers().
	@NonNull
	List<ProjectDiagnostic> upload(
			@NonNull GlDevice device,
			@NonNull DecodedTextures textures) {
		var errors = new ArrayList<ProjectDiagnostic>();
		for (int i = 0; i < bindings.size(); ++i) {
			var binding = bindings.get(i);
			int target = binding.sampler().target();
			if (target == GLES11Ext.GL_TEXTURE_EXTERNAL_OES) {
				continue;
			}
			device.deleteTexture(binding.texture());
			binding.setTexture(null);

			var bitmaps = textures.get(i);
			if (bitmaps == null) {
				continue;
			}
			switch (target) {
				case GLES20.GL_TEXTURE_2D -> loadTexture2D(
						device,
						binding,
						errors,
						bitmaps[0]);
				case GLES20.GL_TEXTURE_CUBE_MAP -> loadCubemap(
						device,
						binding,
						bitmaps,
						errors);
				default -> {
				}
			}
		}
		return errors;
	}

	void resolveUnits(@NonNull GlProgram program) {
		for (var binding : bindings) {
			binding.unit = program.getTextureUnit(binding.sampler().name());
		}
	}

	void bind(@NonNull GlDevice device) {
		for (var binding : bindings) {
			device.bindTexture(binding.unit, binding.texture());
		}
	}

	@Nullable
	SamplerTextureBinding getFirstBinding(@NonNull String... samplerNames) {
		for (var binding : bindings) {
			for (String samplerName : samplerNames) {
				if (binding.sampler().name().equals(samplerName)) {
					return binding;
				}
			}
		}
		return null;
	}

	private static void loadExternalTexture(
			@NonNull GlDevice device,
			@NonNull SamplerTextureBinding binding) {
		var sampler = binding.sampler();
		var texture = device.createExternalTexture();
		device.applySampling(texture, sampler.sampling());
		binding.setTexture(texture);
	}

	private static void loadTexture2D(
			@NonNull GlDevice device,
			@NonNull SamplerTextureBinding binding,
			@NonNull List<ProjectDiagnostic> errors,
			@NonNull Bitmap bitmap) {
		var sampler = binding.sampler();
		var texture = device.createTexture2D();
		device.applySampling(texture, sampler.sampling());
		var message = device.uploadTexture2D(texture, bitmap, true);
		if (message != null) {
			errors.add(ProjectDiagnostic.error(message));
			device.deleteTexture(texture);
			return;
		}
		device.generateMipmap(texture);
		binding.setTexture(texture);
	}

	private static void loadCubemap(
			@NonNull GlDevice device,
			@NonNull SamplerTextureBinding binding,
			@NonNull Bitmap[] faces,
			@NonNull List<ProjectDiagnostic> errors) {
		var sampler = binding.sampler();
		var texture = device.createCubemap();
		device.applySampling(texture, sampler.sampling());
		var message = device.uploadCubemap(texture, faces);
		if (message != null) {
			errors.add(ProjectDiagnostic.error(message));
			device.deleteTexture(texture);
			return;
		}
		device.generateMipmap(texture);
		binding.setTexture(texture);
	}
}

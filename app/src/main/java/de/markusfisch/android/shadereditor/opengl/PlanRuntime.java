package de.markusfisch.android.shadereditor.opengl;

import android.content.Context;
import android.opengl.GLES11Ext;
import android.opengl.GLES30;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import de.markusfisch.android.shadereditor.project.Preparation;
import de.markusfisch.android.shadereditor.project.ProjectDiagnostic;
import de.markusfisch.android.shadereditor.project.ProjectLimits;
import de.markusfisch.android.shadereditor.project.ProjectPath;
import de.markusfisch.android.shadereditor.project.RenderPlan.Sampling;

// A prepared single-pass plan linked in the current context, with the
// images its inputs read and how each input samples its image. It is
// ready to draw once every image is uploaded, and fails as a whole when
// one cannot be; it owns its GL objects and is built again from the plan
// in a new context.
final class PlanRuntime implements EngineBuild {
	@NonNull
	private final GlProgram program;
	private final float fTimeMax;
	private final float quality;
	@NonNull
	private final GlDevice device;
	@NonNull
	private final Preparation preparation;
	@NonNull
	private final PlanBindings bindings;
	private final int glesVersion;
	// Told with the outcome, whatever it is.
	@NonNull
	private final List<ProjectDiagnostic> warnings = new ArrayList<>();
	private final Map<ProjectPath, GlTexture2D> textures = new HashMap<>();
	private final TextureHandOver<DecodedImages> decoded =
			new TextureHandOver<>();
	@Nullable
	private final ShaderTextureResources.SamplerTextureBinding camera;
	// Per image binding on a GLES3 context, else null.
	@Nullable
	private int[] samplers;
	private boolean complete;
	private boolean failed;

	private PlanRuntime(
			@NonNull GlDevice device,
			@NonNull PlanShader shader,
			@NonNull GlProgram program,
			float fTimeMax,
			int glesVersion) {
		this.device = device;
		this.preparation = shader.preparation();
		this.quality = shader.quality();
		this.program = program;
		this.fTimeMax = fTimeMax;
		this.glesVersion = glesVersion;
		var pass = preparation.plan().passes().get(preparation.plan().output());
		bindings = PlanBindings.of(pass, program.getSamplers());
		warnings.addAll(preparation.diagnostics());
		warnings.addAll(bindings.warnings);
		var sampler = bindings.camera;
		if (sampler != null) {
			var texture = device.createExternalTexture();
			device.applySampling(texture, bindings.cameraSampling);
			camera = new ShaderTextureResources.SamplerTextureBinding(
					new DiscoveredSampler(sampler.name(),
							GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
							bindings.cameraSampling));
			camera.setTexture(texture);
		} else {
			camera = null;
		}
	}

	// GL thread. Returns null and adds the preparation's diagnostics and
	// why it cannot draw when it does not link.
	@Nullable
	static PlanRuntime link(
			@NonNull GlDevice device,
			@NonNull PlanShader shader,
			int glesVersion,
			@NonNull List<ProjectDiagnostic> errors) {
		var preparation = shader.preparation();
		var plan = preparation.plan();
		var reasons = new ArrayList<>(preparation.diagnostics());
		var refusals = plan != null
				? PlanSupport.refusals(plan)
				: List.<ProjectDiagnostic>of();
		if (plan == null || !refusals.isEmpty()) {
			errors.addAll(reasons);
			errors.addAll(refusals);
			return null;
		}
		var pass = plan.passes().get(plan.output());
		var source = PlanSource.of(plan.programs().get(pass.program()),
				glesVersion, reasons);
		if (source == null) {
			errors.addAll(reasons);
			return null;
		}
		var result = device.createProgram(source.vertex(), source.fragment(),
				ShaderLineMapping.withSourceMap(source.sourceMap()));
		if (!result.succeeded() || result.getProgram() == null) {
			errors.addAll(reasons);
			errors.addAll(result.getInfoLog());
			return null;
		}
		return new PlanRuntime(device, shader, result.getProgram(),
				source.fTimeMax(), glesVersion);
	}

	@NonNull
	@Override
	public GlProgram program() {
		return program;
	}

	@Override
	public float quality() {
		return quality;
	}

	@Override
	public float fTimeMax() {
		return fTimeMax;
	}

	@NonNull
	@Override
	public Sampling backBufferSampling() {
		return Sampling.PASS;
	}

	@Override
	public boolean isSizedToRender() {
		return false;
	}

	// Decodes its images from the bytes the preparation captured; their
	// size does not depend on the render size.
	@Override
	public void loadTextures(
			@NonNull Context context,
			int width,
			int height,
			@Nullable Runnable onDecoded) {
		var files = new HashMap<ProjectPath, byte[]>();
		for (var path : bindings.imagePaths()) {
			var content = preparation.files().get(path);
			if (content != null) {
				files.put(path, content.bytes());
			}
		}
		int generation = decoded.begin();
		int maxPixels = ProjectLimits.DEFAULT.imagePixels();
		if (files.isEmpty()) {
			decoded.deliver(generation, DecodedImages.decode(files, maxPixels));
			return;
		}
		new Thread(() -> {
			decoded.deliver(generation, DecodedImages.decode(files, maxPixels));
			if (onDecoded != null) {
				onDecoded.run();
			}
		}, "PlanImageDecoder").start();
	}

	// GL thread. Uploads the images once they are decoded, or fails when
	// one cannot be used as its inputs ask; it starts no backbuffer.
	@Nullable
	@Override
	public DecodedTextures upload(@NonNull List<ProjectDiagnostic> diagnostics) {
		var images = decoded.take();
		if (images == null) {
			return null;
		}
		var errors = new ArrayList<ProjectDiagnostic>();
		for (var problem : images.problems.entrySet()) {
			errors.add(bindings.locate(problem.getKey(), problem.getValue()));
		}
		errors.addAll(bindings.check(images.sizes(), glesVersion,
				device.getMaxTextureSize()));
		if (errors.isEmpty()) {
			for (var entry : images.images.entrySet()) {
				var path = entry.getKey();
				var image = entry.getValue();
				var texture = device.createTexture2D();
				textures.put(path, texture);
				var message = device.uploadTexture2D(texture, image.width(),
						image.height(), image.rgba());
				if (message != null) {
					errors.add(bindings.locate(path, path + ": " + message));
				} else if (bindings.needsMipmaps(path)) {
					device.generateMipmap(texture);
				}
			}
		}
		images.recycle();
		diagnostics.addAll(warnings);
		diagnostics.addAll(errors);
		if (!errors.isEmpty()) {
			failed = true;
			return null;
		}
		if (glesVersion >= 3) {
			createSamplers();
		}
		complete = true;
		return null;
	}

	@Override
	public boolean hasFailed() {
		return failed;
	}

	@Nullable
	@Override
	public ShaderTextureResources.SamplerTextureBinding getCameraBinding() {
		return camera;
	}

	// Binds what each input reads, sampled as it asks.
	@Override
	public void draw(@NonNull ShaderRenderPipeline pipeline) {
		var images = bindings.images;
		for (int i = 0; i < images.size(); ++i) {
			var binding = images.get(i);
			var texture = textures.get(binding.image());
			int unit = binding.sampler().unit();
			if (texture == null) {
				continue;
			} else if (samplers != null) {
				device.bindTexture(unit, texture);
				GLES30.glBindSampler(unit, samplers[i]);
			} else {
				device.applySampling(unit, texture, binding.sampling());
			}
		}
		if (camera != null && bindings.camera != null) {
			device.bindTexture(bindings.camera.unit(), camera.texture());
		}
		pipeline.renderMainPass(program);
		if (samplers != null) {
			for (var binding : images) {
				GLES30.glBindSampler(binding.sampler().unit(), 0);
			}
		}
	}

	@Override
	public boolean isComplete() {
		return complete;
	}

	@Override
	public void release() {
		decoded.cancel();
		device.deleteProgram(program);
		for (var texture : textures.values()) {
			device.deleteTexture(texture);
		}
		textures.clear();
		if (camera != null) {
			device.deleteTexture(camera.texture());
			camera.setTexture(null);
		}
		if (samplers != null) {
			GLES30.glDeleteSamplers(samplers.length, samplers, 0);
			samplers = null;
		}
	}

	@Override
	public void discard() {
		decoded.cancel();
		program.invalidate();
		for (var texture : textures.values()) {
			texture.invalidate();
		}
		textures.clear();
		if (camera != null) {
			camera.setTexture(null);
		}
		samplers = null;
	}

	private void createSamplers() {
		var images = bindings.images;
		samplers = new int[images.size()];
		if (samplers.length == 0) {
			return;
		}
		GLES30.glGenSamplers(samplers.length, samplers, 0);
		for (int i = 0; i < samplers.length; ++i) {
			var sampling = images.get(i).sampling();
			int sampler = samplers[i];
			GLES30.glSamplerParameteri(sampler, GLES30.GL_TEXTURE_MIN_FILTER,
					GlSampling.minFilter(sampling));
			GLES30.glSamplerParameteri(sampler, GLES30.GL_TEXTURE_MAG_FILTER,
					GlSampling.magFilter(sampling));
			GLES30.glSamplerParameteri(sampler, GLES30.GL_TEXTURE_WRAP_S,
					GlSampling.wrap(sampling.wrapS()));
			GLES30.glSamplerParameteri(sampler, GLES30.GL_TEXTURE_WRAP_T,
					GlSampling.wrap(sampling.wrapT()));
		}
	}
}

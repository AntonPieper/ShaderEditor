package de.markusfisch.android.shadereditor.opengl;

import android.opengl.GLES30;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import de.markusfisch.android.shadereditor.project.ProjectDiagnostic;
import de.markusfisch.android.shadereditor.project.RenderPlan;
import de.markusfisch.android.shadereditor.project.RenderPlan.Sampling;

// One pass of a plan linked in the current context: its program, what
// its active samplers read, what it warns about, and on GLES3 a sampler
// object per input.
final class PlanPass {
	@NonNull
	final RenderPlan.Pass pass;
	@NonNull
	final GlProgram program;
	@NonNull
	final PlanBindings bindings;
	// Inputs the compiler removed, and uniforms nothing provides.
	@NonNull
	final List<ProjectDiagnostic> warnings;
	final float fTimeMax;
	@NonNull
	private final GlDevice device;
	// Per image and per history binding on a GLES3 context, else null.
	@Nullable
	private int[] samplers;
	@Nullable
	private int[] historySamplers;

	private PlanPass(
			@NonNull GlDevice device,
			@NonNull RenderPlan.Pass pass,
			@NonNull GlProgram program,
			@NonNull PlanBindings bindings,
			@NonNull List<ProjectDiagnostic> warnings,
			float fTimeMax) {
		this.device = device;
		this.pass = pass;
		this.program = program;
		this.bindings = bindings;
		this.warnings = warnings;
		this.fTimeMax = fTimeMax;
	}

	// GL thread. Returns null and adds why when it does not link.
	@Nullable
	static PlanPass link(
			@NonNull GlDevice device,
			@NonNull RenderPlan plan,
			int index,
			int glesVersion,
			@NonNull List<ProjectDiagnostic> errors) {
		var pass = plan.passes().get(index);
		var source = PlanSource.of(plan.programs().get(pass.program()),
				glesVersion, errors);
		if (source == null) {
			return null;
		}
		var lineMapping = ShaderLineMapping.withSourceMap(source.sourceMap());
		var result = device.createProgram(source.vertex(), source.fragment(),
				lineMapping);
		var program = result.getProgram();
		if (!result.succeeded() || program == null) {
			errors.addAll(result.getInfoLog());
			return null;
		}
		var bindings = PlanBindings.of(pass, index, program.getSamplers(),
				source.fragment());
		var warnings = new ArrayList<>(bindings.warnings);
		var inputs = new HashSet<String>();
		for (var input : pass.inputs()) {
			inputs.add(input.uniform());
		}
		warnings.addAll(UniformWarnings.check(
				program.getUniforms().values(), inputs,
				UniformWarnings.ADD_INPUT, source.fragment(), lineMapping));
		return new PlanPass(device, pass, program, bindings, warnings,
				source.fTimeMax());
	}

	// GL thread, on a GLES3 context.
	void createSamplers() {
		var images = bindings.images;
		samplers = new int[images.size()];
		for (int i = 0; i < samplers.length; ++i) {
			samplers[i] = createSampler(images.get(i).sampling());
		}
		var previous = bindings.history;
		historySamplers = new int[previous.size()];
		for (int i = 0; i < historySamplers.length; ++i) {
			historySamplers[i] = createSampler(previous.get(i).sampling());
		}
	}

	// Binds what each input reads, sampled as it asks: its image, the
	// camera, and the previous frame. Unbound samplers read black.
	void bind(
			@NonNull Map<PlanBindings.Texture, GlTexture> textures,
			@Nullable GlTexture camera,
			@Nullable GlTexture2D previous) {
		device.bindEmptyTextures(program);
		var images = bindings.images;
		for (int i = 0; i < images.size(); ++i) {
			var binding = images.get(i);
			var texture = textures.get(binding.texture());
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
		var history = bindings.history;
		for (int i = 0; previous != null && i < history.size(); ++i) {
			var binding = history.get(i);
			int unit = binding.sampler().unit();
			if (historySamplers != null) {
				device.bindTexture(unit, previous);
				GLES30.glBindSampler(unit, historySamplers[i]);
			} else {
				device.applySampling(unit, previous, binding.sampling());
			}
		}
		if (camera != null && bindings.camera != null) {
			device.bindTexture(bindings.camera.unit(), camera);
		}
		device.useProgram(program);
	}

	// After drawing, so the units sample by texture parameters again.
	void unbind() {
		if (samplers == null) {
			return;
		}
		for (var binding : bindings.images) {
			GLES30.glBindSampler(binding.sampler().unit(), 0);
		}
		for (var binding : bindings.history) {
			GLES30.glBindSampler(binding.sampler().unit(), 0);
		}
	}

	void release() {
		device.deleteProgram(program);
		if (samplers != null && historySamplers != null) {
			GLES30.glDeleteSamplers(samplers.length, samplers, 0);
			GLES30.glDeleteSamplers(historySamplers.length, historySamplers, 0);
		}
		samplers = null;
		historySamplers = null;
	}

	void discard() {
		program.invalidate();
		samplers = null;
		historySamplers = null;
	}

	private static int createSampler(@NonNull Sampling sampling) {
		int[] sampler = new int[1];
		GLES30.glGenSamplers(1, sampler, 0);
		GLES30.glSamplerParameteri(sampler[0], GLES30.GL_TEXTURE_MIN_FILTER,
				GlSampling.minFilter(sampling));
		GLES30.glSamplerParameteri(sampler[0], GLES30.GL_TEXTURE_MAG_FILTER,
				GlSampling.magFilter(sampling));
		GLES30.glSamplerParameteri(sampler[0], GLES30.GL_TEXTURE_WRAP_S,
				GlSampling.wrap(sampling.wrapS()));
		GLES30.glSamplerParameteri(sampler[0], GLES30.GL_TEXTURE_WRAP_T,
				GlSampling.wrap(sampling.wrapT()));
		return sampler[0];
	}
}

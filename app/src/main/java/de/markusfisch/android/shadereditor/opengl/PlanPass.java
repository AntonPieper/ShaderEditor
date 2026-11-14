package de.markusfisch.android.shadereditor.opengl;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import de.markusfisch.android.shadereditor.project.ProjectDiagnostic;
import de.markusfisch.android.shadereditor.project.RenderPlan;

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
	// The active uniforms as the text declares them.
	@NonNull
	final Map<String, GlUniform> uniforms;
	// Inputs the compiler removed, and uniforms nothing provides.
	@NonNull
	final List<ProjectDiagnostic> warnings;
	final float fTimeMax;
	@NonNull
	private final GlDevice device;
	@NonNull
	private final PlanSource source;
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
			@NonNull Map<String, GlUniform> uniforms,
			@NonNull List<ProjectDiagnostic> warnings,
			@NonNull PlanSource source) {
		this.device = device;
		this.pass = pass;
		this.program = program;
		this.bindings = bindings;
		this.uniforms = uniforms;
		this.warnings = warnings;
		this.source = source;
		fTimeMax = source.fTimeMax();
	}

	// GL thread. Returns null and adds why when it does not link.
	// textureFix is the fix its warnings name for a sampler without an
	// image.
	@Nullable
	static PlanPass link(
			@NonNull GlDevice device,
			@NonNull RenderPlan plan,
			int index,
			int glesVersion,
			@NonNull String textureFix,
			@NonNull List<ProjectDiagnostic> errors) {
		var pass = plan.passes().get(index);
		var source = PlanSource.of(plan.programs().get(pass.program()),
				glesVersion, errors);
		if (source == null) {
			return null;
		}
		var result = device.createProgram(source.vertex(), source.fragment(),
				source.sourceMap());
		var program = result.getProgram();
		if (!result.succeeded() || program == null) {
			errors.addAll(result.getInfoLog());
			return null;
		}
		var bindings = PlanBindings.of(pass, index, program.getSamplers(),
				source.fragment());
		var uniforms = bindings.declared(program.getUniforms());
		var warnings = new ArrayList<>(bindings.warnings);
		var inputs = new HashSet<String>();
		for (var input : pass.inputs()) {
			inputs.add(input.uniform());
		}
		warnings.addAll(UniformWarnings.check(uniforms.values(), inputs,
				textureFix, source.fragment(), source.sourceMap()));
		return new PlanPass(device, pass, program, bindings, uniforms,
				warnings, source);
	}

	// A warning at the declaration of the uniform name.
	@NonNull
	ProjectDiagnostic warning(@NonNull String name, @NonNull String message) {
		return UniformWarnings.locate(message,
				UniformWarnings.declarationLine(source.fragment(), name),
				source.sourceMap());
	}

	// GL thread, on a GLES3 context. Returns why they could not be made,
	// or null.
	@Nullable
	String createSamplers() {
		device.clearErrors();
		var images = bindings.images;
		samplers = new int[images.size()];
		for (int i = 0; i < samplers.length; ++i) {
			samplers[i] = device.createSampler(images.get(i).sampling());
		}
		var previous = bindings.history;
		historySamplers = new int[previous.size()];
		for (int i = 0; i < historySamplers.length; ++i) {
			historySamplers[i] = device.createSampler(previous.get(i).sampling());
		}
		return device.takeError("glGenSamplers");
	}

	// Binds what each input reads, sampled as it asks: its image, the
	// camera that is open when it is the one it reads, and the previous
	// frame. Unbound samplers read black.
	void bind(
			@NonNull Map<PlanBindings.Texture, GlTexture> textures,
			@Nullable CameraSource.Binding camera,
			@Nullable GlTexture2D previous) {
		device.bindEmptyTextures(bindings.samplers);
		var images = bindings.images;
		for (int i = 0; i < images.size(); ++i) {
			var binding = images.get(i);
			var texture = textures.get(binding.texture());
			int unit = binding.sampler().unit();
			if (texture == null) {
				continue;
			} else if (samplers != null) {
				device.bindTexture(unit, texture);
				device.bindSampler(unit, samplers[i]);
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
				device.bindSampler(unit, historySamplers[i]);
			} else {
				device.applySampling(unit, previous, binding.sampling());
			}
		}
		// Another program may read the camera with other settings.
		var sampler = bindings.camera;
		if (camera != null && sampler != null &&
				sampler.name().equals(camera.sampler())) {
			device.applySampling(sampler.unit(), camera.texture(),
					bindings.cameraSampling);
		}
		device.useProgram(program);
	}

	// After drawing, so the units sample by texture parameters again.
	void unbind() {
		if (samplers == null) {
			return;
		}
		for (var binding : bindings.images) {
			device.bindSampler(binding.sampler().unit(), 0);
		}
		for (var binding : bindings.history) {
			device.bindSampler(binding.sampler().unit(), 0);
		}
	}

	void release() {
		device.deleteProgram(program);
		if (samplers != null && historySamplers != null) {
			device.deleteSamplers(samplers);
			device.deleteSamplers(historySamplers);
		}
		samplers = null;
		historySamplers = null;
	}

	void discard() {
		program.invalidate();
		samplers = null;
		historySamplers = null;
	}
}

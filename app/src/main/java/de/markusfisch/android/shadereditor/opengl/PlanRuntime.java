package de.markusfisch.android.shadereditor.opengl;

import android.content.Context;
import android.opengl.GLES11Ext;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

import de.markusfisch.android.shadereditor.project.ProjectDiagnostic;
import de.markusfisch.android.shadereditor.project.RenderPlan.Sampling;

// A prepared single-pass plan linked in the current context, with the
// engine samplers it reads. It owns its GL objects and is built again
// from the plan in a new context.
final class PlanRuntime implements EngineBuild {
	@NonNull
	private final GlProgram program;
	private final float fTimeMax;
	private final float quality;
	@NonNull
	private final GlDevice device;
	@NonNull
	private final PlanBindings bindings;
	// Told when it is swapped in.
	@NonNull
	private final List<ProjectDiagnostic> warnings = new ArrayList<>();
	@Nullable
	private final ShaderTextureResources.SamplerTextureBinding camera;

	private PlanRuntime(
			@NonNull GlDevice device,
			@NonNull PlanShader shader,
			@NonNull GlProgram program,
			float fTimeMax) {
		this.device = device;
		this.quality = shader.quality();
		this.program = program;
		this.fTimeMax = fTimeMax;
		var preparation = shader.preparation();
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
				source.fTimeMax());
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

	@Override
	public void loadTextures(
			@NonNull Context context,
			int width,
			int height,
			@Nullable Runnable onDecoded) {
	}

	// GL thread. It reads no decoded textures and starts no backbuffer.
	@Nullable
	@Override
	public DecodedTextures upload(@NonNull List<ProjectDiagnostic> diagnostics) {
		diagnostics.addAll(warnings);
		return null;
	}

	@Nullable
	@Override
	public ShaderTextureResources.SamplerTextureBinding getCameraBinding() {
		return camera;
	}

	@Override
	public void draw(@NonNull ShaderRenderPipeline pipeline) {
		if (camera != null && bindings.camera != null) {
			device.bindTexture(bindings.camera.unit(), camera.texture());
		}
		pipeline.renderMainPass(program);
	}

	@Override
	public boolean isComplete() {
		return true;
	}

	@Override
	public void release() {
		device.deleteProgram(program);
		if (camera != null) {
			device.deleteTexture(camera.texture());
			camera.setTexture(null);
		}
	}

	@Override
	public void discard() {
		program.invalidate();
		if (camera != null) {
			camera.setTexture(null);
		}
	}
}

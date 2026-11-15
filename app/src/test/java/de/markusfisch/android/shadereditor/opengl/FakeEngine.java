package de.markusfisch.android.shadereditor.opengl;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;

import de.markusfisch.android.shadereditor.project.Preparation;
import de.markusfisch.android.shadereditor.project.ProjectDiagnostic;

import static de.markusfisch.android.shadereditor.opengl.TestProjects.path;

// A plan linked and drawn on a FakeGlDevice, as ShaderEngine would.
final class FakeEngine {
	final FakeGlDevice device;
	final ShaderRenderPipeline pipeline;
	final PlanRuntime runtime;
	final List<ProjectDiagnostic> diagnostics = new ArrayList<>();
	// What every pass of a frame is drawn with; a test may set more
	// of it than draw() does.
	final FrameInputs frame = new FrameInputs();
	private ActiveBuiltins builtins;

	// A context of this version that draws into every format.
	FakeEngine(@NonNull Preparation preparation, int glesVersion) {
		this(new FakeGlDevice(), preparation, TargetFormats.of(glesVersion,
				"GL_OES_texture_half_float GL_EXT_color_buffer_half_float"));
	}

	FakeEngine(@NonNull FakeGlDevice device,
			@NonNull Preparation preparation,
			@NonNull TargetFormats formats) {
		this(device, preparation, formats, null);
	}

	// With a tuned number of the text replaced by tuning's uniform.
	FakeEngine(@NonNull FakeGlDevice device,
			@NonNull Preparation preparation,
			@NonNull TargetFormats formats,
			@androidx.annotation.Nullable Tuning tuning) {
		this.device = device;
		pipeline = new ShaderRenderPipeline(device);
		pipeline.createContextResources();
		runtime = PlanRuntime.link(device,
				new PlanShader(preparation, 1f, "fix", tuning), formats,
				diagnostics);
		if (runtime != null) {
			runtime.loadTextures(null);
			runtime.upload(diagnostics);
			builtins = new ActiveBuiltins(device, runtime.passes(),
					new UniformSources(UniformSourcesTest.HOST));
		}
	}

	// What every pass of a frame is drawn with, for a test that draws.
	@androidx.annotation.NonNull
	ActiveBuiltins builtins() {
		return builtins;
	}

	// Makes the targets for a render size, which is also the surface's.
	ShaderSwap.Allocation resize(int width, int height) {
		frame.surfaceWidth = width;
		frame.surfaceHeight = height;
		frame.width = width;
		frame.height = height;
		return runtime.createTargets(width, height, diagnostics);
	}

	// Draws one frame at this time and counts the frame number up.
	void draw(float time) {
		draw(time, null);
	}

	// Like draw(time), showing the pass of file as ShaderEngine does.
	void draw(float time, @androidx.annotation.Nullable String file) {
		frame.time = time;
		frame.fTimeMax = runtime.fTimeMax();
		runtime.draw(pipeline, builtins, frame,
				runtime.shown(file != null ? path(file) : null));
		++frame.frame;
	}

	// The program of the pass in this file.
	int program(@NonNull String file) {
		for (var pass : runtime.passes()) {
			var program = pass.program;
			// A pass file holds its target directive.
			if (pass.pass.target().file().equals(path(file))) {
				return program.getId();
			}
		}
		throw new AssertionError(file);
	}

	// The unit of a sampler of the pass in this file.
	int unit(@NonNull String file, @NonNull String sampler) {
		return device.programs.get(program(file)).getTextureUnit(sampler);
	}

	// The draws of the pass in this file.
	@NonNull
	List<FakeGlDevice.Draw> draws(@NonNull String file) {
		return device.drawsOf(program(file));
	}

	// The draws of the presentation shader into a target: the initial
	// content stretched over a history.
	@NonNull
	List<FakeGlDevice.Draw> stretched() {
		var passes = new java.util.HashSet<Integer>();
		for (var pass : runtime.passes()) {
			passes.add(pass.program.getId());
		}
		var stretched = new ArrayList<FakeGlDevice.Draw>();
		for (var draw : device.draws) {
			if (draw.target() != 0 && !passes.contains(draw.program())) {
				stretched.add(draw);
			}
		}
		return stretched;
	}

	// The unit the presentation shader of a draw reads.
	int stretchUnit(@NonNull FakeGlDevice.Draw draw) {
		return device.programs.get(draw.program()).getTextureUnit("frame");
	}

	// The draws onto the surface.
	@NonNull
	List<FakeGlDevice.Draw> presented() {
		var presented = new ArrayList<FakeGlDevice.Draw>();
		for (var draw : device.draws) {
			if (draw.target() == 0) {
				presented.add(draw);
			}
		}
		return presented;
	}
}

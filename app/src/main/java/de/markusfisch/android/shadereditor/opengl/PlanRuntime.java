package de.markusfisch.android.shadereditor.opengl;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import de.markusfisch.android.shadereditor.project.HostDirective.Mipmap;
import de.markusfisch.android.shadereditor.project.HostDirective.Wrap;
import de.markusfisch.android.shadereditor.project.Preparation;
import de.markusfisch.android.shadereditor.project.ProjectDiagnostic;
import de.markusfisch.android.shadereditor.project.ProjectLimits;
import de.markusfisch.android.shadereditor.project.ProjectPath;
import de.markusfisch.android.shadereditor.project.RenderPlan;
import de.markusfisch.android.shadereditor.project.RenderPlan.Sampling;

// A prepared single-pass plan linked in the current context, with the
// images its inputs read and how each input samples its image, and the
// image or shader its history starts as. It is ready to draw once every
// image is uploaded, and fails as a whole when one cannot be; it owns its
// GL objects, its targets among them, and is built again from the plan in
// a new context.
final class PlanRuntime implements ShaderSwap.Build {
	private final float quality;
	@NonNull
	private final GlDevice device;
	@NonNull
	private final Preparation preparation;
	@NonNull
	private final PlanPass running;
	// The shader the history starts as, drawn once per reset, or null.
	@Nullable
	private final PlanPass initialPass;
	// The image the history starts as, or null.
	@Nullable
	private final ProjectPath initialImage;
	// The running pass first.
	@NonNull
	private final List<PlanPass> passes;
	@NonNull
	private final List<GlProgram> programs;
	@NonNull
	private final RenderPlan.Pass pass;
	@NonNull
	private final PlanBindings bindings;
	// Whether the pass keeps its previous frame.
	private final boolean history;
	@NonNull
	private final Sampling presentSampling;
	private final int glesVersion;
	private final FrameInputs passFrame = new FrameInputs();
	private final FrameInputs initialFrame = new FrameInputs();
	private final FullScreenQuad quad = new FullScreenQuad();
	// Told with the outcome, whatever it is.
	@NonNull
	private final List<ProjectDiagnostic> warnings = new ArrayList<>();
	private final Map<PlanBindings.Texture, GlTexture> textures =
			new HashMap<>();
	private final TextureHandOver<DecodedImages> decoded =
			new TextureHandOver<>();
	@Nullable
	private final CameraSource.Binding camera;
	@Nullable
	private GlTexture2D initialTexture;
	@Nullable
	private PassTarget target;
	// What the initial shader draws into.
	@Nullable
	private PassTarget initialTarget;
	// Whether the targets were made since the last frame.
	private boolean reset;
	private boolean complete;
	private boolean failed;

	private PlanRuntime(
			@NonNull GlDevice device,
			@NonNull PlanShader shader,
			@NonNull PlanPass running,
			@Nullable PlanPass initialPass,
			int glesVersion) {
		this.device = device;
		this.preparation = shader.preparation();
		this.quality = shader.quality();
		this.running = running;
		this.initialPass = initialPass;
		this.glesVersion = glesVersion;
		pass = running.pass;
		bindings = running.bindings;
		var initial = pass.initial();
		initialImage = initial != null &&
				initial.kind() == RenderPlan.Source.Kind.IMAGE
				? initial.image()
				: null;
		passes = initialPass != null
				? List.of(running, initialPass)
				: List.of(running);
		programs = initialPass != null
				? List.of(running.program, initialPass.program)
				: List.of(running.program);
		history = pass.history() || !bindings.history.isEmpty();
		var present = pass.target().present();
		presentSampling = new Sampling(present, present, Mipmap.NONE,
				Wrap.CLAMP, Wrap.CLAMP);
		warnings.addAll(preparation.diagnostics());
		// The camera of the running pass, or else of the initial shader.
		PlanBindings cameraBindings = null;
		for (var linked : passes) {
			warnings.addAll(linked.warnings);
			if (cameraBindings == null && linked.bindings.camera != null) {
				cameraBindings = linked.bindings;
			}
		}
		if (cameraBindings != null && cameraBindings.camera != null) {
			var texture = device.createExternalTexture();
			device.applySampling(texture, cameraBindings.cameraSampling);
			camera = new CameraSource.Binding(cameraBindings.camera.name(),
					texture);
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
		var running = PlanPass.link(device, plan, plan.output(), glesVersion,
				shader.textureFix(), reasons);
		var initial = plan.passes().get(plan.output()).initial();
		PlanPass initialPass = null;
		if (running != null && initial != null &&
				initial.kind() == RenderPlan.Source.Kind.PASS) {
			initialPass = PlanPass.link(device, plan, initial.pass(),
					glesVersion, shader.textureFix(), reasons);
			if (initialPass == null) {
				running.release();
				running = null;
			}
		}
		if (running == null) {
			errors.addAll(reasons);
			return null;
		}
		return new PlanRuntime(device, shader, running, initialPass,
				glesVersion);
	}

	// The programs whose builtins it reads, the one drawn every frame
	// first.
	@NonNull
	List<GlProgram> programs() {
		return programs;
	}

	float quality() {
		return quality;
	}

	float fTimeMax() {
		return running.fTimeMax;
	}

	// Decodes its images off the GL thread from the bytes the preparation
	// captured; until upload() took them, it is not complete.
	void loadTextures(@Nullable Runnable onDecoded) {
		var paths = new LinkedHashSet<ProjectPath>();
		for (var linked : passes) {
			paths.addAll(linked.bindings.imagePaths());
		}
		if (initialImage != null && history) {
			paths.add(initialImage);
		}
		var files = new HashMap<ProjectPath, byte[]>();
		for (var path : paths) {
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
			// A build that was released, with its view perhaps, asks for no
			// frame.
			if (decoded.deliver(generation,
					DecodedImages.decode(files, maxPixels)) &&
					onDecoded != null) {
				onDecoded.run();
			}
		}, "PlanImageDecoder").start();
	}

	// GL thread. Uploads the images once they are decoded, or fails when
	// one cannot be used as its inputs ask or start its history, and adds
	// what the host should be told.
	void upload(@NonNull List<ProjectDiagnostic> diagnostics) {
		var images = decoded.take();
		if (images == null) {
			return;
		}
		var errors = new ArrayList<ProjectDiagnostic>();
		for (var problem : images.problems.entrySet()) {
			errors.add(locate(problem.getKey(), problem.getValue()));
		}
		int maxSize = device.getMaxTextureSize();
		for (var linked : passes) {
			errors.addAll(linked.bindings.check(images.sizes(), glesVersion,
					maxSize, device.getMaxCubemapSize()));
		}
		var seed = initialImage != null ? images.images.get(initialImage) : null;
		if (seed != null && (seed.width() > maxSize || seed.height() > maxSize)) {
			errors.add(locate(initialImage, initialImage + " is " +
					seed.width() + "x" + seed.height() + ", larger than " +
					maxSize + ", the largest texture this device supports"));
		}
		var keys = new LinkedHashSet<PlanBindings.Texture>();
		for (var linked : passes) {
			for (var binding : linked.bindings.images) {
				keys.add(binding.texture());
			}
		}
		if (seed != null) {
			keys.add(new PlanBindings.Texture(initialImage, false));
		}
		if (errors.isEmpty()) {
			for (var key : keys) {
				var image = images.images.get(key.image());
				if (image == null) {
					continue;
				}
				GlTexture texture;
				String message;
				if (key.cubemap()) {
					var cubemap = device.createCubemap();
					texture = cubemap;
					message = uploadCubemap(cubemap, image);
				} else {
					var texture2D = device.createTexture2D();
					texture = texture2D;
					message = device.uploadTexture2D(texture2D, image.width(),
							image.height(), image.rgba());
					if (key.image().equals(initialImage)) {
						initialTexture = texture2D;
					}
				}
				textures.put(key, texture);
				if (message != null) {
					errors.add(locate(key.image(), key.image() + ": " + message));
				} else if (needsMipmaps(key)) {
					device.generateMipmap(texture);
				}
			}
		}
		images.recycle();
		diagnostics.addAll(warnings);
		diagnostics.addAll(errors);
		if (!errors.isEmpty()) {
			failed = true;
			return;
		}
		for (int i = 0; glesVersion >= 3 && i < passes.size(); ++i) {
			passes.get(i).createSamplers();
		}
		complete = true;
	}

	// Whether it can never complete, so it must not replace the current
	// build: an image did not decode or upload.
	boolean hasFailed() {
		return failed;
	}

	@Nullable
	CameraSource.Binding getCameraBinding() {
		return camera;
	}

	// GL thread, before it replaces the current build and whenever the
	// render size changed: makes its own target at the size the plan
	// says, the render size times scale or a fixed size, with a second
	// texture when the pass reads its previous frame, and one for the
	// initial shader at the size its own target says. Adds why when it
	// cannot; it has nothing to draw into then.
	@NonNull
	ShaderSwap.Allocation createTargets(
			int width,
			int height,
			@NonNull List<ProjectDiagnostic> errors) {
		releaseTargets();
		// The render size is already scaled by the quality.
		int max = device.getMaxTextureSize();
		var project = RenderPlan.Size.project(width, height, 1, max);
		var size = project.isSuccess()
				? pass.target().size(project.value(), max)
				: project;
		var initialSize = initialPass != null && history && project.isSuccess()
				? initialPass.pass.target().size(project.value(), max)
				: null;
		if (!size.isSuccess() ||
				(initialSize != null && !initialSize.isSuccess())) {
			errors.add(!size.isSuccess()
					? size.diagnostic()
					: initialSize.diagnostic());
			return ShaderSwap.Allocation.INVALID;
		}
		target = PassTarget.create(device, pass.target(), size.value(),
				history, errors);
		if (target == null) {
			return ShaderSwap.Allocation.FAILED;
		} else if (initialSize != null) {
			initialTarget = PassTarget.create(device,
					initialPass.pass.target(), initialSize.value(), false,
					errors);
			if (initialTarget == null) {
				releaseTargets();
				return ShaderSwap.Allocation.FAILED;
			}
		}
		reset = true;
		return ShaderSwap.Allocation.MADE;
	}

	boolean hasTargets() {
		return target != null;
	}

	// GL thread. Cleared to opaque black before every draw, with its
	// previous frame when that is read, and presented with the filter the
	// target asks for.
	void draw(
			@NonNull ShaderRenderPipeline pipeline,
			@NonNull ActiveBuiltins builtins,
			@NonNull FrameInputs frame,
			long now) {
		var target = this.target;
		if (target == null) {
			return;
		}
		var steps = PassSchedule.frame(reset, history,
				initialImage != null || initialPass != null);
		reset = false;
		for (int i = 0, n = steps.size(); i < n; ++i) {
			switch (steps.get(i)) {
				case CLEAR_HISTORY -> target.clearHistory();
				case INITIAL -> drawInitial(pipeline, target, builtins, frame,
						now);
				case CLEAR -> target.clear();
				case DRAW -> drawPass(target, builtins, frame, now);
				case SWAP -> target.swap();
				case PRESENT -> {
					var output = target.completed();
					device.applySampling(output, presentSampling);
					pipeline.present(output, frame.surfaceWidth,
							frame.surfaceHeight);
				}
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
		for (var linked : passes) {
			linked.release();
		}
		for (var texture : textures.values()) {
			device.deleteTexture(texture);
		}
		textures.clear();
		if (camera != null) {
			device.deleteTexture(camera.texture());
		}
		releaseTargets();
	}

	@Override
	public void discard() {
		decoded.cancel();
		for (var linked : passes) {
			linked.discard();
		}
		for (var texture : textures.values()) {
			texture.invalidate();
		}
		textures.clear();
		if (camera != null) {
			camera.texture().invalidate();
		}
		if (target != null) {
			target.discard();
			target = null;
		}
		if (initialTarget != null) {
			initialTarget.discard();
			initialTarget = null;
		}
	}

	// GL thread. Releases what it draws into; false when it had nothing.
	boolean releaseTargets() {
		boolean had = target != null;
		if (target != null) {
			target.release();
			target = null;
		}
		if (initialTarget != null) {
			initialTarget.release();
			initialTarget = null;
		}
		return had;
	}

	// With resolution and pixel positions in the target's size.
	private void drawPass(
			@NonNull PassTarget target,
			@NonNull ActiveBuiltins builtins,
			@NonNull FrameInputs frame,
			long now) {
		passFrame.scale(frame, target.size().width(), target.size().height());
		builtins.write(passFrame, now);
		running.bind(textures, camera != null ? camera.texture() : null,
				target.completed());
		quad.draw();
		running.unbind();
	}

	// Stretches the image, or what the initial shader draws at its own
	// size with time and frame at zero, over the previous frame.
	private void drawInitial(
			@NonNull ShaderRenderPipeline pipeline,
			@NonNull PassTarget target,
			@NonNull ActiveBuiltins builtins,
			@NonNull FrameInputs frame,
			long now) {
		var picture = initialTexture;
		var shader = initialPass;
		var drawn = initialTarget;
		if (shader != null && drawn != null) {
			drawn.clear();
			var size = drawn.size();
			initialFrame.scale(frame, size.width(), size.height());
			initialFrame.time = 0;
			initialFrame.frame = 0;
			builtins.write(1, initialFrame, now);
			shader.bind(textures, camera != null ? camera.texture() : null,
					null);
			quad.draw();
			shader.unbind();
			picture = drawn.completed();
		}
		if (picture != null) {
			device.applySampling(picture, Sampling.IMAGE);
			pipeline.stretch(picture, target.history());
		}
	}

	private boolean needsMipmaps(@NonNull PlanBindings.Texture texture) {
		for (var linked : passes) {
			if (linked.bindings.needsMipmaps(texture)) {
				return true;
			}
		}
		return false;
	}

	// A problem with an image, at the first input that reads it, or else
	// at the target that starts from it.
	@NonNull
	private ProjectDiagnostic locate(
			@NonNull ProjectPath image,
			@NonNull String message) {
		for (var linked : passes) {
			if (linked.bindings.imagePaths().contains(image)) {
				return linked.bindings.locate(image, message);
			}
		}
		return new ProjectDiagnostic(ProjectDiagnostic.Severity.ERROR, message,
				pass.target().file(), pass.target().line());
	}

	// Cuts the faces out of the strip one at a time, so only one face is
	// copied at once.
	@Nullable
	private String uploadCubemap(@NonNull GlCubemap cubemap,
			@NonNull DecodedImages.Image image) {
		int side = image.height();
		var strip = image.rgba().asIntBuffer();
		var face = ByteBuffer.allocateDirect(side * side * 4)
				.order(ByteOrder.nativeOrder());
		for (int i = 0; i < CubemapStrip.FACES; ++i) {
			CubemapStrip.cut(strip, side, i, face.asIntBuffer());
			var message = device.uploadCubemapFace(cubemap, i, side, face);
			if (message != null) {
				return message;
			}
		}
		return null;
	}
}

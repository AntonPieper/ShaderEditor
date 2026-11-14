package de.markusfisch.android.shadereditor.opengl;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import de.markusfisch.android.shadereditor.project.HostDirective.Frame;
import de.markusfisch.android.shadereditor.project.HostDirective.Mipmap;
import de.markusfisch.android.shadereditor.project.HostDirective.Wrap;
import de.markusfisch.android.shadereditor.project.Preparation;
import de.markusfisch.android.shadereditor.project.ProjectDiagnostic;
import de.markusfisch.android.shadereditor.project.ProjectLimits;
import de.markusfisch.android.shadereditor.project.ProjectPath;
import de.markusfisch.android.shadereditor.project.RenderPlan;
import de.markusfisch.android.shadereditor.project.RenderPlan.Sampling;

// A prepared plan linked in the current context, with the
// images its inputs read and how each input samples its image, and the
// image or shader each history starts as. It is ready to draw once every
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
	private final RenderPlan plan;
	// By the index of the pass in the plan.
	@NonNull
	private final List<PlanPass> passes;
	@NonNull
	private final PassSchedule schedule;
	@NonNull
	private final Sampling presentSampling;
	private final int glesVersion;
	@NonNull
	private final TargetFormats formats;
	private final FrameInputs passFrame = new FrameInputs();
	private final FullScreenQuad quad = new FullScreenQuad();
	// Told with the outcome, whatever it is.
	@NonNull
	private final List<ProjectDiagnostic> warnings = new ArrayList<>();
	private final Map<PlanBindings.Texture, GlTexture> textures =
			new HashMap<>();
	private final TextureHandOver<DecodedImages> decoded =
			new TextureHandOver<>();
	// What a pass reads in a frame: the frame another pass drew before
	// it, or a previous frame.
	@NonNull
	private final PlanPass.Outputs inFrame;
	// What a pass drawn on reset reads: what every pass starts as.
	@NonNull
	private final PlanPass.Outputs onReset;
	@Nullable
	private final CameraSource.Binding camera;
	// By the index of the pass in the plan; null where the schedule
	// needs none, and all null before they are made.
	@NonNull
	private final PassTarget[] targets;
	// The sizes of the targets, or null while there are none.
	@Nullable
	private RenderPlan.Size[] madeSizes;
	// Whether the targets were made since the last frame.
	private boolean reset;
	private boolean complete;
	private boolean failed;

	private PlanRuntime(
			@NonNull GlDevice device,
			@NonNull PlanShader shader,
			@NonNull RenderPlan plan,
			@NonNull List<PlanPass> passes,
			@NonNull TargetFormats formats) {
		this.device = device;
		this.preparation = shader.preparation();
		this.quality = shader.quality();
		this.plan = plan;
		this.passes = passes;
		this.formats = formats;
		glesVersion = formats.glesVersion();
		var targets = this.targets = new PassTarget[passes.size()];
		inFrame = (pass, frame) -> targets[pass] == null
				? null
				: frame == Frame.PREVIOUS
						? targets[pass].completed()
						: targets[pass].drawn();
		onReset = (pass, frame) -> targets[pass] == null
				? null
				: targets[pass].completed();
		var backbuffer = new boolean[passes.size()];
		// The running pass first: its camera opens.
		var bindings = new ArrayList<PlanBindings>();
		bindings.add(passes.get(plan.output()).bindings);
		warnings.addAll(preparation.diagnostics());
		for (int i = 0; i < passes.size(); ++i) {
			var linked = passes.get(i);
			backbuffer[i] = linked.bindings.readsBackbuffer();
			warnings.addAll(linked.warnings);
			if (i != plan.output()) {
				bindings.add(linked.bindings);
			}
		}
		schedule = PassSchedule.of(plan, backbuffer);
		var present = plan.passes().get(plan.output()).target().present();
		presentSampling = new Sampling(present, present, Mipmap.NONE,
				Wrap.CLAMP, Wrap.CLAMP);
		checkInitial(plan, schedule, warnings);
		var open = PlanBindings.openCamera(bindings);
		camera = open != null
				? new CameraSource.Binding(open, device.createExternalTexture())
				: null;
		for (var linked : passes) {
			var other = linked.bindings.camera;
			if (open != null && other != null && !other.name().equals(open)) {
				warnings.add(linked.warning(other.name(), other.name() +
						" reads black, because only one camera can be open " +
						"and the running shader reads " + open));
			}
		}
	}

	// Initial content that nothing reads has no effect. Preparation warns
	// when the text cannot read the previous frame; whether a backbuffer
	// in it is used is only known now.
	static void checkInitial(
			@NonNull RenderPlan plan,
			@NonNull PassSchedule schedule,
			@NonNull List<ProjectDiagnostic> warnings) {
		for (int i = 0; i < plan.passes().size(); ++i) {
			var pass = plan.passes().get(i);
			if (pass.initial() == null || schedule.isInitialized(i)) {
				continue;
			}
			var target = pass.target();
			var warning = Preparation.initialWithoutEffect(
					plan.programs().get(pass.program()).file(), target.file(),
					target.line());
			if (!warnings.contains(warning)) {
				warnings.add(warning);
			}
		}
	}

	// GL thread. Returns null and adds the preparation's diagnostics and
	// why it cannot draw when a pass does not link or the context cannot
	// draw into its targets as they ask.
	@Nullable
	static PlanRuntime link(
			@NonNull GlDevice device,
			@NonNull PlanShader shader,
			@NonNull TargetFormats formats,
			@NonNull List<ProjectDiagnostic> errors) {
		var preparation = shader.preparation();
		var plan = preparation.plan();
		var reasons = new ArrayList<>(preparation.diagnostics());
		var refusals = plan != null
				? PlanSupport.refusals(plan, formats)
				: List.<ProjectDiagnostic>of();
		if (plan == null || !refusals.isEmpty()) {
			errors.addAll(reasons);
			errors.addAll(refusals);
			return null;
		}
		var passes = new ArrayList<PlanPass>();
		boolean linked = true;
		for (int i = 0; i < plan.passes().size(); ++i) {
			var pass = PlanPass.link(device, plan, i, formats.glesVersion(),
					shader.textureFix(), reasons);
			linked &= pass != null;
			passes.add(pass);
		}
		if (!linked) {
			for (var pass : passes) {
				if (pass != null) {
					pass.release();
				}
			}
			errors.addAll(reasons);
			return null;
		}
		return new PlanRuntime(device, shader, plan, passes, formats);
	}

	// The passes whose builtins it reads, by their index in the plan.
	@NonNull
	List<PlanPass> passes() {
		return passes;
	}

	float quality() {
		return quality;
	}

	float fTimeMax() {
		return passes.get(plan.output()).fTimeMax;
	}

	// Decodes its images off the GL thread from the bytes the preparation
	// captured; until upload() took them, it is not complete.
	void loadTextures(@Nullable Runnable onDecoded) {
		var paths = new LinkedHashSet<ProjectPath>();
		for (var linked : passes) {
			paths.addAll(linked.bindings.imagePaths());
		}
		for (int pass : schedule.reset) {
			var initial = plan.passes().get(pass).initial();
			if (initial.kind() == RenderPlan.Source.Kind.IMAGE) {
				paths.add(initial.image());
			}
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
		for (int i = 0; i < passes.size(); ++i) {
			var bindings = passes.get(i).bindings;
			errors.addAll(bindings.check(images.sizes(), glesVersion,
					maxSize, device.getMaxCubemapSize()));
			if (schedule.isDrawnOnReset(i)) {
				errors.addAll(bindings.checkOnReset(glesVersion));
			}
		}
		var keys = new LinkedHashSet<PlanBindings.Texture>();
		for (var linked : passes) {
			for (var binding : linked.bindings.images) {
				keys.add(binding.texture());
			}
		}
		for (int pass : schedule.reset) {
			var initial = plan.passes().get(pass).initial();
			var seed = initial.kind() == RenderPlan.Source.Kind.IMAGE
					? images.images.get(initial.image())
					: null;
			if (seed == null || !keys.add(new PlanBindings.Texture(
					initial.image(), false))) {
				continue;
			}
			if (seed.width() > maxSize || seed.height() > maxSize) {
				errors.add(locate(initial.image(), initial.image() + " is " +
						seed.width() + "x" + seed.height() + ", larger than " +
						maxSize + ", the largest texture this device supports"));
			}
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
				}
				textures.put(key, texture);
				if (message == null && needsMipmaps(key)) {
					message = device.generateMipmap(texture);
				}
				if (message != null) {
					errors.add(locate(key.image(), key.image() + ": " + message));
				}
			}
		}
		images.recycle();
		for (int i = 0; glesVersion >= 3 && errors.isEmpty() &&
				i < passes.size(); ++i) {
			var linked = passes.get(i);
			var message = linked.createSamplers();
			if (message != null) {
				errors.add(new ProjectDiagnostic(ProjectDiagnostic.Severity.ERROR,
						"Cannot make the samplers of the inputs: " + message,
						linked.pass.target().file(), 0));
			}
		}
		diagnostics.addAll(warnings);
		diagnostics.addAll(errors);
		if (!errors.isEmpty()) {
			failed = true;
			return;
		}
		complete = true;
	}

	// Whether it can never complete, so it must not replace the current
	// build: an image did not decode, upload or get its mipmaps, or the
	// samplers of its inputs could not be made.
	boolean hasFailed() {
		return failed;
	}

	@Nullable
	CameraSource.Binding getCameraBinding() {
		return camera;
	}

	// GL thread, before it replaces the current build and whenever the
	// render size changed: makes the targets the schedule needs at the
	// sizes the plan says, the render size times scale or a fixed size,
	// with a second texture where the previous frame is read. Targets
	// that keep their sizes keep what they hold. Adds why when it cannot;
	// it has nothing to draw into then.
	@NonNull
	ShaderSwap.Allocation createTargets(
			int width,
			int height,
			@NonNull List<ProjectDiagnostic> errors) {
		var invalid = new ArrayList<ProjectDiagnostic>();
		var sizes = targetSizes(plan, schedule, width, height,
				device.getMaxTextureSize(), invalid);
		for (int i = 0; sizes != null && i < sizes.length &&
				invalid.isEmpty(); ++i) {
			if (sizes[i] != null) {
				invalid.addAll(passes.get(i).bindings.checkPasses(sizes,
						glesVersion));
			}
		}
		if (sizes == null || !invalid.isEmpty()) {
			releaseTargets();
			errors.addAll(invalid);
			return ShaderSwap.Allocation.INVALID;
		} else if (Arrays.equals(sizes, madeSizes)) {
			return ShaderSwap.Allocation.KEPT;
		}
		releaseTargets();
		madeSizes = sizes;
		for (int i = 0; i < targets.length; ++i) {
			if (sizes[i] == null) {
				continue;
			}
			targets[i] = PassTarget.create(device, plan.passes().get(i).target(),
					sizes[i], schedule.keepsHistory(i), formats, errors);
			if (targets[i] == null) {
				releaseTargets();
				return ShaderSwap.Allocation.FAILED;
			}
		}
		reset = true;
		return ShaderSwap.Allocation.MADE;
	}

	// The size of every target the schedule needs at a render size,
	// which is already scaled by the quality, by pass and null for a pass
	// without one. Returns null and adds why when one cannot be made with
	// textures of at most maxSize.
	@Nullable
	static RenderPlan.Size[] targetSizes(
			@NonNull RenderPlan plan,
			@NonNull PassSchedule schedule,
			int width,
			int height,
			int maxSize,
			@NonNull List<ProjectDiagnostic> errors) {
		// Only targets are allocated: a render size larger than a texture
		// is fine while every target fits.
		var project = RenderPlan.Size.project(width, height, 1,
				Integer.MAX_VALUE);
		if (!project.isSuccess()) {
			errors.add(project.diagnostic());
			return null;
		}
		var sizes = new RenderPlan.Size[plan.passes().size()];
		for (int i = 0; i < sizes.length; ++i) {
			if (!schedule.hasTarget(i)) {
				continue;
			}
			var size = plan.passes().get(i).target().size(project.value(),
					maxSize);
			if (!size.isSuccess()) {
				errors.add(size.diagnostic());
				return null;
			}
			sizes[i] = size.value();
		}
		return sizes;
	}

	boolean hasTargets() {
		return targets[plan.output()] != null;
	}

	// GL thread. Draws the passes as the schedule says, each into its
	// target cleared to opaque black, and presents the running one with
	// the filter its target asks for.
	void draw(
			@NonNull ShaderRenderPipeline pipeline,
			@NonNull ActiveBuiltins builtins,
			@NonNull FrameInputs frame) {
		var output = targets[plan.output()];
		if (output == null) {
			return;
		}
		if (reset) {
			reset = false;
			initialize(pipeline, builtins, frame);
		}
		for (int pass : schedule.frame) {
			drawPass(pass, builtins, frame, false);
		}
		var shown = output.drawn();
		device.applySampling(shown, presentSampling);
		pipeline.present(shown, frame.surfaceWidth, frame.surfaceHeight);
		for (var target : targets) {
			if (target != null) {
				target.swap();
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
		for (int i = 0; i < targets.length; ++i) {
			if (targets[i] != null) {
				targets[i].discard();
				targets[i] = null;
			}
		}
		madeSizes = null;
	}

	// GL thread. Releases what it draws into; false when it had nothing.
	boolean releaseTargets() {
		madeSizes = null;
		boolean had = false;
		for (int i = 0; i < targets.length; ++i) {
			if (targets[i] != null) {
				targets[i].release();
				targets[i] = null;
				had = true;
			}
		}
		return had;
	}

	// Clears every history and draws over it what the passes the
	// schedule names start as: an image, or what a pass draws at its own
	// size, stretched.
	private void initialize(
			@NonNull ShaderRenderPipeline pipeline,
			@NonNull ActiveBuiltins builtins,
			@NonNull FrameInputs frame) {
		for (var target : targets) {
			if (target != null) {
				target.clearHistory();
			}
		}
		for (int i = 0; i < schedule.reset.length; ++i) {
			int pass = schedule.reset[i];
			var initial = plan.passes().get(pass).initial();
			GlTexture picture;
			if (initial.kind() == RenderPlan.Source.Kind.PASS) {
				if (schedule.drawsInitial(i)) {
					drawPass(initial.pass(), builtins, frame, true);
				}
				picture = targets[initial.pass()].drawn();
			} else {
				picture = textures.get(new PlanBindings.Texture(
						initial.image(), false));
			}
			if (picture instanceof GlTexture2D texture) {
				device.applySampling(texture, Sampling.IMAGE);
				pipeline.stretch(texture, targets[pass].history());
			}
		}
	}

	// With resolution and pixel positions in the target's size, and on
	// reset with time and frame at zero.
	private void drawPass(
			int pass,
			@NonNull ActiveBuiltins builtins,
			@NonNull FrameInputs frame,
			boolean initializing) {
		var target = targets[pass];
		var linked = passes.get(pass);
		target.clear();
		passFrame.scale(frame, target.size().width(), target.size().height());
		passFrame.fTimeMax = linked.fTimeMax;
		if (initializing) {
			passFrame.time = 0;
			passFrame.frame = 0;
		}
		builtins.write(pass, passFrame);
		linked.bind(textures, camera, initializing ? onReset : inFrame);
		device.draw(quad);
		linked.unbind();
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
	// at a target that starts from it.
	@NonNull
	private ProjectDiagnostic locate(
			@NonNull ProjectPath image,
			@NonNull String message) {
		for (var linked : passes) {
			if (linked.bindings.imagePaths().contains(image)) {
				return linked.bindings.locate(image, message);
			}
		}
		for (var pass : plan.passes()) {
			var initial = pass.initial();
			if (initial != null && image.equals(initial.image())) {
				return new ProjectDiagnostic(ProjectDiagnostic.Severity.ERROR,
						message, pass.target().file(), pass.target().line());
			}
		}
		return ProjectDiagnostic.error(message);
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

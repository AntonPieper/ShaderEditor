package de.markusfisch.android.shadereditor.opengl;

import android.content.Context;
import android.graphics.Bitmap;
import android.opengl.GLES11Ext;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
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
// images its inputs read and how each input samples its image. It is
// ready to draw once every image is uploaded, and fails as a whole when
// one cannot be; it owns its GL objects, its target among them, and is
// built again from the plan in a new context.
final class PlanRuntime implements EngineBuild {
	private final float quality;
	@NonNull
	private final GlDevice device;
	@NonNull
	private final Preparation preparation;
	@NonNull
	private final PlanPass running;
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
	private final FullScreenQuad quad = new FullScreenQuad();
	// Told with the outcome, whatever it is.
	@NonNull
	private final List<ProjectDiagnostic> warnings = new ArrayList<>();
	private final Map<PlanBindings.Texture, GlTexture> textures =
			new HashMap<>();
	private final TextureHandOver<DecodedImages> decoded =
			new TextureHandOver<>();
	@Nullable
	private final ShaderTextureResources.SamplerTextureBinding camera;
	@Nullable
	private PassTarget target;
	// Whether the target was made since the last frame.
	private boolean reset;
	private boolean complete;
	private boolean failed;

	private PlanRuntime(
			@NonNull GlDevice device,
			@NonNull PlanShader shader,
			@NonNull PlanPass running,
			int glesVersion) {
		this.device = device;
		this.preparation = shader.preparation();
		this.quality = shader.quality();
		this.running = running;
		this.glesVersion = glesVersion;
		pass = running.pass;
		bindings = running.bindings;
		history = pass.history() || !bindings.history.isEmpty();
		var present = pass.target().present();
		presentSampling = new Sampling(present, present, Mipmap.NONE,
				Wrap.CLAMP, Wrap.CLAMP);
		warnings.addAll(preparation.diagnostics());
		warnings.addAll(running.warnings);
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
		var running = PlanPass.link(device, plan, plan.output(), glesVersion,
				reasons);
		if (running == null) {
			errors.addAll(reasons);
			return null;
		}
		return new PlanRuntime(device, shader, running, glesVersion);
	}

	@NonNull
	@Override
	public GlProgram program() {
		return running.program;
	}

	@Override
	public float quality() {
		return quality;
	}

	@Override
	public float fTimeMax() {
		return running.fTimeMax;
	}

	// Its images do not depend on the render size.
	@Override
	public boolean fits(int width, int height) {
		return true;
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
				device.getMaxTextureSize(), device.getMaxCubemapSize()));
		if (errors.isEmpty()) {
			for (var binding : bindings.images) {
				var key = binding.texture();
				var image = images.images.get(key.image());
				if (image == null || textures.containsKey(key)) {
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
				if (message != null) {
					errors.add(bindings.locate(key.image(),
							key.image() + ": " + message));
				} else if (bindings.needsMipmaps(key)) {
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
			running.createSamplers();
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

	// Its own target at the size the plan says, the render size times
	// scale or a fixed size, with a second texture when the pass reads its
	// previous frame; the pipeline's targets go.
	@Override
	public boolean createTargets(
			@NonNull ShaderRenderPipeline pipeline,
			int width,
			int height,
			@Nullable Bitmap seed,
			@NonNull List<ProjectDiagnostic> errors) {
		if (target != null) {
			target.release();
			target = null;
		}
		// The render size is already scaled by the quality.
		int max = device.getMaxTextureSize();
		var project = RenderPlan.Size.project(width, height, 1, max);
		var size = project.isSuccess()
				? pass.target().size(project.value(), max)
				: project;
		if (!size.isSuccess()) {
			errors.add(size.diagnostic());
			return false;
		}
		target = PassTarget.create(device, pass.target(), size.value(),
				history, errors);
		if (target == null) {
			return false;
		}
		pipeline.releaseTargets();
		reset = true;
		return true;
	}

	@Override
	public boolean hasTargets(@NonNull ShaderRenderPipeline pipeline) {
		return target != null;
	}

	// Cleared to opaque black before every draw, with its previous frame
	// when that is read, and presented with the filter the target asks
	// for.
	@Override
	public void draw(
			@NonNull ShaderRenderPipeline pipeline,
			@NonNull ActiveBuiltins builtins,
			@NonNull FrameInputs frame,
			long now) {
		var target = this.target;
		if (target == null) {
			return;
		}
		var steps = PassSchedule.frame(reset, history);
		reset = false;
		for (int i = 0, n = steps.size(); i < n; ++i) {
			switch (steps.get(i)) {
				case CLEAR_HISTORY -> target.clearHistory();
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
		running.release();
		for (var texture : textures.values()) {
			device.deleteTexture(texture);
		}
		textures.clear();
		if (camera != null) {
			device.deleteTexture(camera.texture());
			camera.setTexture(null);
		}
		if (target != null) {
			target.release();
			target = null;
		}
	}

	@Override
	public void discard() {
		decoded.cancel();
		running.discard();
		for (var texture : textures.values()) {
			texture.invalidate();
		}
		textures.clear();
		if (camera != null) {
			camera.setTexture(null);
		}
		if (target != null) {
			target.discard();
			target = null;
		}
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

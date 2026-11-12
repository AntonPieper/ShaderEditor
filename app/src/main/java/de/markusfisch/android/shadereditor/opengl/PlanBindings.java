package de.markusfisch.android.shadereditor.opengl;

import android.opengl.GLES11Ext;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.markusfisch.android.shadereditor.project.HostDirective.Mipmap;
import de.markusfisch.android.shadereditor.project.HostDirective.Wrap;
import de.markusfisch.android.shadereditor.project.ProjectDiagnostic;
import de.markusfisch.android.shadereditor.project.ProjectPath;
import de.markusfisch.android.shadereditor.project.RenderPlan;
import de.markusfisch.android.shadereditor.project.RenderPlan.Sampling;
import de.markusfisch.android.shadereditor.project.RenderPlan.Source;

// What each active sampler of a pass reads, decided once after linking
// from the pass's inputs and the samplers the program reflects. A sampler
// without an input or an engine provider keeps the empty texture.
final class PlanBindings {
	// An image input and the active sampler it was declared for.
	record ImageBinding(@NonNull GlUniform sampler, @NonNull RenderPlan.Input input) {
		@NonNull
		ProjectPath image() {
			return input.source().image();
		}

		@NonNull
		Sampling sampling() {
			return input.sampling();
		}
	}

	@NonNull
	final List<ImageBinding> images;
	// The first active camera sampler, or null.
	@Nullable
	final GlUniform camera;
	@NonNull
	final Sampling cameraSampling;
	// Annotated inputs the compiler optimized away.
	@NonNull
	final List<ProjectDiagnostic> warnings;

	private PlanBindings(
			@NonNull List<ImageBinding> images,
			@Nullable GlUniform camera,
			@NonNull Sampling cameraSampling,
			@NonNull List<ProjectDiagnostic> warnings) {
		this.images = List.copyOf(images);
		this.camera = camera;
		this.cameraSampling = cameraSampling;
		this.warnings = List.copyOf(warnings);
	}

	@NonNull
	static PlanBindings of(
			@NonNull RenderPlan.Pass pass,
			@NonNull List<GlUniform> samplers) {
		var active = new HashMap<String, GlUniform>();
		GlUniform camera = null;
		for (var sampler : samplers) {
			active.put(sampler.name(), sampler);
			if (camera == null && isCamera(sampler)) {
				camera = sampler;
			}
		}
		var images = new ArrayList<ImageBinding>();
		var warnings = new ArrayList<ProjectDiagnostic>();
		var cameraSampling = Sampling.CAMERA;
		for (var input : pass.inputs()) {
			var sampler = active.get(input.uniform());
			if (sampler == null) {
				warnings.add(new ProjectDiagnostic(
						ProjectDiagnostic.Severity.WARNING,
						input.uniform() + " has an input but the shader does " +
								"not use it, so nothing is bound to it",
						input.file(), input.line()));
			} else if (input.source().kind() == Source.Kind.IMAGE) {
				images.add(new ImageBinding(sampler, input));
			} else if (sampler == camera) {
				cameraSampling = input.sampling();
			}
		}
		return new PlanBindings(images, camera, cameraSampling, warnings);
	}

	// The images that active samplers read, each once.
	@NonNull
	Set<ProjectPath> imagePaths() {
		var paths = new LinkedHashSet<ProjectPath>();
		for (var binding : images) {
			paths.add(binding.image());
		}
		return paths;
	}

	// Mipmaps are generated once at upload when one input asks for them.
	boolean needsMipmaps(@NonNull ProjectPath image) {
		for (var binding : images) {
			if (binding.image().equals(image) &&
					binding.sampling().mipmap() != Mipmap.NONE) {
				return true;
			}
		}
		return false;
	}

	// Why these images cannot be sampled as the inputs ask on a context
	// of this version and texture size limit; empty when they can.
	@NonNull
	List<ProjectDiagnostic> check(
			@NonNull Map<ProjectPath, RenderPlan.Size> sizes,
			int glesVersion,
			int maxTextureSize) {
		var errors = new ArrayList<ProjectDiagnostic>();
		var first = new HashMap<ProjectPath, ImageBinding>();
		for (var binding : images) {
			var image = binding.image();
			var size = sizes.get(image);
			if (size == null) {
				continue;
			}
			boolean tooLarge = size.width() > maxTextureSize ||
					size.height() > maxTextureSize;
			var earlier = first.get(image);
			if (earlier == null) {
				first.put(image, binding);
				if (tooLarge) {
					errors.add(error(binding, image + " is " + describe(size) +
							", larger than " + maxTextureSize + ", the largest " +
							"texture this device supports"));
				}
			}
			if (tooLarge || glesVersion >= 3) {
				continue;
			}
			if (earlier != null && !earlier.sampling().equals(binding.sampling())) {
				errors.add(error(binding, earlier.input().uniform() + " (" +
						earlier.input().file() + ":" + earlier.input().line() +
						") and " + binding.input().uniform() + " read " + image +
						" with different settings, which needs OpenGL ES 3.0; " +
						"this device only has OpenGL ES 2.0"));
			}
			var sampling = binding.sampling();
			if (!(isPowerOfTwo(size.width()) && isPowerOfTwo(size.height())) &&
					(sampling.wrapS() != Wrap.CLAMP ||
							sampling.wrapT() != Wrap.CLAMP ||
							sampling.mipmap() != Mipmap.NONE)) {
				errors.add(error(binding, image + " is " + describe(size) +
						", not a power of two, so OpenGL ES 2.0 reads it only " +
						"with wrap=clamp and mipmap=none, and this device has " +
						"no OpenGL ES 3.0; resize the image or change the " +
						"settings of " + binding.input().uniform()));
			}
		}
		return errors;
	}

	// A problem with an image, located at the first input that reads it.
	@NonNull
	ProjectDiagnostic locate(@NonNull ProjectPath image, @NonNull String message) {
		for (var binding : images) {
			if (binding.image().equals(image)) {
				return error(binding, message);
			}
		}
		return ProjectDiagnostic.error(message);
	}

	private static boolean isCamera(@NonNull GlUniform sampler) {
		return sampler.textureTarget() == GLES11Ext.GL_TEXTURE_EXTERNAL_OES &&
				(UniformTable.CAMERA_BACK.equals(sampler.name()) ||
						UniformTable.CAMERA_FRONT.equals(sampler.name()));
	}

	private static boolean isPowerOfTwo(int n) {
		return n > 0 && (n & (n - 1)) == 0;
	}

	@NonNull
	private static String describe(@NonNull RenderPlan.Size size) {
		return size.width() + "x" + size.height();
	}

	@NonNull
	private static ProjectDiagnostic error(
			@NonNull ImageBinding binding,
			@NonNull String message) {
		return new ProjectDiagnostic(ProjectDiagnostic.Severity.ERROR, message,
				binding.input().file(), binding.input().line());
	}
}

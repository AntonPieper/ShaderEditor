package de.markusfisch.android.shadereditor.opengl;

import android.opengl.GLES11Ext;
import android.opengl.GLES20;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
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
	// The texture made from an image: 2D, or a cubemap cut from a strip.
	record Texture(@NonNull ProjectPath image, boolean cubemap) {
	}

	// An image input and the active sampler it was declared for.
	record ImageBinding(@NonNull GlUniform sampler, @NonNull RenderPlan.Input input) {
		@NonNull
		ProjectPath image() {
			return input.source().image();
		}

		@NonNull
		Texture texture() {
			return new Texture(image(), "samplerCube".equals(input.samplerType()));
		}

		@NonNull
		Sampling sampling() {
			return input.sampling();
		}
	}

	// A sampler that reads the previous frame of its own pass: the active
	// backbuffer that no input annotates.
	record HistoryBinding(@NonNull GlUniform sampler, @NonNull Sampling sampling) {
	}

	@NonNull
	final List<ImageBinding> images;
	@NonNull
	final List<HistoryBinding> history;
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
			@NonNull List<HistoryBinding> history,
			@Nullable GlUniform camera,
			@NonNull Sampling cameraSampling,
			@NonNull List<ProjectDiagnostic> warnings) {
		this.images = List.copyOf(images);
		this.history = List.copyOf(history);
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
		var history = new ArrayList<HistoryBinding>();
		var declared = new HashSet<String>();
		var warnings = new ArrayList<ProjectDiagnostic>();
		var cameraSampling = Sampling.CAMERA;
		for (var input : pass.inputs()) {
			declared.add(input.uniform());
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
		var backbuffer = active.get(UniformTable.BACKBUFFER);
		if (backbuffer != null && !declared.contains(backbuffer.name()) &&
				backbuffer.textureTarget() == GLES20.GL_TEXTURE_2D) {
			history.add(new HistoryBinding(backbuffer, Sampling.PASS));
		}
		return new PlanBindings(images, history, camera, cameraSampling,
				warnings);
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
	boolean needsMipmaps(@NonNull Texture texture) {
		for (var binding : images) {
			if (binding.texture().equals(texture) &&
					binding.sampling().mipmap() != Mipmap.NONE) {
				return true;
			}
		}
		return false;
	}

	// Why these images cannot be sampled as the inputs ask on a context
	// of this version and these texture size limits; empty when they can.
	@NonNull
	List<ProjectDiagnostic> check(
			@NonNull Map<ProjectPath, RenderPlan.Size> sizes,
			int glesVersion,
			int maxTextureSize,
			int maxCubemapSize) {
		var errors = new ArrayList<ProjectDiagnostic>();
		var first = new HashMap<Texture, ImageBinding>();
		for (var binding : images) {
			var image = binding.image();
			var size = sizes.get(image);
			if (size == null) {
				continue;
			}
			var texture = binding.texture();
			// A cubemap is as large as one of its faces.
			var side = texture.cubemap()
					? new RenderPlan.Size(size.height(), size.height())
					: size;
			int max = texture.cubemap() ? maxCubemapSize : maxTextureSize;
			String invalid = null;
			if (texture.cubemap() &&
					!CubemapStrip.isStrip(size.width(), size.height())) {
				invalid = image + " is " + describe(size) + ", but a " +
						"samplerCube reads a strip of six square faces, six " +
						"times as wide as high";
			} else if (side.width() > max || side.height() > max) {
				invalid = describe(binding, side) + ", larger than " + max +
						", the largest " + (texture.cubemap()
						? "cubemap face"
						: "texture") + " this device supports";
			}
			var earlier = first.get(texture);
			if (earlier == null) {
				first.put(texture, binding);
				if (invalid != null) {
					errors.add(error(binding, invalid));
				}
			}
			if (invalid != null || glesVersion >= 3) {
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
			if (!(isPowerOfTwo(side.width()) && isPowerOfTwo(side.height())) &&
					(sampling.wrapS() != Wrap.CLAMP ||
							sampling.wrapT() != Wrap.CLAMP ||
							sampling.mipmap() != Mipmap.NONE)) {
				errors.add(error(binding, describe(binding, side) +
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
	private static String describe(
			@NonNull ImageBinding binding,
			@NonNull RenderPlan.Size texture) {
		return binding.image() + (binding.texture().cubemap()
				? " has faces of "
				: " is ") + describe(texture);
	}

	@NonNull
	private static ProjectDiagnostic error(
			@NonNull ImageBinding binding,
			@NonNull String message) {
		return new ProjectDiagnostic(ProjectDiagnostic.Severity.ERROR, message,
				binding.input().file(), binding.input().line());
	}
}

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.markusfisch.android.shadereditor.project.HostDirective.Frame;
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
	private static final Pattern COMMENT = Pattern.compile(
			"//[^\n]*|/\\*.*?(\\*/|$)", Pattern.DOTALL);
	private static final Pattern EXTERNAL = Pattern.compile(
			"\\bsamplerExternalOES\\s+(\\w+)");

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

	// An input that reads the previous frame of its own pass, or with a
	// null input the active backbuffer that no input annotates.
	record HistoryBinding(
			@NonNull GlUniform sampler,
			@NonNull Sampling sampling,
			@Nullable RenderPlan.Input input) {
	}

	// The active samplers as the text declares them, in unit order.
	@NonNull
	final List<GlUniform> samplers;
	@NonNull
	final List<ImageBinding> images;
	// The unannotated backbuffer first.
	@NonNull
	final List<HistoryBinding> history;
	// The active camera sampler declared first, or null.
	@Nullable
	final GlUniform camera;
	@NonNull
	final Sampling cameraSampling;
	// Annotated inputs the compiler optimized away.
	@NonNull
	final List<ProjectDiagnostic> warnings;
	// What the text declares as samplerExternalOES, in order.
	@NonNull
	private final Set<String> external;

	private PlanBindings(
			@NonNull List<GlUniform> samplers,
			@NonNull Set<String> external,
			@NonNull List<ImageBinding> images,
			@NonNull List<HistoryBinding> history,
			@Nullable GlUniform camera,
			@NonNull Sampling cameraSampling,
			@NonNull List<ProjectDiagnostic> warnings) {
		this.samplers = List.copyOf(samplers);
		this.external = external;
		this.images = List.copyOf(images);
		this.history = List.copyOf(history);
		this.camera = camera;
		this.cameraSampling = cameraSampling;
		this.warnings = List.copyOf(warnings);
	}

	// self is the index of the pass in its plan, text what its program
	// compiles.
	@NonNull
	static PlanBindings of(
			@NonNull RenderPlan.Pass pass,
			int self,
			@NonNull List<GlUniform> reflected,
			@NonNull String text) {
		var active = new HashMap<String, GlUniform>();
		var external = externalSamplers(text);
		var samplers = new ArrayList<GlUniform>();
		for (var sampler : reflected) {
			var declared = declared(sampler, external);
			samplers.add(declared);
			active.put(declared.name(), declared);
		}
		var camera = camera(external, samplers, active);
		var images = new ArrayList<ImageBinding>();
		var history = new ArrayList<HistoryBinding>();
		var declared = new HashSet<String>();
		var warnings = new ArrayList<ProjectDiagnostic>();
		var cameraSampling = Sampling.CAMERA;
		for (var input : pass.inputs()) {
			declared.add(input.uniform());
			var source = input.source();
			var sampler = active.get(input.uniform());
			if (sampler == null) {
				warnings.add(new ProjectDiagnostic(
						ProjectDiagnostic.Severity.WARNING,
						input.uniform() + " has an input but the shader does " +
								"not use it, so nothing is bound to it",
						input.file(), input.line()));
			} else if (source.kind() == Source.Kind.IMAGE) {
				images.add(new ImageBinding(sampler, input));
			} else if (source.kind() == Source.Kind.PASS &&
					source.pass() == self && input.frame() == Frame.PREVIOUS) {
				history.add(new HistoryBinding(sampler, input.sampling(), input));
			} else if (sampler == camera) {
				cameraSampling = input.sampling();
			}
		}
		var backbuffer = active.get(UniformTable.BACKBUFFER);
		if (backbuffer != null && !declared.contains(backbuffer.name()) &&
				backbuffer.textureTarget() == GLES20.GL_TEXTURE_2D) {
			history.add(0, new HistoryBinding(backbuffer, Sampling.PASS, null));
		}
		return new PlanBindings(samplers, external, images, history, camera,
				cameraSampling, warnings);
	}

	// The uniforms of the program as the text declares them, so a camera
	// the driver reports as sampler2D is still the camera for the
	// sources it needs and for the warnings about it.
	@NonNull
	Map<String, GlUniform> declared(@NonNull Map<String, GlUniform> uniforms) {
		var declared = new HashMap<String, GlUniform>();
		for (var entry : uniforms.entrySet()) {
			declared.put(entry.getKey(), declared(entry.getValue(), external));
		}
		return declared;
	}

	// The camera that opens for passes that read different ones: that of
	// the first pass, the running one, that reads one. Only one camera can
	// be open.
	@Nullable
	static String openCamera(@NonNull List<PlanBindings> passes) {
		for (var bindings : passes) {
			if (bindings.camera != null) {
				return bindings.camera.name();
			}
		}
		return null;
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
		for (int i = 1; glesVersion < 3 && i < history.size(); ++i) {
			var earlier = history.get(0);
			var input = history.get(i).input();
			if (input != null &&
					!earlier.sampling().equals(history.get(i).sampling())) {
				errors.add(error(input, describe(earlier) + " and " +
						input.uniform() + " read the previous frame with " +
						"different settings, which needs OpenGL ES 3.0; this " +
						"device only has OpenGL ES 2.0"));
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

	// Some drivers, the emulator's among them, report a samplerExternalOES
	// as a sampler2D; its declaration tells the two apart then.
	@NonNull
	private static GlUniform declared(
			@NonNull GlUniform uniform,
			@NonNull Set<String> external) {
		return uniform.type() == GLES20.GL_SAMPLER_2D &&
				external.contains(uniform.name())
				? new GlUniform(uniform.name(),
						GLES11Ext.GL_SAMPLER_EXTERNAL_OES, uniform.size(),
						uniform.location(), uniform.unit())
				: uniform;
	}

	// The camera the text declares first, as the old renderer chose
	// between two, or else the first one reflected.
	@Nullable
	private static GlUniform camera(
			@NonNull Set<String> external,
			@NonNull List<GlUniform> samplers,
			@NonNull Map<String, GlUniform> active) {
		for (var name : external) {
			var sampler = active.get(name);
			if (sampler != null && isCamera(sampler)) {
				return sampler;
			}
		}
		for (var sampler : samplers) {
			if (isCamera(sampler)) {
				return sampler;
			}
		}
		return null;
	}

	private static boolean isCamera(@NonNull GlUniform sampler) {
		return (UniformTable.CAMERA_BACK.equals(sampler.name()) ||
				UniformTable.CAMERA_FRONT.equals(sampler.name())) &&
				sampler.textureTarget() == GLES11Ext.GL_TEXTURE_EXTERNAL_OES;
	}

	// The names text declares as samplerExternalOES outside of comments,
	// in the order declared.
	@NonNull
	static Set<String> externalSamplers(@NonNull String text) {
		var names = new LinkedHashSet<String>();
		Matcher m = EXTERNAL.matcher(COMMENT.matcher(text).replaceAll(" "));
		while (m.find()) {
			names.add(m.group(1));
		}
		return names;
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
	private static String describe(@NonNull HistoryBinding binding) {
		var input = binding.input();
		return input == null
				? binding.sampler().name()
				: input.uniform() + " (" + input.file() + ":" + input.line() + ")";
	}

	@NonNull
	private static ProjectDiagnostic error(
			@NonNull ImageBinding binding,
			@NonNull String message) {
		return error(binding.input(), message);
	}

	@NonNull
	private static ProjectDiagnostic error(
			@NonNull RenderPlan.Input input,
			@NonNull String message) {
		return new ProjectDiagnostic(ProjectDiagnostic.Severity.ERROR, message,
				input.file(), input.line());
	}
}

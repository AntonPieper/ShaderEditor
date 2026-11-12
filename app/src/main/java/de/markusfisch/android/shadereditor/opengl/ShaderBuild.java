package de.markusfisch.android.shadereditor.opengl;

import android.content.Context;
import android.graphics.Bitmap;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;

import de.markusfisch.android.shadereditor.database.Database;
import de.markusfisch.android.shadereditor.project.ProjectDiagnostic;

// One shader linked in the current context, with its textures.
final class ShaderBuild implements EngineBuild {
	@NonNull
	final GlProgram program;
	@NonNull
	final PreparedShaderSource source;
	@NonNull
	final ShaderTextureResources textures;
	final float quality;
	@NonNull
	private final GlDevice device;
	private final int backBufferUnit;
	private final TextureHandOver<DecodedTextures> decoded =
			new TextureHandOver<>();
	private boolean complete;
	private boolean failed;
	// The render size its backbuffer seed decodes for.
	private int seedWidth;
	private int seedHeight;

	private ShaderBuild(
			@NonNull GlDevice device,
			@NonNull GlProgram program,
			@NonNull PreparedShaderSource source,
			float quality) {
		this.device = device;
		this.program = program;
		this.source = source;
		this.quality = quality;
		textures = ShaderTextureResources.create(source.getSamplers());
		textures.createExternalTextures(device);
		textures.resolveUnits(program);
		backBufferUnit = program.getTextureUnit(UniformTable.BACKBUFFER);
	}

	// GL thread. Returns null and adds the reasons to errors when the
	// shader does not link.
	@Nullable
	static ShaderBuild link(
			@NonNull GlDevice device,
			@NonNull String text,
			float quality,
			int version,
			@NonNull List<ProjectDiagnostic> errors) {
		var source = ShaderSourcePreparer.prepare(text, version, errors);
		var fragmentShader = source.getFragmentShader();
		if (fragmentShader == null) {
			return null;
		}
		var result = device.createProgram(
				source.getVertexShader(
						FullScreenQuad.VERTEX_SHADER,
						FullScreenQuad.VERTEX_SHADER_3,
						version),
				fragmentShader.getSource(),
				fragmentShader.getLineMapping());
		if (!result.succeeded() || result.getProgram() == null) {
			errors.addAll(result.getInfoLog());
			return null;
		}
		return new ShaderBuild(device, result.getProgram(), source, quality);
	}

	// Decodes the images it samples off the GL thread; until upload()
	// took them, the build is not complete.
	@Override
	public void loadTextures(
			@NonNull Context context,
			int width,
			int height,
			@Nullable Runnable onDecoded) {
		int generation = decoded.begin();
		var samplers = textures.getSamplers();
		var backBuffer = source.getBackBufferParameters();
		seedWidth = width;
		seedHeight = height;
		complete = !DecodedTextures.isNeeded(samplers, backBuffer);
		if (complete) {
			return;
		}
		new Thread(() -> {
			DecodedTextures result;
			try {
				var textureDao = Database.getInstance(context).getDataSource().texture;
				result = DecodedTextures.decode(
						samplers,
						backBuffer,
						width,
						height,
						new DecodedTextures.BitmapSource() {
							@Override
							public Bitmap get(@NonNull String name) {
								return textureDao.getTextureBitmap(name);
							}

							@Override
							public Bitmap get(long id) {
								return textureDao.getTextureBitmap(id);
							}
						});
			} catch (RuntimeException | OutOfMemoryError e) {
				result = DecodedTextures.failed(e);
			}
			// A build that was released, with its view perhaps, asks for no
			// frame.
			if (decoded.deliver(generation, result) && onDecoded != null) {
				onDecoded.run();
			}
		}, "ShaderTextureDecoder").start();
	}

	// GL thread. Uploads what was decoded, if it is ready, and returns it
	// for its backbuffer seed; the caller recycles it. The build is
	// complete when everything uploaded, and cannot draw when it adds
	// errors. Once complete, it adds what it reads that nothing sets.
	@Nullable
	@Override
	public DecodedTextures upload(@NonNull List<ProjectDiagnostic> errors) {
		var result = decoded.take();
		if (result != null) {
			var error = result.error();
			var uploadErrors = error != null
					? List.of(ProjectDiagnostic.error(error))
					: textures.upload(device, result);
			errors.addAll(uploadErrors);
			complete = uploadErrors.isEmpty();
			failed = !complete;
		}
		var fragmentShader = source.getFragmentShader();
		if (complete && fragmentShader != null) {
			errors.addAll(UniformWarnings.check(program.getUniforms().values(),
					textures.getTexturedNames(), UniformWarnings.ADD_LIBRARY_TEXTURE,
					fragmentShader.getSource(), fragmentShader.getLineMapping()));
		}
		return result;
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
		return source.getFTimeMax();
	}

	// The backbuffer preset is tiled to the render size.
	@Override
	public boolean fits(int width, int height) {
		return !source.getBackBufferParameters().hasPreset() ||
				(seedWidth == EngineInputs.renderSize(width, quality) &&
						seedHeight == EngineInputs.renderSize(height, quality));
	}

	@Override
	public boolean hasFailed() {
		return failed;
	}

	@Nullable
	@Override
	public ShaderTextureResources.SamplerTextureBinding getCameraBinding() {
		return textures.getFirstBinding(
				UniformTable.CAMERA_BACK,
				UniformTable.CAMERA_FRONT);
	}

	// The pipeline's two targets, which it draws into in turn.
	@Override
	public boolean createTargets(
			@NonNull ShaderRenderPipeline pipeline,
			int width,
			int height,
			@Nullable Bitmap seed,
			@NonNull List<ProjectDiagnostic> errors) {
		var targets = pipeline.createTargets(width, height,
				source.getBackBufferSampling(), seed, errors);
		if (targets == null) {
			return false;
		}
		pipeline.useTargets(targets);
		return true;
	}

	@Override
	public boolean hasTargets(@NonNull ShaderRenderPipeline pipeline) {
		return pipeline.hasTargets();
	}

	// Into the front target, with the back one bound as backbuffer.
	@Override
	public void draw(
			@NonNull ShaderRenderPipeline pipeline,
			@NonNull ActiveBuiltins builtins,
			@NonNull FrameInputs frame,
			long now) {
		builtins.write(frame, now);
		device.bindEmptyTextures(program);
		device.bindTexture(backBufferUnit, pipeline.getBackTexture());
		textures.bind(device);
		pipeline.renderMainPass(program);
		pipeline.renderSurfacePass(frame.surfaceWidth, frame.surfaceHeight);
		pipeline.swapTargets();
	}

	@Override
	public boolean isComplete() {
		return complete;
	}

	@Override
	public void release() {
		decoded.cancel();
		device.deleteProgram(program);
		textures.release(device);
	}

	@Override
	public void discard() {
		decoded.cancel();
		program.invalidate();
		textures.discard();
	}
}

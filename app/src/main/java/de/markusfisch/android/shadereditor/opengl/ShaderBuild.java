package de.markusfisch.android.shadereditor.opengl;

import android.content.Context;
import android.graphics.Bitmap;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;

import de.markusfisch.android.shadereditor.database.Database;
import de.markusfisch.android.shadereditor.project.ProjectDiagnostic;

// One shader linked in the current context, with its textures.
final class ShaderBuild implements ShaderSwap.Build {
	@NonNull
	final GlProgram program;
	@NonNull
	final PreparedShaderSource source;
	@NonNull
	final ShaderTextureResources textures;
	final float quality;
	@NonNull
	private final GlDevice device;
	private final TextureHandOver<DecodedTextures> decoded =
			new TextureHandOver<>();
	private boolean complete;

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
		var source = ShaderSourcePreparer.prepare(text, version);
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
	void loadTextures(
			@NonNull Context context,
			int width,
			int height,
			@Nullable Runnable onDecoded) {
		int generation = decoded.begin();
		var samplers = textures.getSamplers();
		var backBuffer = source.getBackBufferParameters();
		complete = !DecodedTextures.isNeeded(samplers, backBuffer);
		if (complete) {
			return;
		}
		new Thread(() -> {
			var textureDao = Database.getInstance(context).getDataSource().texture;
			decoded.deliver(generation, DecodedTextures.decode(
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
					}));
			if (onDecoded != null) {
				onDecoded.run();
			}
		}, "ShaderTextureDecoder").start();
	}

	// GL thread. Uploads what was decoded, if it is ready, and returns it
	// for its backbuffer seed; the caller recycles it.
	@Nullable
	DecodedTextures upload(@NonNull List<ProjectDiagnostic> errors) {
		var result = decoded.take();
		if (result != null) {
			errors.addAll(textures.upload(device, result));
			complete = true;
		}
		return result;
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

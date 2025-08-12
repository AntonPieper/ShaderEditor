package de.markusfisch.android.shadereditor.platform.render.gl.handlers;

import android.opengl.GLES32;

import androidx.annotation.NonNull;

import de.markusfisch.android.shadereditor.engine.asset.ShaderAsset;
import de.markusfisch.android.shadereditor.engine.pipeline.GpuCommand;
import de.markusfisch.android.shadereditor.engine.scene.Geometry;
import de.markusfisch.android.shadereditor.engine.scene.Uniform;
import de.markusfisch.android.shadereditor.platform.render.gl.GlesBinder;
import de.markusfisch.android.shadereditor.platform.render.gl.core.GlesCommandHandler;
import de.markusfisch.android.shadereditor.platform.render.gl.core.GlesRenderContext;
import de.markusfisch.android.shadereditor.platform.render.gl.core.ShaderCache;
import de.markusfisch.android.shadereditor.platform.render.gl.managers.GlesFramebufferManager;
import de.markusfisch.android.shadereditor.platform.render.gl.managers.GlesGeometryManager;

public final class BlitHandler implements GlesCommandHandler<GpuCommand.Blit> {
	private static final ShaderAsset BLIT_SHADER = new ShaderAsset(
			// VS
			"attribute vec4 a_Position;attribute vec2 a_TexCoord;varying vec2 v_TexCoord;" +
					"void main(){gl_Position=a_Position;v_TexCoord=a_TexCoord;}",
			// FS
			"precision mediump float;varying vec2 v_TexCoord;uniform sampler2D uTex;" +
					"void main(){gl_FragColor=texture2D(uTex,v_TexCoord);}"
	);
	@NonNull
	private final ShaderCache shaders;
	@NonNull
	private final GlesGeometryManager geometries;
	@NonNull
	private final GlesFramebufferManager framebuffers;
	@NonNull
	private final GlesBinder binder;
	private final Geometry fsq = Geometry.fullscreenQuad();
	private ShaderCache.Program blit;

	public BlitHandler(
			@NonNull ShaderCache shaders,
			@NonNull GlesGeometryManager geometries,
			@NonNull GlesFramebufferManager framebuffers,
			@NonNull GlesBinder binder) {
		this.shaders = shaders;
		this.geometries = geometries;
		this.framebuffers = framebuffers;
		this.binder = binder;
	}

	@NonNull
	@Override
	public Class<GpuCommand.Blit> type() {
		return GpuCommand.Blit.class;
	}

	@Override
	public void handle(@NonNull GpuCommand.Blit cmd, @NonNull GlesRenderContext ctx) {
		GLES32.glBindFramebuffer(GLES32.GL_FRAMEBUFFER,
				framebuffers.getFramebufferHandle(cmd.dst()));
		if (!cmd.dst().isDefault()) GLES32.glViewport(0, 0, cmd.dst().width(), cmd.dst().height());

		if (blit == null) blit = shaders.get(BLIT_SHADER);
		GLES32.glUseProgram(blit.programId());
		ctx.setCurrentProgram(blit);
		binder.bind(blit.locate("uTex"), new Uniform.Sampler2D(cmd.src()));

		int vao = geometries.getGeometryHandle(fsq);
		GLES32.glBindVertexArray(vao);
		GLES32.glDrawArrays(GLES32.GL_TRIANGLE_STRIP, 0, fsq.vertexCount());
	}
}
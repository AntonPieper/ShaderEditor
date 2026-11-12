package de.markusfisch.android.shadereditor.opengl;

import android.opengl.GLES20;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;

import de.markusfisch.android.shadereditor.project.ProjectDiagnostic;
import de.markusfisch.android.shadereditor.project.RenderPlan;
import de.markusfisch.android.shadereditor.project.RenderPlan.Sampling;

// What a pass draws into: one texture, or two when its previous frame is
// read, which change roles at swap().
final class PassTarget {
	@NonNull
	private final GlDevice device;
	@NonNull
	private final RenderPlan.Size size;
	@NonNull
	private final GlTexture2D[] textures;
	@NonNull
	private final GlFramebuffer[] framebuffers;
	private int current;

	private PassTarget(
			@NonNull GlDevice device,
			@NonNull RenderPlan.Size size,
			int count) {
		this.device = device;
		this.size = size;
		textures = new GlTexture2D[count];
		framebuffers = new GlFramebuffer[count];
	}

	// Returns null and adds why when it cannot be drawn into.
	@Nullable
	static PassTarget create(
			@NonNull GlDevice device,
			@NonNull RenderPlan.Target target,
			@NonNull RenderPlan.Size size,
			boolean history,
			@NonNull List<ProjectDiagnostic> errors) {
		var pass = new PassTarget(device, size, history ? 2 : 1);
		for (int i = 0; i < pass.textures.length; ++i) {
			var texture = pass.textures[i] = device.createTexture2D();
			device.allocateTexture2D(texture, size.width(), size.height());
			device.applySampling(texture, Sampling.PASS);
			var framebuffer = pass.framebuffers[i] = device.createFramebuffer();
			device.attachColor(framebuffer, texture);
			int status = device.checkFramebufferStatus(framebuffer);
			if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
				device.bindFramebuffer(null);
				pass.release();
				errors.add(new ProjectDiagnostic(ProjectDiagnostic.Severity.ERROR,
						"Cannot draw into a target of " + size.width() + "x" +
								size.height() + ", framebuffer incomplete: 0x" +
								Integer.toHexString(status),
						target.file(), target.line()));
				return null;
			}
		}
		device.bindFramebuffer(null);
		return pass;
	}

	@NonNull
	RenderPlan.Size size() {
		return size;
	}

	// The frame completed last: before swap() the previous one.
	@NonNull
	GlTexture2D completed() {
		return textures[textures.length - 1 - current];
	}

	// What holds the previous frame until swap().
	@NonNull
	GlFramebuffer history() {
		return framebuffers[framebuffers.length - 1 - current];
	}

	void clearHistory() {
		bind(history());
	}

	void clear() {
		bind(framebuffers[current]);
	}

	void swap() {
		current = textures.length - 1 - current;
	}

	void release() {
		for (int i = 0; i < textures.length; ++i) {
			device.deleteFramebuffer(framebuffers[i]);
			device.deleteTexture(textures[i]);
		}
	}

	// After the context was lost with its objects.
	void discard() {
		for (int i = 0; i < textures.length; ++i) {
			framebuffers[i].invalidate();
			textures[i].invalidate();
		}
	}

	private void bind(@NonNull GlFramebuffer framebuffer) {
		device.bindFramebuffer(framebuffer);
		device.setViewport(0, 0, size.width(), size.height());
		device.clear(GLES20.GL_COLOR_BUFFER_BIT);
	}
}

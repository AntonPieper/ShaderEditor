package de.markusfisch.android.shadereditor.opengl;

import android.opengl.GLES20;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;

import de.markusfisch.android.shadereditor.project.HostDirective;
import de.markusfisch.android.shadereditor.project.ProjectDiagnostic;
import de.markusfisch.android.shadereditor.project.RenderPlan;
import de.markusfisch.android.shadereditor.project.RenderPlan.Sampling;

// What a pass draws into: one texture, or two when its previous frame is
// read, which change roles at swap(). When an input reads it with a
// mipmap filter, its textures have mipmaps, made again after every draw.
final class PassTarget {
	@NonNull
	private final GlDevice device;
	@NonNull
	private final RenderPlan.Size size;
	@NonNull
	private final GlTexture2D[] textures;
	@NonNull
	private final GlFramebuffer[] framebuffers;
	@NonNull
	private final HostDirective.Format format;
	private boolean mipmaps;
	private int current;
	// The builds that draw into it: the one it was made for, and the
	// build that goes on from that one, until either is released.
	private int owners = 1;

	private PassTarget(
			@NonNull GlDevice device,
			@NonNull RenderPlan.Size size,
			@NonNull HostDirective.Format format,
			int count) {
		this.device = device;
		this.size = size;
		this.format = format;
		textures = new GlTexture2D[count];
		framebuffers = new GlFramebuffer[count];
	}

	// Returns null and adds why when it cannot be drawn into. With
	// mipmaps, its textures have them; where the device cannot make
	// them, it adds why as a warning and has none.
	@Nullable
	static PassTarget create(
			@NonNull GlDevice device,
			@NonNull RenderPlan.Target target,
			@NonNull RenderPlan.Size size,
			boolean history,
			boolean mipmaps,
			@NonNull TargetFormats formats,
			@NonNull List<ProjectDiagnostic> errors) {
		var format = target.format();
		var pass = new PassTarget(device, size, format, history ? 2 : 1);
		pass.mipmaps = mipmaps;
		for (int i = 0; i < pass.textures.length; ++i) {
			var texture = pass.textures[i] = device.createTexture2D();
			var message = device.allocateTexture2D(texture, size.width(),
					size.height(), formats.internalFormat(format),
					formats.type(format));
			device.applySampling(texture, Sampling.PASS);
			var framebuffer = pass.framebuffers[i] = device.createFramebuffer();
			if (message == null) {
				message = device.attachColor(framebuffer, texture);
			}
			int status = device.checkFramebufferStatus(framebuffer);
			if (message != null || status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
				device.bindFramebuffer(null);
				pass.release();
				errors.add(new ProjectDiagnostic(ProjectDiagnostic.Severity.ERROR,
						"Cannot draw into a target of " + size.width() + "x" +
								size.height() + " in format=" +
								TargetFormats.name(format) + ", " +
								(message != null
										? message
										: "framebuffer incomplete: 0x" +
										Integer.toHexString(status)),
						target.file(), target.line()));
				return null;
			}
		}
		device.bindFramebuffer(null);
		for (int i = 0; pass.mipmaps && i < pass.textures.length; ++i) {
			var message = device.generateMipmap(pass.textures[i]);
			if (message != null) {
				pass.mipmaps = false;
				errors.add(new ProjectDiagnostic(
						ProjectDiagnostic.Severity.WARNING,
						"Cannot make mipmaps of a target of " + size.width() +
								"x" + size.height() + " in format=" +
								TargetFormats.name(format) + ", " + message +
								"; it is read with its base filter",
						target.file(), target.line()));
			}
		}
		for (var texture : pass.textures) {
			texture.setMipmaps(pass.mipmaps);
		}
		return pass;
	}

	@NonNull
	RenderPlan.Size size() {
		return size;
	}

	@NonNull
	HostDirective.Format format() {
		return format;
	}

	// Whether it is what create() makes for target at size with or
	// without history and mipmaps.
	boolean fits(@NonNull RenderPlan.Target target,
			@NonNull RenderPlan.Size size, boolean history, boolean mipmaps) {
		return this.size.equals(size) && format == target.format() &&
				(textures.length == 2) == history && this.mipmaps == mipmaps;
	}

	// One more build draws into it, as the one that has it did, and
	// what it holds goes on; each releases it.
	@NonNull
	PassTarget share() {
		++owners;
		return this;
	}

	// What the pass draws into, and until swap() the frame it drew.
	@NonNull
	GlTexture2D drawn() {
		return textures[current];
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

	// After the pass drew into drawn(): its mipmaps again, when it has
	// them.
	void updateMipmaps() {
		if (mipmaps) {
			device.generateMipmap(drawn());
		}
	}

	// After a reset cleared the history or drew into it.
	void updateHistoryMipmaps() {
		if (mipmaps) {
			device.generateMipmap(completed());
		}
	}

	void swap() {
		current = textures.length - 1 - current;
	}

	void release() {
		if (--owners > 0) {
			return;
		}
		for (int i = 0; i < textures.length; ++i) {
			device.deleteFramebuffer(framebuffers[i]);
			device.deleteTexture(textures[i]);
		}
	}

	// After the context was lost with its objects.
	void discard() {
		if (--owners > 0) {
			return;
		}
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

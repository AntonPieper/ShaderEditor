package de.markusfisch.android.shadereditor.opengl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;

import androidx.annotation.NonNull;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import de.markusfisch.android.shadereditor.project.RenderPlan.Sampling;

public class DecodedTexturesTest {
	private static final DiscoveredSampler TEXTURE = sampler("noise",
			GLES20.GL_TEXTURE_2D);
	private static final DiscoveredSampler CUBEMAP = sampler("sky",
			GLES20.GL_TEXTURE_CUBE_MAP);
	private static final DiscoveredSampler CAMERA = sampler("cameraBack",
			GLES11Ext.GL_TEXTURE_EXTERNAL_OES);

	@Test
	public void decodesOnlyImagesAndPresets() {
		assertFalse(DecodedTextures.isNeeded(List.of(),
				BackBufferParameters.NONE));
		assertFalse(DecodedTextures.isNeeded(List.of(CAMERA),
				BackBufferParameters.NONE));
		assertTrue(DecodedTextures.isNeeded(List.of(CAMERA, TEXTURE),
				BackBufferParameters.NONE));
		assertTrue(DecodedTextures.isNeeded(List.of(CUBEMAP),
				BackBufferParameters.NONE));
		assertTrue(DecodedTextures.isNeeded(List.of(),
				new BackBufferParameters("7")));
	}

	@Test
	public void missingTexturesLeaveTheirSamplersEmpty() {
		var requested = new ArrayList<String>();
		var samplers = List.of(CAMERA, TEXTURE, CUBEMAP);
		var textures = DecodedTextures.decode(
				samplers,
				new BackBufferParameters("7"),
				64,
				32,
				name -> {
					requested.add(name);
					return null;
				});
		assertEquals(List.of("noise", "sky", "7"), requested);
		for (int i = 0; i < samplers.size(); ++i) {
			assertNull(textures.get(i));
		}
		assertNull(textures.backBuffer());
		textures.recycle();
	}

	@Test
	public void presetIsLookedUpByNameAndThenByRowId() {
		var requested = new ArrayList<Object>();
		DecodedTextures.BitmapSource source = new DecodedTextures.BitmapSource() {
			@Override
			public Bitmap get(@NonNull String name) {
				requested.add(name);
				return null;
			}

			@Override
			public Bitmap get(long id) {
				requested.add(id);
				return null;
			}
		};
		for (String preset : new String[]{"7", "noise", "07"}) {
			DecodedTextures.decode(List.of(), new BackBufferParameters(preset),
					64, 32, source).recycle();
		}
		assertEquals(List.of("7", 7L, "noise", "07"), requested);
	}

	@Test
	public void withoutAPresetTheBackbufferStartsCleared() {
		var textures = DecodedTextures.decode(
				List.of(),
				BackBufferParameters.NONE,
				64,
				32,
				name -> {
					throw new AssertionError(name);
				});
		assertNull(textures.backBuffer());
	}

	@Test
	public void cubemapAtlasMustHoldEveryFaceItCuts() {
		assertTrue(DecodedTextures.isCubemapAtlas(2, 3));
		assertTrue(DecodedTextures.isCubemapAtlas(1024, 1536));
		assertTrue(DecodedTextures.isCubemapAtlas(101, 150));
		assertFalse(DecodedTextures.isCubemapAtlas(0, 0));
		assertFalse(DecodedTextures.isCubemapAtlas(1, 1));
		assertFalse(DecodedTextures.isCubemapAtlas(1, 3));
		// An odd width puts the right column past the edge.
		assertFalse(DecodedTextures.isCubemapAtlas(3, 6));
		assertFalse(DecodedTextures.isCubemapAtlas(99, 150));
		// A square image is too short for three rows.
		assertFalse(DecodedTextures.isCubemapAtlas(512, 512));
	}

	@Test
	public void decodeThatThrowsBecomesAFailure() {
		var thrown = assertThrows(IllegalStateException.class,
				() -> DecodedTextures.decode(
						List.of(TEXTURE, CUBEMAP),
						BackBufferParameters.NONE,
						64,
						32,
						name -> {
							if (name.equals("sky")) {
								throw new IllegalStateException("database closed");
							}
							return null;
						}));
		var failed = DecodedTextures.failed(thrown);
		assertEquals("Cannot load the textures: database closed", failed.error());
		assertNull(failed.backBuffer());
		failed.recycle();
		assertEquals("Cannot load the textures: java.lang.OutOfMemoryError",
				DecodedTextures.failed(new OutOfMemoryError()).error());
	}

	private static DiscoveredSampler sampler(String name, int target) {
		return new DiscoveredSampler(name, target, Sampling.IMAGE);
	}
}

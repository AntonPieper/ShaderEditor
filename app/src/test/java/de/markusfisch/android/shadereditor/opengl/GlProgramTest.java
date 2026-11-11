package de.markusfisch.android.shadereditor.opengl;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLES30;

import org.junit.Test;

import java.util.List;

public class GlProgramTest {
	@Test
	public void samplersGetUnitsInNameOrder() {
		var program = new GlProgram(1, List.of(
				sampler("noise", GLES20.GL_SAMPLER_2D, 4),
				new GlUniform("time", GLES20.GL_FLOAT, 1, 0),
				sampler("cameraBack", GLES11Ext.GL_SAMPLER_EXTERNAL_OES, 2),
				sampler("backbuffer", GLES20.GL_SAMPLER_2D, 7),
				sampler("sky", GLES20.GL_SAMPLER_CUBE, 1)));
		assertEquals(0, program.getTextureUnit("backbuffer"));
		assertEquals(1, program.getTextureUnit("cameraBack"));
		assertEquals(2, program.getTextureUnit("noise"));
		assertEquals(3, program.getTextureUnit("sky"));
		assertEquals(GlUniform.NO_UNIT, program.getTextureUnit("time"));
		assertEquals(List.of("backbuffer", "cameraBack", "noise", "sky"),
				program.getSamplers().stream().map(GlUniform::name).toList());
		assertEquals(7, program.getUniforms().get("backbuffer").location());
	}

	@Test
	public void moreSamplersThanUnitsFailTheBuild() {
		var program = new GlProgram(1, List.of(
				sampler("c", GLES20.GL_SAMPLER_2D, 0),
				sampler("a", GLES20.GL_SAMPLER_2D, 1),
				new GlUniform("b[0]", GLES20.GL_SAMPLER_2D, 2, 2)));
		assertEquals(0, program.getTextureUnit("a"));
		assertEquals(1, program.getTextureUnit("b"));
		assertEquals(3, program.getTextureUnit("c"));
		assertNull(program.checkTextureUnits(4));
		var error = program.checkTextureUnits(3);
		assertNotNull(error);
		assertEquals("The shader needs 4 texture units, but this device has only 3",
				error.message());
	}

	@Test
	public void samplerNothingProvidesStillTakesItsOwnUnit() {
		var program = new GlProgram(1, List.of(
				sampler("backbuffer", GLES20.GL_SAMPLER_2D, 0),
				sampler("aUnknownTexture", GLES20.GL_SAMPLER_2D, 1)));
		assertEquals(0, program.getTextureUnit("aUnknownTexture"));
		assertEquals(1, program.getTextureUnit("backbuffer"));
		assertEquals(GlUniform.NO_UNIT, program.getTextureUnit("cameraFront"));
	}

	@Test
	public void inactiveSamplerGetsNoUnit() {
		var program = new GlProgram(1, List.of(
				sampler("a", GLES20.GL_SAMPLER_2D, -1),
				sampler("b", GLES20.GL_SAMPLER_2D, 3)));
		assertEquals(GlUniform.NO_UNIT, program.getTextureUnit("a"));
		assertEquals(0, program.getTextureUnit("b"));
	}

	@Test
	public void samplerArrayIsFoundByItsBaseName() {
		var program = new GlProgram(1, List.of(
				new GlUniform("textures[0]", GLES20.GL_SAMPLER_2D, 2, 5)));
		assertEquals(0, program.getTextureUnit("textures"));
		assertEquals(5, program.getUniforms().get("textures").location());
	}

	@Test
	public void samplerArrayTakesAUnitPerElement() {
		var program = new GlProgram(1, List.of(
				new GlUniform("textures[0]", GLES20.GL_SAMPLER_2D, 2, 5),
				sampler("aCube", GLES20.GL_SAMPLER_CUBE, 3),
				sampler("z", GLES20.GL_SAMPLER_2D, 4)));
		assertEquals(0, program.getTextureUnit("aCube"));
		var textures = program.getUniforms().get("textures");
		assertArrayEquals(new int[]{1, 2}, textures.units());
		assertEquals(3, program.getTextureUnit("z"));
		assertNull(program.checkTextureUnits(4));
		assertNotNull(program.checkTextureUnits(3));
	}

	@Test
	public void emptyTextureTargetFollowsSamplerType() {
		assertEquals(GLES20.GL_TEXTURE_2D,
				sampler("a", GLES20.GL_SAMPLER_2D, 0).textureTarget());
		assertEquals(GLES20.GL_TEXTURE_2D,
				sampler("a", GLES30.GL_INT_SAMPLER_2D, 0).textureTarget());
		assertEquals(GLES20.GL_TEXTURE_CUBE_MAP,
				sampler("a", GLES20.GL_SAMPLER_CUBE, 0).textureTarget());
		assertEquals(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
				sampler("a", GLES11Ext.GL_SAMPLER_EXTERNAL_OES, 0).textureTarget());
		assertEquals(0, sampler("a", GLES30.GL_SAMPLER_3D, 0).textureTarget());
	}

	private static GlUniform sampler(String name, int type, int location) {
		return new GlUniform(name, type, 1, location);
	}
}

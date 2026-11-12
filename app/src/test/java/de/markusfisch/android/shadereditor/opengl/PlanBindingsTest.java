package de.markusfisch.android.shadereditor.opengl;

import static de.markusfisch.android.shadereditor.opengl.TestProjects.lines;
import static de.markusfisch.android.shadereditor.opengl.TestProjects.path;
import static de.markusfisch.android.shadereditor.opengl.TestProjects.plan;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.opengl.GLES11Ext;
import android.opengl.GLES20;

import org.junit.Test;

import java.util.List;

import de.markusfisch.android.shadereditor.project.HostDirective.Filter;
import de.markusfisch.android.shadereditor.project.HostDirective.Mipmap;
import de.markusfisch.android.shadereditor.project.HostDirective.Wrap;
import de.markusfisch.android.shadereditor.project.ProjectDiagnostic;
import de.markusfisch.android.shadereditor.project.ProjectDiagnostic.Severity;
import de.markusfisch.android.shadereditor.project.RenderPlan.Sampling;

public class PlanBindingsTest {
	private static final String MAIN = "void main() {}";

	@Test
	public void warnsAboutADeclaredInputTheShaderDoesNotUse() {
		var plan = plan("main.glsl", lines(
				MAIN,
				"#pragma se input filter=linear",
				"uniform samplerExternalOES cameraBack;"));
		var bindings = PlanBindings.of(plan.passes().get(0), List.of());
		assertNull(bindings.camera);
		assertEquals(List.of(new ProjectDiagnostic(Severity.WARNING,
						"cameraBack has an input but the shader does not use " +
								"it, so nothing is bound to it", path("main.glsl"), 2)),
				bindings.warnings);
	}

	@Test
	public void leavesEngineSamplersToTheEngine() {
		var plan = plan("main.glsl", lines(
				"#pragma se input filter=linear",
				"uniform samplerExternalOES cameraFront;",
				"uniform sampler2D backbuffer;",
				"uniform sampler2D nothingProvides;",
				MAIN));
		var program = new GlProgram(1, List.of(
				sampler("cameraFront", GLES11Ext.GL_SAMPLER_EXTERNAL_OES),
				sampler("backbuffer", GLES20.GL_SAMPLER_2D),
				sampler("nothingProvides", GLES20.GL_SAMPLER_2D)));
		var bindings = PlanBindings.of(plan.passes().get(0),
				program.getSamplers());
		assertEquals("cameraFront", bindings.camera.name());
		assertEquals(1, bindings.camera.unit());
		assertEquals(new Sampling(Filter.LINEAR, Filter.LINEAR, Mipmap.NONE,
				Wrap.CLAMP, Wrap.CLAMP), bindings.cameraSampling);
		assertTrue(bindings.warnings.isEmpty());
	}

	@Test
	public void anUnannotatedCameraKeepsItsDefaultSampling() {
		var bindings = PlanBindings.of(plan("main.glsl", MAIN).passes().get(0),
				List.of(sampler("cameraBack", GLES11Ext.GL_SAMPLER_EXTERNAL_OES)
						.withUnit(0)));
		assertEquals("cameraBack", bindings.camera.name());
		assertSame(Sampling.CAMERA, bindings.cameraSampling);
		var plain = PlanBindings.of(plan("main.glsl", MAIN).passes().get(0),
				List.of(sampler("cameraBack", GLES20.GL_SAMPLER_2D).withUnit(0)));
		assertNull(plain.camera);
	}

	private static GlUniform sampler(String name, int type) {
		return new GlUniform(name, type, 1, 0);
	}
}

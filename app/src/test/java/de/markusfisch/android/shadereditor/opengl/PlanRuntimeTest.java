package de.markusfisch.android.shadereditor.opengl;

import static de.markusfisch.android.shadereditor.opengl.TestProjects.lines;
import static de.markusfisch.android.shadereditor.opengl.TestProjects.path;
import static de.markusfisch.android.shadereditor.opengl.TestProjects.plan;
import static de.markusfisch.android.shadereditor.opengl.TestProjects.prepare;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import de.markusfisch.android.shadereditor.project.Preparation;
import de.markusfisch.android.shadereditor.project.ProjectDiagnostic;
import de.markusfisch.android.shadereditor.project.RenderPlan.Size;

public class PlanRuntimeTest {
	private static final String MAIN = "void main() {}";

	// The backbuffer is declared but never read, so linking removes it.
	@Test
	public void initialWithoutAReadBackbufferWarnsAtLink() {
		var preparation = prepare("main.frag", lines(
						"#pragma se target initial=\"seed.png\"",
						"uniform sampler2D backbuffer;",
						MAIN),
				"seed.png", "png");
		assertEquals(List.of(), preparation.diagnostics());
		var warnings = checkInitial(preparation, false);
		assertEquals(1, warnings.size());
		assertEquals(ProjectDiagnostic.Severity.WARNING,
				warnings.get(0).severity());
		assertEquals(1, warnings.get(0).line());
		assertEquals(List.of(), checkInitial(preparation, true));
	}

	@Test
	public void initialWarnsOnceWhenPreparationWarnedAlready() {
		var preparation = prepare("main.frag", lines(
						"#pragma se target initial=\"seed.png\"",
						MAIN),
				"seed.png", "png");
		assertEquals(1, preparation.diagnostics().size());
		assertEquals(preparation.diagnostics(), checkInitial(preparation,
				false));
	}

	// Frame n draws into one texture of the target and reads the other,
	// which holds frame n - 1; the frame drawn is the one presented.
	@Test
	public void aPassReadsTheFrameItDrewBeforeAndPresentsTheNewOne() {
		var engine = new FakeEngine(prepare("main.frag", lines(
				"uniform sampler2D backbuffer;",
				"uniform vec2 resolution;",
				"void main() {",
				"	gl_FragColor = texture2D(backbuffer,",
				"			gl_FragCoord.xy / resolution);",
				"}")), 3);
		assertEquals(ShaderSwap.Allocation.MADE, engine.resize(8, 4));
		engine.draw(0);
		engine.draw(.1f);
		engine.draw(.2f);
		var draws = engine.draws("main.frag");
		int unit = engine.unit("main.frag", "backbuffer");
		assertEquals(3, draws.size());
		for (int i = 1; i < draws.size(); ++i) {
			assertEquals(draws.get(i - 1).target(),
					(int) draws.get(i).units().get(unit));
			assertNotEquals(draws.get(i - 1).target(), draws.get(i).target());
		}
		var presented = engine.presented();
		assertEquals(3, presented.size());
		for (int i = 0; i < draws.size(); ++i) {
			assertTrue(presented.get(i).units().containsValue(
					draws.get(i).target()));
		}
	}

	// In frame n, now reads what blur.frag drew in frame n and before
	// what it drew in frame n - 1.
	@Test
	public void aPassReadsTheCurrentAndThePreviousFrameOfAnother() {
		var engine = new FakeEngine(prepare("main.frag", lines(
						"#pragma se input \"blur.frag\"",
						"uniform sampler2D now;",
						"#pragma se input \"blur.frag\" frame=previous",
						"uniform sampler2D before;",
						MAIN),
				"blur.frag", MAIN), 3);
		engine.resize(8, 4);
		for (int i = 0; i < 3; ++i) {
			engine.draw(i);
		}
		var blur = engine.draws("blur.frag");
		var main = engine.draws("main.frag");
		int now = engine.unit("main.frag", "now");
		int before = engine.unit("main.frag", "before");
		for (int i = 0; i < 3; ++i) {
			assertEquals(blur.get(i).target(),
					(int) main.get(i).units().get(now));
			if (i > 0) {
				assertEquals(blur.get(i - 1).target(),
						(int) main.get(i).units().get(before));
			}
		}
	}

	// ftime is (time % FTIME_PERIOD) / FTIME_PERIOD * 2 - 1, each pass
	// with its own period.
	@Test
	public void eachPassHasItsOwnFtimePeriod() {
		var engine = new FakeEngine(prepare("main.frag", lines(
						"#pragma se input \"blur.frag\"",
						"uniform sampler2D blur;",
						"uniform float ftime;",
						MAIN),
				"blur.frag", lines(
						"#define FTIME_PERIOD 2.0",
						"uniform float ftime;",
						MAIN)), 3);
		engine.resize(8, 4);
		engine.draw(1.5f);
		assertEquals(.5f, engine.draws("blur.frag").get(0)
				.uniforms().get("ftime")[0], 1e-6f);
		assertEquals(0f, engine.draws("main.frag").get(0)
				.uniforms().get("ftime")[0], 1e-6f);
	}

	// a.frag and b.frag both start as seed.frag: one draw of it on reset,
	// stretched into both histories.
	@Test
	public void aSharedInitialShaderIsDrawnOncePerReset() {
		var engine = new FakeEngine(prepare("main.frag", lines(
						"#pragma se input \"a.frag\" frame=previous",
						"uniform sampler2D a;",
						"#pragma se input \"b.frag\" frame=previous",
						"uniform sampler2D b;",
						MAIN),
				"a.frag", lines(
						"#pragma se target initial=\"seed.frag\"",
						MAIN),
				"b.frag", lines(
						"#pragma se target initial=\"seed.frag\"",
						MAIN),
				"seed.frag", MAIN), 3);
		engine.resize(8, 4);
		engine.draw(0);
		var seed = engine.draws("seed.frag");
		assertEquals(1, seed.size());
		var stretched = new ArrayList<Integer>();
		for (var draw : engine.stretched()) {
			if (draw.units().get(engine.stretchUnit(draw)) ==
					seed.get(0).target()) {
				stretched.add(draw.target());
			}
		}
		assertEquals(2, stretched.size());
		assertNotEquals(stretched.get(0), stretched.get(1));
	}

	// On a reset, seed.frag reads data.frag as it starts: its current
	// and its previous frame are one texture then, which OpenGL ES 2.0
	// samples with one setting.
	@Test
	public void gles2CannotReadOnePassOnResetWithTwoSettings() {
		var preparation = prepare("main.frag", lines(
						"#pragma se target initial=\"seed.frag\"",
						"uniform sampler2D backbuffer;",
						MAIN),
				"seed.frag", lines(
						"#pragma se input \"data.frag\" filter=nearest",
						"uniform sampler2D a;",
						"#pragma se input \"data.frag\" frame=previous " +
								"filter=linear",
						"uniform sampler2D b;",
						MAIN),
				"data.frag", lines(
						"#pragma se target size=2x2",
						MAIN));
		var gles2 = new FakeEngine(preparation, 2);
		assertTrue(gles2.runtime.hasFailed());
		assertEquals(List.of(new ProjectDiagnostic(
						ProjectDiagnostic.Severity.ERROR,
						"a (seed.frag:1) and b read data.frag with different " +
								"settings when a reset draws seed.frag, where " +
								"the current and previous frame of data.frag " +
								"are one texture, which needs OpenGL ES 3.0; " +
								"this device only has OpenGL ES 2.0",
						path("seed.frag"), 3)),
				errors(gles2.diagnostics));
		assertFalse(new FakeEngine(preparation, 3).runtime.hasFailed());
	}

	private static List<ProjectDiagnostic> errors(
			List<ProjectDiagnostic> diagnostics) {
		var errors = new ArrayList<ProjectDiagnostic>();
		for (var diagnostic : diagnostics) {
			if (diagnostic.severity() == ProjectDiagnostic.Severity.ERROR) {
				errors.add(diagnostic);
			}
		}
		return errors;
	}

	// Turning the phone swaps the render size; a fixed size stays, and
	// so do the histories when every size stays.
	@Test
	public void fixedTargetsKeepTheirSizeAtAnotherRenderSize() {
		var plan = plan("main.frag", lines(
						"#pragma se input \"sim.frag\"",
						"uniform sampler2D sim;",
						"#pragma se target scale=0.5",
						MAIN),
				"sim.frag", lines(
						"#pragma se input \"sim.frag\" frame=previous",
						"uniform sampler2D state;",
						"#pragma se target size=256x256",
						MAIN));
		var schedule = PassSchedule.of(plan, new boolean[2]);
		var errors = new ArrayList<ProjectDiagnostic>();
		var portrait = PlanRuntime.targetSizes(plan, schedule, 1080, 2400,
				4096, errors);
		var landscape = PlanRuntime.targetSizes(plan, schedule, 2400, 1080,
				4096, errors);
		int main = plan.output();
		int sim = 1 - main;
		assertEquals(new Size(256, 256), portrait[sim]);
		assertEquals(portrait[sim], landscape[sim]);
		assertEquals(new Size(540, 1200), portrait[main]);
		assertEquals(new Size(1200, 540), landscape[main]);
		var fixed = plan("main.frag", lines(
				"#pragma se target size=512x512",
				"uniform sampler2D backbuffer;",
				MAIN));
		var fixedSchedule = PassSchedule.of(fixed, new boolean[]{true});
		assertArrayEquals(
				PlanRuntime.targetSizes(fixed, fixedSchedule, 1080, 2400, 4096,
						errors),
				PlanRuntime.targetSizes(fixed, fixedSchedule, 2400, 1080, 4096,
						errors));
		assertEquals(List.of(), errors);
	}

	@Test
	public void aTargetLargerThanTheDeviceAllowsIsAnErrorOnItsLine() {
		var plan = plan("main.frag", lines(
				"#pragma se target size=8192x16",
				MAIN));
		var errors = new ArrayList<ProjectDiagnostic>();
		assertNull(PlanRuntime.targetSizes(plan,
				PassSchedule.of(plan, new boolean[1]), 100, 100, 4096, errors));
		assertEquals(1, errors.size());
		assertEquals(1, errors.get(0).line());
	}

	// Only targets are allocated, so only they are bounded by the
	// largest texture; the render size is not.
	@Test
	public void targetsThatFitAreMadeAtARenderSizeThatDoesNot() {
		var errors = new ArrayList<ProjectDiagnostic>();
		var fixed = plan("main.frag", lines(
				"#pragma se target size=16x16",
				MAIN));
		assertArrayEquals(new Size[]{new Size(16, 16)},
				PlanRuntime.targetSizes(fixed, PassSchedule.of(fixed,
						new boolean[1]), 4097, 100, 4096, errors));
		var half = plan("main.frag", lines(
				"#pragma se target scale=0.5",
				MAIN));
		assertArrayEquals(new Size[]{new Size(2048, 50)},
				PlanRuntime.targetSizes(half, PassSchedule.of(half,
						new boolean[1]), 4097, 100, 4096, errors));
		assertEquals(List.of(), errors);
		var full = plan("main.frag", lines(
				"#pragma se target scale=1",
				MAIN));
		assertNull(PlanRuntime.targetSizes(full, PassSchedule.of(full,
				new boolean[1]), 4097, 100, 4096, errors));
		assertEquals(1, errors.size());
		assertEquals(path("main.frag"), errors.get(0).file());
		assertEquals(1, errors.get(0).line());
	}

	// A resize that changes no target keeps the targets and what they
	// hold: the frame after it reads the frame before it.
	@Test
	public void aResizeThatChangesNoTargetKeepsTheHistory() {
		var engine = new FakeEngine(prepare("main.frag", lines(
				"#pragma se target size=64x64",
				"uniform sampler2D backbuffer;",
				MAIN)), 3);
		engine.device.maxTextureSize = 4096;
		assertEquals(ShaderSwap.Allocation.MADE, engine.resize(100, 200));
		engine.draw(0);
		engine.draw(1);
		int allocations = count(engine.device.calls, "allocateTexture2D");
		assertEquals(ShaderSwap.Allocation.KEPT, engine.resize(5000, 100));
		assertEquals(allocations, count(engine.device.calls,
				"allocateTexture2D"));
		engine.draw(2);
		var draws = engine.draws("main.frag");
		assertEquals(draws.get(1).target(), (int) draws.get(2).units()
				.get(engine.unit("main.frag", "backbuffer")));
		assertTrue(engine.stretched().isEmpty());
	}

	private static int count(List<String> calls, String call) {
		int count = 0;
		for (var c : calls) {
			if (c.equals(call)) {
				++count;
			}
		}
		return count;
	}

	// Any step of making the targets of a candidate can fail, at its
	// first target or a later one: the candidate fails as a whole,
	// everything it made is deleted, and the targets of the running plan
	// are neither deleted nor written.
	@Test
	public void aFailureAnywhereInMakingTargetsLeavesNothingBehind() {
		var running = prepare("main.frag", lines(
				"uniform sampler2D backbuffer;",
				MAIN));
		var candidate = prepare("main.frag", lines(
						"#pragma se input \"a.frag\" frame=previous",
						"uniform sampler2D a;",
						"#pragma se input \"b.frag\"",
						"uniform sampler2D b;",
						MAIN),
				"a.frag", MAIN,
				"b.frag", lines(
						"#pragma se input \"b.frag\" frame=previous",
						"uniform sampler2D self;",
						MAIN));
		var formats = TargetFormats.of(3, "GL_EXT_color_buffer_float");
		for (var step : List.of("createTexture2D", "allocateTexture2D",
				"createFramebuffer", "attachColor", "checkFramebufferStatus")) {
			for (int occurrence : new int[]{1, 4}) {
				var device = new FakeGlDevice();
				var old = new FakeEngine(device, running, formats);
				old.resize(8, 4);
				old.draw(0);
				var next = new FakeEngine(device, candidate, formats);
				var live = new java.util.HashSet<>(device.live);
				int touched = device.touched.size();
				device.fail(step, occurrence);
				assertEquals(step + " " + occurrence,
						ShaderSwap.Allocation.FAILED, next.resize(8, 4));
				assertEquals(step + " " + occurrence, live, device.live);
				for (var texture : device.touched.subList(touched,
						device.touched.size())) {
					assertFalse(step + " " + occurrence,
							live.contains(texture));
				}
				old.draw(1);
				var draws = old.draws("main.frag");
				assertEquals(draws.get(0).target(), (int) draws.get(1)
						.units().get(old.unit("main.frag", "backbuffer")));
			}
		}
	}

	// A driver may fail a link without naming a line; the error still
	// names the pass file whose program it was.
	@Test
	public void aProgramErrorWithoutALineNamesItsPassFile() {
		var device = new FakeGlDevice();
		// The presentation shader links first, then blur.frag.
		device.fail("createProgram", 2);
		var engine = new FakeEngine(device, prepare("main.frag", lines(
						"#pragma se input \"blur.frag\"",
						"uniform sampler2D blur;",
						MAIN),
				"blur.frag", MAIN),
				TargetFormats.of(3, "GL_EXT_color_buffer_float"));
		assertNull(engine.runtime);
		assertEquals(List.of(new ProjectDiagnostic(
						ProjectDiagnostic.Severity.ERROR, "link failed",
						path("blur.frag"), 0)),
				errors(engine.diagnostics));
	}

	// The presentation shader both presents and stretches initial content
	// over a history; only presenting is limited. A seed of 1000 in an
	// rgba16f target survives the stretch, and the presented picture under
	// a ceiling is still no brighter than it. The shader computes what
	// PictureLimit.apply computes with the limit it is given.
	@Test
	public void onlyThePresentedPictureIsLimited() {
		var engine = new FakeEngine(prepare("main.frag", lines(
						"#pragma se input \"main.frag\" frame=previous",
						"uniform sampler2D self;",
						"#pragma se target format=rgba16f initial=\"seed.frag\"",
						MAIN),
				"seed.frag", lines(
						"#pragma se target format=rgba16f",
						MAIN)), 3);
		float ceiling = PictureLimit.BALANCED / 255f;
		engine.pipeline.setLimit(PictureLimit.strength(ceiling));
		engine.resize(8, 4);
		engine.draw(0);
		var stretched = engine.stretched();
		assertEquals(1, stretched.size());
		float stretch = stretched.get(0).uniforms().get("limit")[0];
		assertEquals(1000f, PictureLimit.apply(1000f, 1000f, stretch), 0f);
		var presented = engine.presented();
		assertEquals(1, presented.size());
		float present = presented.get(0).uniforms().get("limit")[0];
		assertEquals(ceiling, PictureLimit.apply(4f, 4f, present), 1e-6f);
	}

	private static List<ProjectDiagnostic> checkInitial(
			Preparation preparation,
			boolean history) {
		var plan = preparation.plan();
		var warnings = new ArrayList<>(preparation.diagnostics());
		PlanRuntime.checkInitial(plan,
				PassSchedule.of(plan, new boolean[]{history}), warnings);
		return warnings;
	}
}

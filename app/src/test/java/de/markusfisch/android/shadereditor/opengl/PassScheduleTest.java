package de.markusfisch.android.shadereditor.opengl;

import static de.markusfisch.android.shadereditor.opengl.TestProjects.lines;
import static de.markusfisch.android.shadereditor.opengl.TestProjects.path;
import static de.markusfisch.android.shadereditor.opengl.TestProjects.plan;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

import de.markusfisch.android.shadereditor.project.HostDirective.Frame;
import de.markusfisch.android.shadereditor.project.HostDirective.TextureKind;
import de.markusfisch.android.shadereditor.project.RenderPlan;
import de.markusfisch.android.shadereditor.project.RenderPlan.Sampling;
import de.markusfisch.android.shadereditor.project.RenderPlan.Source;

public class PassScheduleTest {
	private static final String MAIN = "void main() {}";
	private static final String PNG = "\u0089PNG";

	@Test
	public void aPassIsDrawnAfterThePassesWhoseCurrentFrameItReads() {
		var plan = plan("main.frag", lines(
						"#pragma se input \"b.frag\"",
						"uniform sampler2D b;",
						MAIN),
				"b.frag", lines(
						"#pragma se input \"a.frag\"",
						"uniform sampler2D a;",
						MAIN),
				"a.frag", MAIN);
		var schedule = schedule(plan);
		assertArrayEquals(passes(plan, "a.frag", "b.frag", "main.frag"),
				schedule.frame);
		for (int pass = 0; pass < 3; ++pass) {
			assertTrue(schedule.hasTarget(pass));
			assertFalse(schedule.keepsHistory(pass));
		}
	}

	// Ping-pong: each reads the other's previous frame.
	@Test
	public void readingAPreviousFrameOrdersNothingAndKeepsThatHistory() {
		var plan = plan("main.frag", lines(
						"#pragma se input \"b.frag\"",
						"uniform sampler2D b;",
						MAIN),
				"a.frag", lines(
						"#pragma se input \"b.frag\" frame=previous",
						"uniform sampler2D b;",
						MAIN),
				"b.frag", lines(
						"#pragma se input \"a.frag\" frame=previous",
						"uniform sampler2D a;",
						MAIN));
		var schedule = schedule(plan);
		assertArrayEquals(passes(plan, "a.frag", "b.frag", "main.frag"),
				schedule.frame);
		assertTrue(schedule.keepsHistory(index(plan, "a.frag")));
		assertTrue(schedule.keepsHistory(index(plan, "b.frag")));
		assertFalse(schedule.keepsHistory(index(plan, "main.frag")));
	}

	@Test
	public void aBackbufferOrAnInputOfItsOwnPreviousFrameKeepsTheHistory() {
		var plain = plan("main.frag", lines(
				"uniform sampler2D backbuffer;",
				MAIN));
		assertFalse(PassSchedule.of(plain, new boolean[]{false})
				.keepsHistory(0));
		assertTrue(PassSchedule.of(plain, new boolean[]{true})
				.keepsHistory(0));
		assertTrue(schedule(plan("main.frag", lines(
				"#pragma se input \"main.frag\" frame=previous",
				"uniform sampler2D self;",
				MAIN))).keepsHistory(0));
	}

	@Test
	public void anInitialShaderIsDrawnOnResetOnly() {
		var plan = plan("main.frag", lines(
						"#pragma se input \"main.frag\" frame=previous",
						"uniform sampler2D self;",
						"#pragma se target initial=\"seed.frag\"",
						MAIN),
				"seed.frag", MAIN);
		var schedule = schedule(plan);
		int main = index(plan, "main.frag");
		int seed = index(plan, "seed.frag");
		assertArrayEquals(new int[]{main}, schedule.frame);
		assertArrayEquals(new int[]{main}, schedule.reset);
		assertTrue(schedule.isInitialized(main));
		assertTrue(schedule.hasTarget(seed));
		assertFalse(schedule.keepsHistory(seed));
	}

	@Test
	public void initialContentThatNothingReadsIsNotDrawn() {
		var plan = plan("main.frag", lines(
						"#pragma se target initial=\"seed.frag\"",
						MAIN),
				"seed.frag", MAIN);
		var schedule = schedule(plan);
		assertArrayEquals(new int[0], schedule.reset);
		assertFalse(schedule.isInitialized(index(plan, "main.frag")));
		assertFalse(schedule.hasTarget(index(plan, "seed.frag")));
	}

	// state.frag is read only by the initial shader, on reset, and must
	// show what it starts as then.
	@Test
	public void whatAnInitialShaderReadsOnResetIsInitializedFirst() {
		var plan = plan("main.frag", lines(
						"#pragma se input \"main.frag\" frame=previous",
						"uniform sampler2D self;",
						"#pragma se target initial=\"seed.frag\"",
						MAIN),
				"seed.frag", lines(
						"#pragma se input \"state.frag\"",
						"uniform sampler2D state;",
						MAIN),
				"state.frag", lines(
						"#pragma se target initial=\"s.png\"",
						MAIN),
				"s.png", PNG);
		var schedule = schedule(plan);
		int state = index(plan, "state.frag");
		assertArrayEquals(passes(plan, "main.frag"), schedule.frame);
		assertArrayEquals(passes(plan, "state.frag", "main.frag"),
				schedule.reset);
		assertTrue(schedule.isInitialized(state));
		assertTrue(schedule.hasTarget(state));
		assertFalse(schedule.keepsHistory(state));
	}

	// s.frag draws for main.frag on reset while t.frag, drawn for b.frag,
	// reads what s.frag starts as.
	@Test
	public void aPassDrawnOnResetKeepsWhatItStartsAsWhileThatIsRead() {
		var plan = plan("main.frag", lines(
						"#pragma se input \"b.frag\" frame=previous",
						"uniform sampler2D b;",
						"#pragma se input \"main.frag\" frame=previous",
						"uniform sampler2D self;",
						"#pragma se target initial=\"s.frag\"",
						MAIN),
				"b.frag", lines(
						"#pragma se target initial=\"t.frag\"",
						MAIN),
				"s.frag", MAIN,
				"t.frag", lines(
						"#pragma se input \"s.frag\"",
						"uniform sampler2D s;",
						MAIN));
		var schedule = schedule(plan);
		assertTrue(schedule.keepsHistory(index(plan, "s.frag")));
		assertFalse(schedule.keepsHistory(index(plan, "t.frag")));
		assertTrue(schedule.isInitialized(index(plan, "b.frag")));
	}

	// An initial shader is one pass: drawn once on a reset, whatever
	// number of passes start as it.
	@Test
	public void aSharedInitialShaderIsDrawnOncePerReset() {
		var plan = plan("main.frag", lines(
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
				"seed.frag", MAIN);
		var schedule = schedule(plan);
		assertArrayEquals(passes(plan, "a.frag", "b.frag"), schedule.reset);
		assertTrue(schedule.drawsInitial(0));
		assertFalse(schedule.drawsInitial(1));
	}

	@Test
	public void aPlanWithACurrentFrameCycleIsAMistake() {
		var plan = plan("main.frag", MAIN);
		var pass = plan.passes().get(0);
		var self = new RenderPlan.Input("self", TextureKind.FLAT,
				Source.pass(0), Frame.CURRENT, Sampling.PASS, path("main.frag"),
				1);
		var cyclic = new RenderPlan(plan.programs(), List.of(
				new RenderPlan.Pass(0, List.of(self), pass.target(), null,
						false, true)), 0, List.of());
		assertThrows(IllegalArgumentException.class,
				() -> PassSchedule.of(cyclic, new boolean[1]));
	}

	private static PassSchedule schedule(RenderPlan plan) {
		return PassSchedule.of(plan, new boolean[plan.passes().size()]);
	}

	private static int[] passes(RenderPlan plan, String... files) {
		var passes = new int[files.length];
		for (int i = 0; i < files.length; ++i) {
			passes[i] = index(plan, files[i]);
		}
		return passes;
	}

	// Pass and program have the same index.
	private static int index(RenderPlan plan, String file) {
		for (int i = 0; i < plan.programs().size(); ++i) {
			if (plan.programs().get(i).file().equals(path(file))) {
				return i;
			}
		}
		throw new AssertionError(file);
	}
}

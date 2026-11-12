package de.markusfisch.android.shadereditor.opengl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLES30;

import androidx.annotation.NonNull;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import de.markusfisch.android.shadereditor.project.ProjectDiagnostic;
import de.markusfisch.android.shadereditor.project.ProjectPath;
import de.markusfisch.android.shadereditor.project.SourceLocation;
import de.markusfisch.android.shadereditor.project.SourceMap;

public class UniformWarningsTest {
	private static final String ADD_TEXTURE = "add a texture named %s";
	private static final List<UniformDef> TABLE = UniformTable.BUILTINS;

	@Test
	public void builtinsOfFittingTypeAndProvidedNamesAreNotReported() {
		assertEquals(List.of(), names(find(Set.of("noise"),
				uniform("time", GLES20.GL_FLOAT),
				uniform("powerConnected", GLES20.GL_BOOL),
				uniform("pointers[0]", GLES20.GL_FLOAT_VEC3, 4),
				uniform("backbuffer", GLES20.GL_SAMPLER_2D),
				uniform("cameraBack", GLES11Ext.GL_SAMPLER_EXTERNAL_OES),
				uniform("noise", GLES20.GL_SAMPLER_2D))));
	}

	@Test
	public void activeUniformsNothingProvidesAreReportedOncePerDeclaration() {
		var findings = find(Set.of("noise"),
				uniform("tmie", GLES20.GL_FLOAT),
				uniform("photo", GLES20.GL_SAMPLER_2D),
				uniform("weights[0]", GLES20.GL_FLOAT, 3),
				uniform("light0.color", GLES20.GL_FLOAT_VEC3),
				uniform("light0.position", GLES20.GL_FLOAT_VEC3),
				uniform("noise", GLES20.GL_SAMPLER_2D));
		assertEquals(List.of("light0", "photo", "tmie", "weights"),
				names(findings));
		for (var finding : findings) {
			assertNull(finding.builtin());
		}
	}

	@Test
	public void uniformsWithoutLocationAndOfGlslItselfAreNotReported() {
		assertEquals(List.of(), names(find(Set.of(),
				new GlUniform("Block.member", GLES20.GL_FLOAT, 1, -1),
				uniform("gl_DepthRange.near", GLES20.GL_FLOAT))));
	}

	@Test
	public void providedNameWinsOverBuiltinOfOtherType() {
		assertEquals(List.of(), names(find(Set.of("frame"),
				uniform("frame", GLES20.GL_SAMPLER_2D))));
	}

	@Test
	public void wrongTypeIsFoundForScalarsVectorsMatricesArraysAndSamplers() {
		var findings = find(Set.of(),
				uniform("time", GLES20.GL_FLOAT_VEC3),
				uniform("frame", GLES20.GL_FLOAT),
				uniform("resolution", GLES20.GL_FLOAT_VEC3),
				uniform("rotationMatrix", GLES20.GL_FLOAT_MAT4),
				uniform("second[0]", GLES20.GL_INT, 2),
				uniform("pointers[0]", GLES20.GL_FLOAT_VEC2, 10),
				uniform("backbuffer", GLES20.GL_SAMPLER_CUBE),
				uniform("cameraFront", GLES20.GL_SAMPLER_2D),
				uniform("mouse.x", GLES20.GL_FLOAT));
		assertEquals(List.of("backbuffer", "cameraFront", "frame", "mouse",
				"pointers", "resolution", "rotationMatrix", "second", "time"),
				names(findings));
		for (var finding : findings) {
			assertEquals(finding.name(), finding.builtin().getName());
		}
	}

	@Test
	public void wrongTypeNamesTheExpectedType() {
		assertEquals("time is not set because its type does not fit; " +
						"the builtin is float time",
				message(uniform("time", GLES20.GL_FLOAT_VEC3)));
		assertEquals("pointers is not set because its type does not fit; " +
						"the builtin is vec3 pointers[10]",
				message(uniform("pointers[0]", GLES20.GL_FLOAT, 10)));
	}

	@Test
	public void typosSuggestTheNearestBuiltinOfTheSameType() {
		assertEquals("time", nearest("tmie", GLES20.GL_FLOAT));
		assertEquals("time", nearest("tme", GLES20.GL_FLOAT));
		assertEquals("time", nearest("Time", GLES20.GL_FLOAT));
		assertEquals("time", nearest("iTime", GLES20.GL_FLOAT));
		assertEquals("resolution", nearest("resolutoin", GLES20.GL_FLOAT_VEC2));
		assertEquals("resolution", nearest("iResolution", GLES20.GL_FLOAT_VEC2));
		assertEquals("touchStart", nearest("touchstart", GLES20.GL_FLOAT_VEC2));
		assertEquals("ftime", nearest("ftim", GLES20.GL_FLOAT));
		assertEquals("backbuffer", nearest("backbufer", GLES20.GL_SAMPLER_2D));
		assertEquals("pointerCount", nearest("pointercount", GLES20.GL_BOOL));
	}

	@Test
	public void unrelatedNamesSuggestNothing() {
		assertNull(nearest("speed", GLES20.GL_FLOAT));
		assertNull(nearest("color", GLES20.GL_FLOAT_VEC3));
		assertNull(nearest("tm", GLES20.GL_FLOAT));
		assertNull(nearest("photo", GLES20.GL_SAMPLER_2D));
		assertNull(nearest("resolutionsXY", GLES20.GL_FLOAT_VEC2));
	}

	@Test
	public void suggestionIsNeverOfAnotherKind() {
		assertNull(nearest("tmie", GLES20.GL_SAMPLER_2D));
		assertNull(nearest("tmie", GLES20.GL_FLOAT_VEC3));
		assertNull(nearest("backbufer", GLES20.GL_FLOAT));
		assertNull(nearest("resolutoin", GLES20.GL_FLOAT));
		assertNull(nearest("cameraFrnt", GLES20.GL_SAMPLER_2D));
	}

	@Test
	public void distanceCountsEditsAndSwaps() {
		assertEquals(0, UniformWarnings.distance("time", "time"));
		assertEquals(1, UniformWarnings.distance("Time", "time"));
		assertEquals(1, UniformWarnings.distance("tmie", "time"));
		assertEquals(1, UniformWarnings.distance("tme", "time"));
		assertEquals(1, UniformWarnings.distance("timee", "time"));
		assertEquals(1, UniformWarnings.distance("tide", "time"));
		assertEquals(4, UniformWarnings.distance("", "time"));
	}

	@Test
	public void unprovidedMessagesCarryTheirFix() {
		assertEquals("Nothing provides tmie, so it reads zero; " +
						"did you mean time?",
				message(uniform("tmie", GLES20.GL_FLOAT)));
		assertEquals("Nothing provides speed, so it reads zero",
				message(uniform("speed", GLES20.GL_FLOAT)));
		assertEquals("Nothing provides photo, so it reads black; " +
						"add a texture named photo",
				message(uniform("photo", GLES20.GL_SAMPLER_2D)));
		assertEquals("Nothing provides sky, so it reads black; " +
						"add a texture named sky",
				message(uniform("sky", GLES20.GL_SAMPLER_CUBE)));
		assertEquals("Nothing provides volume, so it reads black",
				message(uniform("volume", GLES30.GL_SAMPLER_3D)));
		assertEquals("Nothing provides backbufer, so it reads black; " +
						"did you mean backbuffer?",
				message(uniform("backbufer", GLES20.GL_SAMPLER_2D)));
	}

	@Test
	public void declarationIsFoundOutsideOfCommentsAndAroundConditions() {
		String text = "precision mediump float;\n" +
				"// uniform float tmie;\n" +
				"/* uniform float tmie;\n" +
				"   uniform float tmie; */\n" +
				"#if defined(GL_ES)\n" +
				"uniform float tmiex;\n" +
				"uniform lowp float a, /* b */ tmie;\n" +
				"#else\n" +
				"uniform float tmie;\n" +
				"#endif\n" +
				"void main() { gl_FragColor = vec4(tmie); }\n";
		assertEquals(7, UniformWarnings.declarationLine(text, "tmie"));
		assertEquals(6, UniformWarnings.declarationLine(text, "tmiex"));
	}

	@Test
	public void declarationOverLinesIsFoundAtItsName() {
		assertEquals(3, UniformWarnings.declarationLine(
				"uniform\nsampler2D\nphoto;\n", "photo"));
		assertEquals(0, UniformWarnings.declarationLine(
				"/* unterminated\nuniform float tmie;", "tmie"));
	}

	@Test
	public void missingDeclarationIsLineZero() {
		assertEquals(0, UniformWarnings.declarationLine(
				"void main() { float tmie = 1.; }\n", "tmie"));
		assertEquals(0, UniformWarnings.declarationLine(
				"uniform Light { float tmie; } light;\n", "tmie"));
	}

	@Test
	public void warningsWithoutASourceMapKeepTheirLine() {
		var warnings = UniformWarnings.check(
				List.of(uniform("tmie", GLES20.GL_FLOAT)), Set.of(),
				ADD_TEXTURE,
				"#define SHADER_EDITOR 1\nprecision mediump float;\n" +
						"uniform float tmie;\n",
				null);
		assertEquals(1, warnings.size());
		assertEquals(ProjectDiagnostic.Severity.WARNING, warnings.get(0).severity());
		assertNull(warnings.get(0).file());
		assertEquals(3, warnings.get(0).line());
	}

	@Test
	public void warningsAreLocatedInTheirProjectFile() {
		var main = ProjectPath.parse("main.glsl").value();
		var lib = ProjectPath.parse("lib/inputs.glsl").value();
		var include = new SourceLocation(main, 2, null);
		var sourceMap = new SourceMap(List.of(
				new SourceLocation(main, 1, null),
				SourceLocation.GENERATED,
				new SourceLocation(lib, 1, include),
				new SourceLocation(lib, 2, include),
				new SourceLocation(main, 3, null)));
		var warnings = UniformWarnings.check(
				List.of(uniform("photo", GLES20.GL_SAMPLER_2D),
						uniform("tmie", GLES20.GL_FLOAT)),
				Set.of(), UniformWarnings.ADD_INPUT,
				"#version 300 es\n#define SHADER_EDITOR 1\n" +
						"// inputs\nuniform sampler2D photo;\nvoid main() {}\n",
				sourceMap);
		assertEquals(2, warnings.size());
		assertEquals("lib/inputs.glsl:2: Nothing provides photo, so it reads " +
						"black; attach an image to it with #pragma se input \"path\"",
				warnings.get(0).toString());
		assertEquals("Nothing provides tmie, so it reads zero; did you mean time?",
				warnings.get(1).toString());
	}

	@Test
	public void warningsNeverMakeErrors() {
		var warnings = UniformWarnings.check(
				List.of(uniform("tmie", GLES20.GL_FLOAT),
						uniform("time", GLES20.GL_FLOAT_VEC3),
						uniform("photo", GLES20.GL_SAMPLER_2D)),
				Set.of(), ADD_TEXTURE,
				"uniform float tmie;\nuniform vec3 time;\n" +
						"uniform sampler2D photo;\n",
				null);
		assertEquals(3, warnings.size());
		assertFalse(ProjectDiagnostic.anyError(warnings));
		var mixed = new ArrayList<>(warnings);
		mixed.add(ProjectDiagnostic.error("syntax error"));
		assertTrue(ProjectDiagnostic.anyError(mixed));
		assertFalse(ProjectDiagnostic.anyError(List.of()));
	}

	@NonNull
	private static GlUniform uniform(@NonNull String name, int type) {
		return uniform(name, type, 1);
	}

	@NonNull
	private static GlUniform uniform(@NonNull String name, int type, int size) {
		return new GlUniform(name, type, size, 1);
	}

	@NonNull
	private static List<UniformWarnings.Finding> find(
			@NonNull Set<String> provided,
			@NonNull GlUniform... reflected) {
		return UniformWarnings.find(List.of(reflected), TABLE, provided);
	}

	@NonNull
	private static List<String> names(@NonNull List<UniformWarnings.Finding> findings) {
		var names = new ArrayList<String>();
		for (var finding : findings) {
			names.add(finding.name());
		}
		return names;
	}

	private static String nearest(@NonNull String name, int type) {
		var builtin = UniformWarnings.nearestBuiltin(name, uniform(name, type), TABLE);
		return builtin != null ? builtin.getName() : null;
	}

	@NonNull
	private static String message(@NonNull GlUniform uniform) {
		var findings = UniformWarnings.find(List.of(uniform), TABLE, Set.of());
		assertEquals(1, findings.size());
		return UniformWarnings.message(findings.get(0), TABLE,
				ADD_TEXTURE);
	}
}

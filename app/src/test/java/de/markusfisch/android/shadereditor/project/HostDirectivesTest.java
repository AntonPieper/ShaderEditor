package de.markusfisch.android.shadereditor.project;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

import de.markusfisch.android.shadereditor.project.HostDirective.Filter;
import de.markusfisch.android.shadereditor.project.HostDirective.Format;
import de.markusfisch.android.shadereditor.project.HostDirective.Frame;
import de.markusfisch.android.shadereditor.project.HostDirective.Input;
import de.markusfisch.android.shadereditor.project.HostDirective.Mipmap;
import de.markusfisch.android.shadereditor.project.HostDirective.Target;
import de.markusfisch.android.shadereditor.project.HostDirective.Wrap;

public class HostDirectivesTest {
	private static final String TEX = "uniform sampler2D tex;";

	@Test
	public void attachesToMultilineDeclarationWithPrecisionAndComments() {
		HostDirectives result = parse(
				"precision mediump float;",
				"  #  pragma se input \"assets/noise.png\" // noise",
				"/* a comment",
				"   spanning lines */",
				"",
				"uniform // why",
				"\thighp sampler2D",
				"  noise /* name */ ;",
				"uniform sampler2D other;");
		assertTrue(result.diagnostics().isEmpty());
		Input input = single(result.inputs());
		assertEquals("noise", input.uniform());
		assertEquals("sampler2D", input.samplerType());
		assertEquals(path("shaders/assets/noise.png"), input.source());
		assertEquals(path("shaders/main.glsl"), input.file());
		assertEquals(2, input.line());
		assertNull(input.frame());
		assertNull(input.min());
		assertNull(input.mag());
		assertNull(input.mipmap());
		assertNull(input.wrapS());
		assertNull(input.wrapT());
		assertNull(result.target());
	}

	@Test
	public void blanksDirectiveLinesAndKeepsSourceMap() {
		HostDirectives result = parse(
				"#version 300 es",
				"#pragma se input \"a.png\"",
				TEX,
				"#pragma se target scale=0.5",
				"#pragma sequence",
				"#pragma optimize(off)");
		assertTrue(result.diagnostics().isEmpty());
		assertEquals("#version 300 es\n\n" + TEX + "\n\n" +
				"#pragma sequence\n#pragma optimize(off)\n", result.text());
		assertEquals(6, result.lines().size());
		assertEquals(4, result.sourceMap().locate(4).line());
	}

	@Test
	public void parsesFrameValues() {
		assertEquals(Frame.CURRENT, input("\"pass.glsl\" frame=current").frame());
		assertEquals(Frame.PREVIOUS,
				input("\"pass.glsl\" frame=previous").frame());
	}

	@Test
	public void parsesFilterValues() {
		Input nearest = input("\"a.png\" filter=nearest");
		assertEquals(Filter.NEAREST, nearest.min());
		assertEquals(Filter.NEAREST, nearest.mag());
		assertEquals(Filter.LINEAR, input("\"a.png\" filter=linear").mag());
	}

	@Test
	public void parsesMinAndMagSeparately() {
		Input input = input("\"a.png\" min=linear mag=nearest");
		assertEquals(Filter.LINEAR, input.min());
		assertEquals(Filter.NEAREST, input.mag());
		Input onlyMag = input("\"a.png\" mag=linear");
		assertNull(onlyMag.min());
		assertEquals(Filter.LINEAR, onlyMag.mag());
		assertEquals(Filter.NEAREST, input("\"a.png\" min=nearest").min());
	}

	@Test
	public void parsesMipmapValues() {
		assertEquals(Mipmap.NONE, input("\"a.png\" mipmap=none").mipmap());
		assertEquals(Mipmap.NEAREST,
				input("\"a.jpg\" mipmap=nearest").mipmap());
		assertEquals(Mipmap.LINEAR,
				input("\"a.JPEG\" mipmap=linear").mipmap());
	}

	@Test
	public void parsesWrapWithOneOrTwoValues() {
		Input one = input("\"a.png\" wrap=repeat");
		assertEquals(Wrap.REPEAT, one.wrapS());
		assertEquals(Wrap.REPEAT, one.wrapT());
		Input two = input("\"a.png\" wrap=clamp,mirror");
		assertEquals(Wrap.CLAMP, two.wrapS());
		assertEquals(Wrap.MIRROR, two.wrapT());
	}

	@Test
	public void acceptsEngineSamplerWithOptionsOnly() {
		HostDirectives result = parse(
				"#pragma se input filter=linear",
				"uniform sampler2D backbuffer;",
				"#pragma se input wrap=repeat",
				"uniform samplerExternalOES cameraBack;");
		assertTrue(result.diagnostics().isEmpty());
		assertNull(result.inputs().get(0).source());
		assertEquals("cameraBack", result.inputs().get(1).uniform());
	}

	@Test
	public void acceptsCubemapImage() {
		HostDirectives result = parse(
				"#pragma se input \"sky.png\" mipmap=linear",
				"uniform samplerCube sky;");
		assertTrue(result.diagnostics().isEmpty());
		assertEquals("samplerCube", single(result.inputs()).samplerType());
	}

	@Test
	public void acceptsPathWithSpacesAndParentSegments() {
		assertEquals(path("my assets/a b.png"),
				input("\"../my assets/a b.png\"").source());
	}

	@Test
	public void parsesEveryTargetProperty() {
		Target scaled = target("scale=0.5 format=rgba16f " +
				"initial=\"start.png\" present=linear");
		assertEquals(0.5, scaled.scale(), 0);
		assertNull(scaled.width());
		assertEquals(Format.RGBA16F, scaled.format());
		assertEquals(path("shaders/start.png"), scaled.initial());
		assertEquals(Filter.LINEAR, scaled.present());
		Target sized = target("size=320x200 format=rgba8 present=nearest");
		assertNull(sized.scale());
		assertEquals(320, (int) sized.width());
		assertEquals(200, (int) sized.height());
		assertEquals(Format.RGBA8, sized.format());
		assertEquals(Filter.NEAREST, sized.present());
		assertNull(sized.initial());
		assertEquals(1.0, target("scale=1").scale(), 0);
		assertEquals(0.25, target("scale=.25").scale(), 0);
		assertEquals(path("shaders/init.glsl"),
				target("initial=\"init.glsl\"").initial());
	}

	@Test
	public void ignoresDirectivesInsideComments() {
		HostDirectives result = parse(
				"// #pragma se input \"a.png\"",
				"/*",
				"#pragma se target scale=2",
				"*/",
				TEX);
		assertTrue(result.diagnostics().isEmpty());
		assertTrue(result.inputs().isEmpty());
		assertNull(result.target());
		assertEquals("#pragma se target scale=2", result.lines().get(2));
	}

	@Test
	public void passesUnrelatedPragmasThrough() {
		HostDirectives result = parse(
				"#pragma sequence",
				"#pragma seinput",
				"#pragma STDGL invariant(all)");
		assertTrue(result.diagnostics().isEmpty());
		assertEquals("#pragma seinput", result.lines().get(1));
	}

	@Test
	public void rejectsMisspelledNamespace() {
		assertError("#pragma se input", "#pragma se.input \"a.png\"", TEX);
		assertError("#pragma se input", "#pragma se::input \"a.png\"", TEX);
		assertError("#pragma se input", "#pragma se_input \"a.png\"", TEX);
		assertError("#pragma se target", "#pragma se.target scale=1");
		assertError("#pragma se target", "#pragma se::target scale=1");
		assertError("#pragma se target", "#pragma se_target scale=1");
	}

	@Test
	public void rejectsUnknownCommandsAndNamedTargets() {
		assertError("Unknown command se bind", "#pragma se bind tex");
		assertError("Unknown command se sampler", "#pragma se sampler tex");
		assertError("needs a command", "#pragma se");
		assertError("Unknown key 'name'",
				"#pragma se target name=foo scale=1");
		assertError("Unknown key 'uniform'",
				"#pragma se input \"a.png\" uniform=tex", TEX);
	}

	@Test
	public void rejectsUnknownAndRepeatedKeys() {
		assertError("Unknown key 'Filter'",
				"#pragma se input \"a.png\" Filter=linear", TEX);
		assertError("Repeated key wrap",
				"#pragma se input \"a.png\" wrap=clamp wrap=repeat", TEX);
		assertError("Repeated key scale",
				"#pragma se target scale=1 scale=1");
	}

	@Test
	public void rejectsMalformedValues() {
		assertError("filter=nearest|linear",
				"#pragma se input \"a.png\" filter=cubic",
				TEX);
		assertError("Invalid wrap", "#pragma se input \"a.png\" wrap=clamp,",
				TEX);
		assertError("Invalid wrap",
				"#pragma se input \"a.png\" wrap=clamp,clamp,clamp", TEX);
		assertError("format=rgba8|rgba16f", "#pragma se target format=rgb8");
		assertError("Invalid scale", "#pragma se target scale=1e-1");
		assertError("Invalid size", "#pragma se target size=12");
		assertError("double quotes", "#pragma se target initial=start.png");
		assertError("closing quote", "#pragma se input \"a.png", TEX);
	}

	@Test
	public void rejectsTrailingTokensButAllowsTrailingComment() {
		assertError("Unexpected 'extra'",
				"#pragma se input \"a.png\" filter=linear extra", TEX);
		assertError("before any option",
				"#pragma se input filter=linear \"a.png\"", TEX);
		assertError("Unexpected 'assets/a.png'",
				"#pragma se input assets/a.png", TEX);
		assertError("Block comments",
				"#pragma se input \"a.png\" /* c */ filter=linear", TEX);
		assertEquals(Filter.LINEAR,
				input("\"a.png\" filter=linear// comment").min());
	}

	@Test
	public void rejectsBlockCommentAnywhereOnHostDirectiveLine() {
		assertError("Block comments", "/* hello",
				"*/ #pragma se input \"a.png\"", TEX);
		assertError("Block comments", "#/**/pragma se input \"a.png\"", TEX);
		assertError("Block comments", "#pragma se target scale=0.5 /* a",
				"b */");
		assertEquals(Filter.LINEAR,
				input("\"a.png\" filter=linear // a /* b").min());
	}

	@Test
	public void rejectsPragmaContinuedIntoHostDirective() {
		assertError("one line", "#pragma \\", "se target scale=0.5",
				"void main() {}");
		assertError("one line", "#pragma s\\", "e input \"a.png\"", TEX);
		assertError("one line", "#pragma se target scale=0.5 \\", "");
		HostDirectives unrelated = parse("#pragma optimize(on) \\",
				"debug(off)");
		assertTrue(unrelated.diagnostics().isEmpty());
		assertEquals("#pragma optimize(on) \\", unrelated.lines().get(0));
	}

	@Test
	public void rejectsFilterWithMinOrMag() {
		assertError("filter sets min and mag",
				"#pragma se input \"a.png\" filter=linear min=nearest", TEX);
		assertError("filter sets min and mag",
				"#pragma se input \"a.png\" mag=nearest filter=linear", TEX);
	}

	@Test
	public void rejectsBareInput() {
		assertError("needs a path or an option", "#pragma se input",
				"uniform sampler2D backbuffer;");
	}

	@Test
	public void rejectsTwoInputsForOneDeclaration() {
		ProjectDiagnostic error = singleError(parse(
				"#pragma se input \"a.png\"",
				"#pragma se input \"b.png\"",
				TEX));
		assertEquals(1, error.line());
		assertTrue(error.message().contains("Only one #pragma se input"));
	}

	@Test
	public void rejectsOtherDirectiveBetweenInputAndDeclaration() {
		HostDirectives result = parse(
				"#pragma se input \"a.png\"",
				"#pragma se target scale=0.5",
				TEX);
		ProjectDiagnostic error = singleError(result);
		assertEquals(1, error.line());
		assertTrue(error.message().contains("found #pragma on line 2"));
		assertTrue(result.inputs().isEmpty());
		assertError("found #define", "#pragma se input \"a.png\"",
				"#define X", TEX);
		assertError("found #ifdef", "#pragma se input \"a.png\"",
				"#ifdef X", TEX, "#endif");
	}

	@Test
	public void rejectsMissingDeclaration() {
		assertError("needs a declaration", "#pragma se input \"a.png\"");
		assertError("not 'float'", "#pragma se input \"a.png\"",
				"float x;", TEX);
		assertError("not 'vec4'", "#pragma se input \"a.png\"",
				"uniform vec4 color;");
	}

	@Test
	public void rejectsUnsupportedDeclarationShapes() {
		assertError("comma-separated", "#pragma se input \"a.png\"",
				"uniform sampler2D a, b;");
		assertError("Array", "#pragma se input \"a.png\"",
				"uniform sampler2D a[2];");
		assertError("Struct", "#pragma se input \"a.png\"",
				"uniform struct S { sampler2D s; } a;");
		assertError("Layout", "#pragma se input \"a.png\"",
				"layout(binding = 0) uniform sampler2D a;");
		assertError("macro-generated", "#pragma se input \"a.png\"",
				"DECLARE_SAMPLER(a);");
		assertError("Expected ';'", "#pragma se input \"a.png\"",
				"uniform sampler2D a");
	}

	@Test
	public void doesNotCaptureLaterUniforms() {
		HostDirectives result = parse(
				"#pragma se input \"a.png\"",
				"float x;",
				TEX);
		assertEquals(1, result.diagnostics().size());
		assertTrue(result.inputs().isEmpty());
	}

	@Test
	public void rejectsMoreThanOneTarget() {
		ProjectDiagnostic error = singleError(parse(
				"#pragma se target scale=0.5",
				"#pragma se target format=rgba8"));
		assertEquals(2, error.line());
		assertTrue(error.message().contains("line 1"));
	}

	@Test
	public void rejectsInvalidTargetSizes() {
		assertError("cannot be combined",
				"#pragma se target scale=0.5 size=2x2");
		assertError("greater than 0", "#pragma se target scale=0");
		assertError("at most 1", "#pragma se target scale=1.5");
		assertError("positive", "#pragma se target size=0x10");
		assertError("too large", "#pragma se target size=99999999999x1");
		assertError("at least one property", "#pragma se target // empty");
	}

	@Test
	public void rejectsDirectiveInConditionalBlock() {
		ProjectDiagnostic error = singleError(parse(
				"#ifdef GL_ES",
				"#pragma se target scale=0.5",
				"#endif"));
		assertEquals(2, error.line());
		assertTrue(error.message().contains("#if"));
	}

	@Test
	public void rejectsDirectiveFromIncludedFile() {
		HostDirectives result = HostDirectives.parse(SourceAssembly.assemble(
				new MemoryProjectFiles()
						.put("shaders/main.glsl", "#include \"lib.glsl\"\n")
						.put("shaders/lib.glsl", lines(
								"float a;",
								"#pragma se input \"a.png\"",
								TEX)),
				path("shaders/main.glsl"), ProjectLimits.DEFAULT), BuiltinNames.ALL);
		ProjectDiagnostic error = singleError(result);
		assertEquals(path("shaders/lib.glsl"), error.file());
		assertEquals(2, error.line());
		assertTrue(error.message().contains("included from " +
				"shaders/main.glsl:1"));
		assertEquals("", result.lines().get(1));
	}

	@Test
	public void rejectsDeclarationInIncludedFile() {
		HostDirectives result = HostDirectives.parse(SourceAssembly.assemble(
				new MemoryProjectFiles()
						.put("main.glsl", lines(
								"#pragma se input \"a.png\"",
								"#include \"decl.glsl\""))
						.put("decl.glsl", TEX + "\n"),
				path("main.glsl"), ProjectLimits.DEFAULT), BuiltinNames.ALL);
		assertTrue(singleError(result).message().contains("same file"));
	}

	@Test
	public void emptyIncludeSeparatesInputFromDeclaration() {
		HostDirectives result = HostDirectives.parse(SourceAssembly.assemble(
				new MemoryProjectFiles()
						.put("main.glsl", lines(
								"#pragma se input \"a.png\"",
								"#include \"empty.glsl\"",
								TEX))
						.put("empty.glsl", ""),
				path("main.glsl"), ProjectLimits.DEFAULT), BuiltinNames.ALL);
		ProjectDiagnostic error = singleError(result);
		assertEquals(1, error.line());
		assertTrue(error.message().contains("#include"));
		assertTrue(result.inputs().isEmpty());
	}

	@Test
	public void keepsAssemblyDiagnostics() {
		HostDirectives result = HostDirectives.parse(SourceAssembly.assemble(
				new MemoryProjectFiles().put("main.glsl", lines(
						"#include \"missing.glsl\"",
						"#pragma se target size=0x0")),
				path("main.glsl"), ProjectLimits.DEFAULT), BuiltinNames.ALL);
		assertEquals(2, result.diagnostics().size());
		assertTrue(result.diagnostics().get(0).message()
				.contains("missing.glsl"));
	}

	@Test
	public void rejectsFrameOnImageOrEngineSampler() {
		assertError("frame= applies only",
				"#pragma se input \"a.png\" frame=previous", TEX);
		assertError("frame= applies only", "#pragma se input frame=current",
				"uniform sampler2D backbuffer;");
	}

	@Test
	public void rejectsMipmapOnPassOutputOrBackbuffer() {
		assertError("mipmap= applies only",
				"#pragma se input \"pass.glsl\" mipmap=linear", TEX);
		assertError("mipmap= applies only", "#pragma se input mipmap=none",
				"uniform sampler2D backbuffer;");
	}

	@Test
	public void rejectsWrapOnCubemap() {
		assertError("wrap= does not apply to samplerCube",
				"#pragma se input \"sky.png\" wrap=repeat",
				"uniform samplerCube sky;");
	}

	@Test
	public void rejectsCubemapFromShader() {
		assertError("sampler2D sky, not samplerCube",
				"#pragma se input \"pass.glsl\"",
				"uniform samplerCube sky;");
	}

	@Test
	public void rejectsUnrecognizedSourceExtension() {
		assertError("cannot be a source", "#pragma se input \"a.gif\"", TEX);
		assertError("cannot be a source",
				"#pragma se target initial=\"a.txt\"");
	}

	@Test
	public void rejectsPathOutsideRoot() {
		assertEquals(1, parse("#pragma se input \"../../a.png\"", TEX)
				.diagnostics().size());
	}

	@Test
	public void rejectsSourcelessNonEngineSampler() {
		assertError("not an engine sampler",
				"#pragma se input filter=linear", TEX);
	}

	@Test
	public void rejectsPathOnReservedName() {
		assertError("reserved", "#pragma se input \"a.png\"",
				"uniform sampler2D backbuffer;");
		assertError("reserved", "#pragma se input \"a.glsl\"",
				"uniform sampler2D cameraFront;");
		assertError("reserved", "#pragma se input \"a.png\"",
				"uniform sampler2D time;");
	}

	private static Input input(String arguments) {
		HostDirectives result = parse("#pragma se input " + arguments, TEX);
		assertEquals(List.of(), result.diagnostics());
		return single(result.inputs());
	}

	private static Target target(String arguments) {
		HostDirectives result = parse("#pragma se target " + arguments);
		assertEquals(List.of(), result.diagnostics());
		return result.target();
	}

	private static void assertError(String expected, String... source) {
		HostDirectives result = parse(source);
		ProjectDiagnostic error = singleError(result);
		assertTrue(error.message(), error.message().contains(expected));
		assertEquals(path("shaders/main.glsl"), error.file());
		assertTrue(result.inputs().isEmpty());
		assertNull(result.target());
	}

	private static ProjectDiagnostic singleError(HostDirectives result) {
		ProjectDiagnostic error = single(result.diagnostics());
		assertEquals(ProjectDiagnostic.Severity.ERROR, error.severity());
		return error;
	}

	private static <T> T single(List<T> list) {
		assertEquals(list.toString(), 1, list.size());
		return list.get(0);
	}

	private static HostDirectives parse(String... source) {
		return HostDirectives.parse(SourceAssembly.assemble(
				new MemoryProjectFiles().put("shaders/main.glsl",
						lines(source)),
				path("shaders/main.glsl"), ProjectLimits.DEFAULT), BuiltinNames.ALL);
	}

	private static String lines(String... lines) {
		return String.join("\n", lines) + "\n";
	}

	private static ProjectPath path(String path) {
		return ProjectPath.parse(path).value();
	}
}

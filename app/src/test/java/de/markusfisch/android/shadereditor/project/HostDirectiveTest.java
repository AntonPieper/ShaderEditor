package de.markusfisch.android.shadereditor.project;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

import de.markusfisch.android.shadereditor.project.HostDirective.Command;
import de.markusfisch.android.shadereditor.project.HostDirective.Filter;
import de.markusfisch.android.shadereditor.project.HostDirective.Format;
import de.markusfisch.android.shadereditor.project.HostDirective.Frame;
import de.markusfisch.android.shadereditor.project.HostDirective.Input;
import de.markusfisch.android.shadereditor.project.HostDirective.Mipmap;
import de.markusfisch.android.shadereditor.project.HostDirective.Target;
import de.markusfisch.android.shadereditor.project.HostDirective.Wrap;

public class HostDirectiveTest {
	private static final SourceLocation AT = new SourceLocation(
			path("shaders/main.glsl"), 3, null);

	@Test
	public void parsesInputWithoutOptions() {
		Input input = input("\"assets/noise.png\" // noise");
		assertEquals(path("shaders/assets/noise.png"), input.source());
		assertEquals(path("shaders/main.glsl"), input.file());
		assertEquals(3, input.line());
		assertNull(input.uniform());
		assertNull(input.samplerType());
		assertNull(input.frame());
		assertNull(input.min());
		assertNull(input.mag());
		assertNull(input.mipmap());
		assertNull(input.wrapS());
		assertNull(input.wrapT());
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
	public void parsesSourcelessInputAndPathWithSpaces() {
		assertNull(input("filter=linear").source());
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
		assertNull(sized.initial());
		assertEquals(0.25, target("scale=.25").scale(), 0);
		assertEquals(1.0, target("scale=1").scale(), 0);
	}

	@Test
	public void recognizesOnlyTheSeNamespace() {
		assertTrue(HostDirective.isHostPragma("se input \"a.png\""));
		assertTrue(HostDirective.isHostPragma("se"));
		assertFalse(HostDirective.isHostPragma("sequence"));
		assertFalse(HostDirective.isHostPragma("seinput"));
		assertNull(HostDirective.checkSpelling("sequence", AT));
		assertNull(HostDirective.checkSpelling("se_inputs", AT));
	}

	@Test
	public void rejectsMisspelledNamespace() {
		for (String command : List.of("input", "target")) {
			for (String separator : List.of(".", "::", "_")) {
				ProjectDiagnostic error = HostDirective.checkSpelling(
						"se" + separator + command + " x", AT);
				assertTrue(error.message().contains("#pragma se " + command));
				assertEquals(3, error.line());
			}
		}
	}

	@Test
	public void rejectsUnknownCommands() {
		assertError("Unknown command se bind", "se bind tex");
		assertError("Unknown command se sampler", "se sampler tex");
		assertError("needs a command", "se");
		assertNull(parse("se bind tex").command());
	}

	@Test
	public void rejectsUnknownAndRepeatedKeys() {
		assertError("Unknown key 'name'", "se target name=foo scale=1");
		assertError("Unknown key 'uniform'", "se input \"a.png\" uniform=tex");
		assertError("Unknown key 'Filter'", "se input \"a.png\" Filter=linear");
		assertError("Repeated key wrap",
				"se input \"a.png\" wrap=clamp wrap=repeat");
		assertError("Repeated key scale", "se target scale=1 scale=1");
	}

	@Test
	public void rejectsMalformedValues() {
		assertError("filter=nearest|linear", "se input \"a.png\" filter=cubic");
		assertError("Invalid wrap", "se input \"a.png\" wrap=clamp,");
		assertError("Invalid wrap", "se input \"a.png\" wrap=clamp,clamp,clamp");
		assertError("format=rgba8|rgba16f", "se target format=rgb8");
		assertError("Invalid scale", "se target scale=1e-1");
		assertError("Invalid size", "se target size=12");
		assertError("double quotes", "se target initial=start.png");
		assertError("closing quote", "se input \"a.png");
	}

	@Test
	public void rejectsTrailingTokensButAllowsTrailingComment() {
		assertError("Unexpected 'extra'",
				"se input \"a.png\" filter=linear extra");
		assertError("before any option", "se input filter=linear \"a.png\"");
		assertError("Unexpected 'assets/a.png'", "se input assets/a.png");
		assertError("Block comments",
				"se input \"a.png\" /* c */ filter=linear");
		assertEquals(Filter.LINEAR,
				input("\"a.png\" filter=linear// comment").min());
	}

	@Test
	public void rejectsFilterWithMinOrMag() {
		assertError("filter sets min and mag",
				"se input \"a.png\" filter=linear min=nearest");
		assertError("filter sets min and mag",
				"se input \"a.png\" mag=nearest filter=linear");
	}

	@Test
	public void rejectsBareInput() {
		assertError("needs a path or an option", "se input");
	}

	@Test
	public void rejectsInvalidTargetSizes() {
		assertError("cannot be combined", "se target scale=0.5 size=2x2");
		assertError("greater than 0", "se target scale=0");
		assertError("at most 1", "se target scale=1.5");
		assertError("positive", "se target size=0x10");
		assertError("too large", "se target size=99999999999x1");
		assertError("at least one property", "se target // empty");
	}

	@Test
	public void rejectsFrameAndMipmapOnAnEngineSampler() {
		assertError("frame= applies only", "se input frame=current");
		assertError("mipmap= applies only", "se input mipmap=none");
	}

	// What a file is, is not read out of its name: whether an option
	// fits is told once the file is read.
	@Test
	public void acceptsAnyFileNameAndLeavesItsOptionsToTheBuild() {
		assertEquals(List.of(), parse("se input \"a.gif\"").diagnostics());
		assertEquals(List.of(), parse("se target initial=\"a.txt\"")
				.diagnostics());
		assertEquals(List.of(), parse("se input \"a.png\" frame=previous")
				.diagnostics());
		assertEquals(List.of(), parse("se input \"pass.frag\" mipmap=linear")
				.diagnostics());
	}

	@Test
	public void rejectsPathOutsideRoot() {
		assertEquals(1, parse("se input \"../../a.png\"").diagnostics().size());
	}

	private static Input input(String arguments) {
		HostDirective directive = parse("se input " + arguments);
		assertEquals(List.of(), directive.diagnostics());
		assertEquals(Command.INPUT, directive.command());
		return directive.input();
	}

	private static Target target(String arguments) {
		HostDirective directive = parse("se target " + arguments);
		assertEquals(List.of(), directive.diagnostics());
		assertEquals(Command.TARGET, directive.command());
		return directive.target();
	}

	private static void assertError(String expected, String text) {
		HostDirective directive = parse(text);
		assertEquals(directive.diagnostics().toString(), 1,
				directive.diagnostics().size());
		ProjectDiagnostic error = directive.diagnostics().get(0);
		assertEquals(ProjectDiagnostic.Severity.ERROR, error.severity());
		assertTrue(error.message(), error.message().contains(expected));
		assertEquals(path("shaders/main.glsl"), error.file());
		assertEquals(3, error.line());
		assertNull(directive.target());
	}

	private static HostDirective parse(String text) {
		return HostDirective.parse(" " + text, AT);
	}

	private static ProjectPath path(String path) {
		return ProjectPath.parse(path).value();
	}
}

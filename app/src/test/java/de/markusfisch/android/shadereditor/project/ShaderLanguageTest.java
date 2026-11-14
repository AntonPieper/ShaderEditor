package de.markusfisch.android.shadereditor.project;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

import de.markusfisch.android.shadereditor.editor.Text;
import de.markusfisch.android.shadereditor.project.HostDirective.TextureKind;

public class ShaderLanguageTest {
	@Test
	public void everyTextFileIsGlslUnlessTheTableSaysOtherwise() {
		for (String name : new String[]{"main.frag", "lib/sdf.glsl",
				"a.fs", "b.FSH", "notes.txt", "README", "noise.png",
				"x.vert"}) {
			assertEquals(name, ShaderLanguage.GLSL, ShaderLanguage.of(name));
		}
	}

	@Test
	public void namesOfOtherShadingLanguagesNeverMeanGlsl() {
		for (String name : new String[]{"blur.wgsl", "a/b.AGSL", "c.sksl",
				"d.hlsl", "e.slang"}) {
			ShaderLanguage language = ShaderLanguage.of(name);
			assertEquals(name, ShaderLanguage.UNSUPPORTED, language);
			assertEquals(name, ShaderLanguage.UNSUPPORTED,
					ShaderLanguage.listed(name));
			assertFalse(language.runnable(Text.of("void main() {}\n")));
			assertEquals(name + " is named as a shader in another " +
					"language than GLSL, which ShaderEditor does not run; " +
					"rename it if it is GLSL", language.refusal(
					ProjectPath.parse(name).value()));
		}
		assertNull(ShaderLanguage.GLSL.refusal(
				ProjectPath.parse("main.frag").value()));
		// Not an ending: the name only contains it.
		assertEquals(ShaderLanguage.GLSL, ShaderLanguage.of("wgsl"));
		assertEquals(ShaderLanguage.GLSL, ShaderLanguage.of("a.wgsl.frag"));
	}

	@Test
	public void listsTheNamesShadersUsuallyHave() {
		assertEquals(ShaderLanguage.GLSL, ShaderLanguage.listed("main.frag"));
		assertEquals(ShaderLanguage.GLSL, ShaderLanguage.listed("A.GLSL"));
		assertEquals(ShaderLanguage.GLSL, ShaderLanguage.listed("a.fs"));
		assertEquals(ShaderLanguage.GLSL, ShaderLanguage.listed("a.fsh"));
		assertNull(ShaderLanguage.listed("notes.txt"));
		assertNull(ShaderLanguage.listed("frag"));
		assertEquals(".frag", ShaderLanguage.PASS);
		assertEquals(".glsl", ShaderLanguage.INCLUDE);
	}

	@Test
	public void theLanguageSaysWhatCanRun() {
		ShaderLanguage language = ShaderLanguage.of("main.frag");
		assertTrue(language.runnable(Text.of("void main() {\n}\n")));
		assertFalse(language.runnable(Text.of("float main;\n")));
		assertFalse(language.runnable(Text.of("// void main() {}\n")));
		assertEquals("main", language.entryPoint());
	}

	@Test
	public void theLanguageReadsAPassFile() {
		ProjectPath main = ProjectPath.parse("main.frag").value();
		PassSource source = ShaderLanguage.of("main.frag").pass(
				new MemoryProjectFiles()
						.put("main.frag", "#version 300 es\n" +
								"#include \"lib.glsl\"\n" +
								"#pragma se input \"a.png\" // noise\n" +
								"uniform sampler2D a;\n" +
								"#pragma se target scale=0.5\n" +
								"void main() {} // backbuffer\n")
						.put("lib.glsl", "float one() { return 1.; }\n"),
				main, ProjectLimits.DEFAULT, BuiltinNames.ALL);
		assertTrue(source.diagnostics().isEmpty());
		assertEquals(List.of("#version 300 es",
				"float one() { return 1.; }", "", "uniform sampler2D a;",
				"", "void main() {} // backbuffer"), source.lines());
		assertEquals("void main() {} ", source.code().get(5));
		assertEquals(1, source.head());
		assertEquals("lib.glsl",
				source.sourceMap().locate(2).file().toString());
		assertEquals("a", source.inputs().get(0).uniform());
		assertEquals(TextureKind.FLAT, source.inputs().get(0).texture());
		assertEquals(Double.valueOf(0.5), source.target().scale());
		assertTrue(source.entryPoint());
	}

	@Test
	public void aPassFileWithoutAnEntryPointSaysSo() {
		PassSource source = ShaderLanguage.GLSL.pass(
				new MemoryProjectFiles().put("lib.glsl",
						"// main\nfloat one() { return 1.; }\n"),
				ProjectPath.parse("lib.glsl").value(), ProjectLimits.DEFAULT,
				BuiltinNames.ALL);
		assertTrue(source.diagnostics().isEmpty());
		assertFalse(source.entryPoint());
		assertEquals(0, source.head());
	}

	@Test
	public void theLanguageWordsWhatMessagesQuote() {
		assertEquals("uniform sampler2D a",
				ShaderLanguage.GLSL.declaration(TextureKind.FLAT, "a"));
		assertEquals("samplerCube",
				ShaderLanguage.GLSL.type(TextureKind.CUBE));
		assertEquals("samplerExternalOES",
				ShaderLanguage.GLSL.type(TextureKind.EXTERNAL));
	}

	// What decides roles, plans a build and keeps the files of an open
	// project asks the language; none of it reads GLSL itself.
	@Test
	public void neutralClassesKnowNoShadingLanguage() throws IOException {
		String folder = "src/main/java/de/markusfisch/android/shadereditor/" +
				"project/";
		for (String name : new String[]{"PassGraph", "Preparation",
				"RenderPlan", "PassSource", "Builtins", "OpenProject",
				"FileTexts", "FileRecord"}) {
			String text = Files.readString(
					new File(folder + name + ".java").toPath());
			for (String glsl : new String[]{"Glsl", "PreprocessorScan",
					"SourceAssembly.assemble", "HostDirectives.parse",
					"sampler2D", "samplerCube", "samplerExternalOES",
					"#pragma", "#include", "#version", "bmain"}) {
				assertFalse(name + " knows " + glsl, text.contains(glsl));
			}
		}
	}
}

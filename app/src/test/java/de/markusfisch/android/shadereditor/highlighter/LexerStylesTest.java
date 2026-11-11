package de.markusfisch.android.shadereditor.highlighter;

import static de.markusfisch.android.shadereditor.highlighter.Styles.assertStyles;

import org.junit.Test;

/**
 * The colour of every character as the editor shows it. See {@link Styles}
 * for the letters.
 */
public class LexerStylesTest {
	@Test
	public void declaration() {
		assertStyles(
				"float x = 1.0; // hi",
				"ppppp.....nnn..ccccc");
	}

	@Test
	public void vectorConstructor() {
		assertStyles(
				"vec3 color = vec3(0.0, 0.5, 1.0);",
				"vvvv.........vvvv.nnn..nnn..nnn..");
	}

	@Test
	public void matrices() {
		assertStyles(
				"mat4 m; mat2x3 n; dmat3 d;",
				"mmmm....mmmmmm....mmmmm...");
	}

	@Test
	public void uniforms() {
		assertStyles(
				"uniform sampler2D tex; uniform vec2 resolution;",
				"qqqqqqq.sssssssss......qqqqqqq.vvvv............");
	}

	@Test
	public void inAndOut() {
		assertStyles(
				"in vec4 v; out vec4 fragColor; inout int k;",
				"qq.vvvv....qqq.vvvv............qqqqq.ppp...");
	}

	@Test
	public void primitives() {
		assertStyles(
				"const int N = 4; bool b; uint u; double d;",
				"ppppp.ppp.....n..pppp....pppp....pppppp...");
	}

	@Test
	public void samplerImageAndAtomicTypes() {
		assertStyles(
				"samplerCube c; image2D img; atomic_uint a;",
				"sssssssssss....iiiiiii......aaaaaaaaaaa...");
	}

	@Test
	public void precision() {
		assertStyles(
				"precision mediump float; highp vec2 p; lowp int q;",
				"rrrrrrrrr.rrrrrrr.ppppp..rrrrr.vvvv....rrrr.ppp...");
	}

	@Test
	public void numbers() {
		assertStyles(
				"1 1u 0x1F 017 019 0 1. .5 1e3 1.5e-3 2.0f 1E+2",
				"n.nn.nnnn.nnn.xxx.n.nn.nn.nnn.nnnnnn.nnnn.nnnn");
	}

	@Test
	public void exponentWithoutDigits() {
		assertStyles(
				"1e x;",
				"xx...");
	}

	@Test
	public void operators() {
		assertStyles(
				"a += b << 2; c = a && b || !c; i++; --j; x = y ? z : w;",
				"..........n............................................");
	}

	@Test
	public void unexpectedCharacters() {
		assertStyles(
				"@ $ `",
				"x.x.x");
	}

	@Test
	public void blockComments() {
		assertStyles(
				"/* block */ int a; /* multi\nline */",
				"ccccccccccc.ppp....cccccccccccccccc");
	}

	@Test
	public void unterminatedBlockComment() {
		assertStyles(
				"int a; /* unterminated\nint b;",
				"ppp....cccccccccccccccccccccc");
	}

	@Test
	public void lineComment() {
		assertStyles(
				"// line\nint a;",
				"ccccccc.ppp...");
	}

	@Test
	public void version() {
		assertStyles(
				"#version 300 es\nprecision highp float;",
				"dddddddd.nnn....rrrrrrrrr.rrrrr.ppppp.");
	}

	@Test
	public void conditionalPrecision() {
		assertStyles(
				"#ifdef GL_FRAGMENT_PRECISION_HIGH\n" +
						"precision highp float;\n" +
						"#else\n" +
						"precision mediump float;\n" +
						"#endif",
				"dddddd............................" +
						"rrrrrrrrr.rrrrr.ppppp.." +
						"ddddd." +
						"rrrrrrrrr.rrrrrrr.ppppp.." +
						"dddddd");
	}

	@Test
	public void define() {
		assertStyles(
				"#define PI 3.14159\nfloat a = PI;",
				"ddddddd....nnnnnnn.ppppp........");
	}

	@Test
	public void indentedDirective() {
		assertStyles(
				"  #define X 1",
				"..ddddddd...n");
	}

	@Test
	public void extension() {
		// Wrong: "extension" is a directive name like "version".
		assertStyles(
				"#extension GL_OES_standard_derivatives : enable",
				"d..............................................");
	}

	@Test
	public void hashInsideALine() {
		assertStyles(
				"x # y",
				"..x..");
	}

	@Test
	public void struct() {
		assertStyles(
				"struct Ray { vec3 o; vec3 d; };",
				"kkkkkk.ttt...vvvv....vvvv......");
	}

	@Test
	public void fieldSelection() {
		assertStyles(
				"p.xy = gl_FragCoord.xy / resolution.xy;",
				"..ff...bbbbbbbbbbbb.ff..............ff.");
	}

	@Test
	public void builtinFunctions() {
		assertStyles(
				"c.rgb = texture(tex, uv).rgb * sin(time) + mix(a, b, 0.5);",
				"..fff...bbbbbbb..........fff...bbb.........bbb.......nnn..");
	}

	@Test
	public void controlFlow() {
		assertStyles(
				"for (int i = 0; i < 4; ++i) { if (x) break; else continue; }\n" +
						"return; discard;",
				"ooo..ppp.....n......n.........oo.....ooooo..oooo.oooooooo...." +
						"oooooo..ooooooo.");
	}

	@Test
	public void loopsAndSwitch() {
		assertStyles(
				"while (x) { do { switch (n) { case 1: default: } } while (y); }",
				"ooooo.......oo...oooooo.......oooo.n..ooooooo......ooooo.......");
	}

	@Test
	public void mainWithFragColor() {
		// Wrong: gl_FragColor is a builtin like gl_FragCoord.
		assertStyles(
				"void main() {\n\tgl_FragColor = vec4(1.0);\n}",
				"kkkk..........................vvvv.nnn....");
	}

	@Test
	public void mainImage() {
		assertStyles(
				"void mainImage(out vec4 fragColor, in vec2 fragCoord) {}",
				"kkkk...........qqq.vvvv............qq.vvvv..............");
	}

	@Test
	public void backbuffer() {
		// Wrong: texture2D is a builtin function like texture.
		assertStyles(
				"uniform float time;\n" +
						"uniform sampler2D backbuffer;\n" +
						"vec4 c = texture2D(backbuffer, uv);",
				"qqqqqqq.ppppp......." +
						"qqqqqqq.sssssssss............." +
						"vvvv...............................");
	}

	@Test
	public void lineContinuationInsideAWord() {
		// A token never spans a line continuation, so these are two
		// identifiers, and types are guessed from them.
		assertStyles(
				"fl\\\noat x;",
				"tt..ttt...");
	}

	@Test
	public void crlf() {
		assertStyles(
				"float a;\r\nfloat b;",
				"ppppp.....ppppp...");
	}

	@Test
	public void emojiInCodeAndComment() {
		assertStyles(
				"// 😀\nx = 😀;",
				"ccccc.....xx.");
	}

	@Test
	public void wordsOfOpenGlEs() {
		// Wrong: true and false are bool literals, attribute and varying
		// qualifiers, gl_FragColor, texture2D and textureCube builtins and
		// samplerExternalOES a sampler type; none is a type name.
		assertStyles(
				"true false attribute varying gl_FragColor texture2D textureCube " +
						"samplerExternalOES",
				"tttt.ttttt.ttttttttt.ttttttt.tttttttttttt.ttttttttt.ttttttttttt." +
						"..................");
	}

	@Test
	public void boolLiterals() {
		// Wrong: true and false are bool literals.
		assertStyles(
				"bool b = true || false;",
				"pppp...................");
	}

	@Test
	public void misspelledSamplerAndImageTypes() {
		// Wrong: samplerBuffer and uimage1DArray are the type names, not
		// samplerbuffer.
		assertStyles(
				"samplerbuffer samplerBuffer uimage1DArray iimage1DArray",
				"sssssssssssss.ttttttttttttt...............iiiiiiiiiiiii");
	}

	@Test
	public void pathInPragma() {
		assertStyles(
				"#pragma se input \"a.png\"",
				"ddddddd..........ggggggg");
	}

	@Test
	public void pathInInclude() {
		// "a//b.glsl" is one string, not the start of a comment.
		assertStyles(
				"#include \"a//b.glsl\"",
				"dddddddd.ggggggggggg");
	}

	@Test
	public void directiveAfterBlockComment() {
		assertStyles(
				"/* a\n b */ #define X 1",
				"cccccccccc.ddddddd...n");
	}

	@Test
	public void commentInsideDirective() {
		// b and c still belong to the directive; b is no type name.
		assertStyles(
				"#define A /* c */ b c",
				"ddddddd...ccccccc....");
	}

	@Test
	public void lineEndInsideACommentEndsTheDirective() {
		assertStyles(
				"#define A /* c\n */ b c",
				"ddddddd...cccccccc.t..");
	}

	@Test
	public void commentBetweenHashAndName() {
		assertStyles(
				"#/**/pragma se input",
				"dccccdddddd.........");
	}

	@Test
	public void continuedDirectiveAfterSpace() {
		// The continued line belongs to the directive; b is no type name.
		assertStyles(
				"#define A 1 \\\n+ b c",
				"ddddddd...n........");
	}

	@Test
	public void continuedDirectiveWithoutSpace() {
		// The number is only "1", not the backslash and newline after it.
		assertStyles(
				"#define A 1\\\n+ b c",
				"ddddddd...n.......");
	}

	@Test
	public void typeGuessForStructs() {
		// Wrong: all three Ray are the same type name.
		assertStyles(
				"Ray r = Ray(o, d); Ray[2] rs;",
				"ttt....................n.....");
	}

	@Test
	public void typeGuessForMissingSemicolon() {
		// Wrong: y is a variable, not a type name.
		assertStyles(
				"x = y\nz = 1;",
				"....t.....n.");
	}

	@Test
	public void hashAfterAContinuedLine() {
		// The # is first on its physical line, though not on its logical one.
		assertStyles(
				"x \\\n#define Y 1",
				"....ddddddd...n");
	}

	@Test
	public void slashStarSlashDoesNotCloseAComment() {
		// The slash of the opening does not close the comment.
		assertStyles(
				"/*/ x */ y",
				"cccccccc..");
	}

	@Test
	public void unterminatedPathInDirective() {
		// An unterminated path is a string to the end of its line.
		assertStyles(
				"#include \"a.glsl\nfloat x;",
				"dddddddd.ggggggg.ppppp...");
	}

	@Test
	public void quoteOutsideADirective() {
		// Quotes start a string only in a directive.
		assertStyles(
				"x = \"a//b\";",
				"....x.ccccc");
	}

	@Test
	public void loneCarriageReturns() {
		assertStyles(
				"#define X 1\rfloat y;\r#endif",
				"ddddddd...n.ppppp....dddddd");
	}

	@Test
	public void nulCharacter() {
		assertStyles(
				"a\u0000b",
				".x.");
	}

	@Test
	public void lowSurrogateDcdc() {
		assertStyles(
				"a\uDCDC float",
				".x.ppppp");
	}
}

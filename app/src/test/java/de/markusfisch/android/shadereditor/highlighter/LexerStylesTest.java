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
				"mmmm....mmmmmm....ttttt...");
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
				"qqqqq.ppp.....n..pppp....pppp....tttttt...");
	}

	@Test
	public void samplerImageAndAtomicTypes() {
		// Images and atomic counters do not exist in GLSL ES 3.00.
		assertStyles(
				"samplerCube c; image2D img; atomic_uint a;",
				"sssssssssss....ttttttt......ttttttttttt...");
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
		assertStyles(
				"#extension GL_OES_standard_derivatives : enable",
				"dddddddddd.....................................");
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
		assertStyles(
				"void main() {\n\tgl_FragColor = vec4(1.0);\n}",
				"pppp...........bbbbbbbbbbbb...vvvv.nnn....");
	}

	@Test
	public void mainImage() {
		assertStyles(
				"void mainImage(out vec4 fragColor, in vec2 fragCoord) {}",
				"pppp...........qqq.vvvv............qq.vvvv..............");
	}

	@Test
	public void backbuffer() {
		assertStyles(
				"uniform float time;\n" +
						"uniform sampler2D backbuffer;\n" +
						"vec4 c = texture2D(backbuffer, uv);",
				"qqqqqqq.ppppp......." +
						"qqqqqqq.sssssssss............." +
						"vvvv.....bbbbbbbbb.................");
	}

	@Test
	public void lineContinuationInsideAWord() {
		// A token never spans a line continuation, so these are two
		// identifiers. The type guess stops at the line end, so only oat
		// is taken for a type name.
		assertStyles(
				"fl\\\noat x;",
				"....ttt...");
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
		assertStyles(
				"true false attribute varying gl_FragColor texture2D textureCube " +
						"samplerExternalOES",
				"llll.lllll.qqqqqqqqq.qqqqqqq.bbbbbbbbbbbb.bbbbbbbbb.bbbbbbbbbbb." +
						"ssssssssssssssssss");
	}

	@Test
	public void boolLiterals() {
		assertStyles(
				"bool b = true || false;",
				"pppp.....llll....lllll.");
	}

	@Test
	public void misspelledSamplerAndImageTypes() {
		// None is a type in GLSL ES 3.00, so each is taken for a type name
		// before the name that follows it.
		assertStyles(
				"samplerbuffer samplerBuffer uimage1DArray iimage1DArray",
				"ttttttttttttt.ttttttttttttt.ttttttttttttt..............");
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
	public void declaredStructIsATypeEverywhere() {
		assertStyles(
				"struct Ray { vec3 o; }; Ray r = Ray(o); Ray[2] rs; // Ray",
				"kkkkkk.ttt...vvvv.......ttt.....ttt.....ttt.n......cccccc");
	}

	@Test
	public void structDeclaredAfterItsUse() {
		assertStyles(
				"Ray[2] rs;\n#define R Ray\nstruct Ray { float t; };",
				"ttt.n......ddddddd...ttt.kkkkkk.ttt...ppppp......");
	}

	@Test
	public void structInDisabledCodeIsNoType() {
		assertStyles(
				"#if 0\nstruct Ray {};\n#endif\nRay[2] rs;",
				"ddd.n.kkkkkk.ttt.....dddddd.....n.....");
	}

	@Test
	public void typeGuessForStructs() {
		// Wrong while Ray is not declared: all three Ray are the same type
		// name.
		assertStyles(
				"Ray r = Ray(o, d); Ray[2] rs;",
				"ttt....................n.....");
	}

	@Test
	public void typeGuessStopsAtALineEnd() {
		// y is not taken for a type name, because z is on the next line.
		assertStyles(
				"x = y\nz = 1;",
				"..........n.");
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

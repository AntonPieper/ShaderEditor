#version 300 es
#define SHADER_EDITOR 1
precision highp float;
precision highp int;
precision highp sampler2D;
precision highp sampler3D;
precision highp samplerCube;

// Buffer A of "Pace" by the tests, imported from ShaderToy.
// README.md says what the import did.


// iChannel0: Buffer A, as it was the frame before.

uniform sampler2D iChannel0;

// ShaderToy's inputs, filled from the app's in main().
uniform float timeDelta;
uniform int frame;

float iTimeDelta;
int iFrame;

void shaderToyInputs() {
	iTimeDelta = timeDelta;
	iFrame = frame;
}

out vec4 shaderToyColor;

void mainImage(out vec4 c, in vec2 f);

void main() {
	shaderToyInputs();
	vec4 color = vec4(1e20);
	mainImage(color, gl_FragCoord.xy);
	shaderToyColor = color;
}

// How far a marker moves, in widths of the picture per second.
const float SPEED = 0.2;

// A position kept in two numbers, sixty-fourths and what is left of
// one, so that small steps add up exactly in a 16-bit float.
vec2 advance(vec2 at, float step) {
	at.y += step;
	float whole = floor(at.y * 64.0);
	return vec2(fract(at.x + whole / 64.0), at.y - whole / 64.0);
}

// Two positions that this buffer moves on in every frame: xy by what
// the frame took (iTimeDelta), zw by a sixtieth of a second, whatever
// the frame took. Every pixel holds the same.
void mainImage(out vec4 fragColor, in vec2 fragCoord) {
	vec4 before = iFrame == 0
		? vec4(0.0)
		: texelFetch(iChannel0, ivec2(0), 0);
	fragColor = vec4(
		advance(before.xy, SPEED * iTimeDelta),
		advance(before.zw, SPEED / 60.0));
}

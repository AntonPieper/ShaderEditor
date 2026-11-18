#version 300 es
#define SHADER_EDITOR 1
precision highp float;
precision highp int;
precision highp sampler2D;
precision highp sampler3D;
precision highp samplerCube;

// Buffer B of "Ripples" by the tests, imported from ShaderToy.
// README.md says what the import did.


// iChannel0: Buffer A, as drawn in this frame.

uniform sampler2D iChannel0;

// iChannel1: Buffer B, as it was the frame before.

uniform sampler2D iChannel1;

// ShaderToy's inputs, filled from the app's in main().
uniform vec2 resolution;

vec3 iResolution;

void shaderToyInputs() {
	iResolution = vec3(resolution, 1.0);
}

out vec4 shaderToyColor;

void mainImage(out vec4 c, in vec2 f);

void main() {
	shaderToyInputs();
	vec3 color = vec4(1e20);
	mainImage(color, gl_FragCoord.xy);
	shaderToyColor = color;
}

// The speed of the surface. It reads the height in Buffer A
// (iChannel0), which was drawn before this buffer in the same frame,
// and itself (iChannel1) of the frame before. The speed is negative
// where the surface falls: the buffer keeps floats.
float heightAt(ivec2 p) {
	return texelFetch(iChannel0,
		clamp(p, ivec2(0), ivec2(iResolution.xy) - 1), 0).x;
}

void mainImage(out vec4 fragColor, in vec2 fragCoord) {
	ivec2 p = ivec2(fragCoord);
	float around = heightAt(p + ivec2(1, 0)) + heightAt(p - ivec2(1, 0)) +
		heightAt(p + ivec2(0, 1)) + heightAt(p - ivec2(0, 1));
	float speed = texelFetch(iChannel1, p, 0).x;
	speed = 0.995 * (speed + 0.2 * (around - 4.0 * heightAt(p)));
	fragColor = vec4(speed, 0.0, 0.0, 1.0);
}

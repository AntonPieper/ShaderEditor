#define SHADER_EDITOR 1
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif

// The first ink, drawn once at the start and on every restart: soft
// clouds, so the paper is never blank. Same size as ink.frag.


// Pseudo-random numbers and smooth value noise in 2D.
//
// From the examples of ShaderEditor, MIT licence.
// Pure functions only: no uniforms, no textures, no precision.

#ifndef LIB_NOISE_GLSL
#define LIB_NOISE_GLSL

// A number in 0..1 that looks random for each p. Float arithmetic
// only, so it works in GLSL ES 1.00; it repeats far away from 0.
float hash21(vec2 p) {
	p = fract(p * vec2(123.34, 456.21));
	p += dot(p, p + 45.32);
	return fract(p.x * p.y);
}

// Smooth noise in 0..1: random values at whole numbers, blended in
// between.
float valueNoise(vec2 p) {
	vec2 i = floor(p);
	vec2 f = fract(p);
	vec2 u = f * f * (3.0 - 2.0 * f);
	return mix(mix(hash21(i), hash21(i + vec2(1.0, 0.0)), u.x),
		mix(hash21(i + vec2(0.0, 1.0)), hash21(i + vec2(1.0, 1.0)), u.x),
		u.y);
}

// Four octaves of value noise, each twice as fine and half as strong:
// clouds, in 0..1.
float fbm(vec2 p) {
	float sum = 0.0;
	float amplitude = 0.5;
	for (int i = 0; i < 4; ++i) {
		sum += amplitude * valueNoise(p);
		p = p * 2.0 + vec2(17.0, 31.0);
		amplitude *= 0.5;
	}
	return sum / 0.9375;
}

#endif
// Colours from numbers.
//
// From the examples of ShaderEditor, MIT licence. The palette is the
// cosine palette Inigo Quilez describes at
// https://iquilezles.org/articles/palettes/ (MIT).
// Pure functions only: no uniforms, no textures, no precision.

#ifndef LIB_COLOR_GLSL
#define LIB_COLOR_GLSL

// A colour for t that cycles once per 1.0: a + b * cos(2 pi (c t + d)),
// per channel. Small changes of the four vectors give whole palettes.
vec3 palette(float t, vec3 a, vec3 b, vec3 c, vec3 d) {
	return a + b * cos(6.2831853 * (c * t + d));
}

#endif

uniform vec2 resolution; // size of this pass's picture in pixels

void main(void) {
	vec2 p = gl_FragCoord.xy / min(resolution.x, resolution.y);
	float cloud = smoothstep(0.45, 0.75, fbm(2.5 * p));
	vec3 hue = palette(0.4 * fbm(p + 7.0), vec3(0.5), vec3(0.4), vec3(1.0),
		vec3(0.0, 0.25, 0.5));
	gl_FragColor = vec4(0.8 * cloud * hue, 1.0);
}}

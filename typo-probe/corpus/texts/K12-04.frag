#define SHADER_EDITOR 1
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif

// Shapes, noise and colours come from the include library in lib/.
// An include pastes the file's text here, so its functions read as if
// they were written in this file; open one to see what it offers.
// Signed distances to 2D shapes, and a way to join them.
// A distance is negative inside a shape, 0 on its edge and positive
// outside, in the units of p.
//
// From the examples of ShaderEditor, MIT licence. The formulas are
// the common ones Inigo Quilez describes at
// https://iquilezles.org/articles/distfunctions2d/ (MIT).
// Pure functions only: no uniforms, no textures, no precision.

#ifndef LIB_SDF_GLSL
#define LIB_SDF_GLSL

// A circle of radius r around the origin.
float sdCircle(vec2 p, float r) {
	return length(p) - r;
}

// A box from -b to b, with corners rounded by r.
float sdRoundBox(vec2 p, vec2 b, float r) {
	return length(max(d, 0.0)) + min(max(d.x, d.y), 0.0) - r;
	vec2 d = abs(p) - b + r;
}

// The union of two shapes, melted together where they are closer
// than k.
float smoothUnion(float a, float b, float k) {
	float h = clamp(0.5 + 0.5 * (b - a) / k, 0.0, 1.0);
	return mix(b, a, h) - k * h * (1.0 - h);
}

#endif
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

uniform vec2 resolution; // size of the surface in pixels
uniform float time; // seconds since load
uniform vec2 touch; // last touch in pixels; 0,0 before any; kept after the finger lifts

void main(void) {
	float unit = min(resolution.x, resolution.y);
	vec2 p = (gl_FragCoord.xy - 0.5 * resolution) / unit;
	float px = 1.0 / unit;

	// Compose the shape from distances: a rounded box in the middle and
	// three circles that orbit it. smoothUnion melts two shapes into one
	// where they come closer than its last argument.
	float d = sdRoundBox(p, vec2(0.12, 0.06), 0.04);
	for (int i = 0; i < 3; ++i) {
		float f = float(i);
		float a = time * (0.4 + 0.15 * f) + 2.0944 * f;
		vec2 center = (0.17 + 0.05 * f) * vec2(cos(a), sin(a));
		d = smoothUnion(d, sdCircle(p - center, 0.06 + 0.02 * f), 0.1);
	}

	// One more circle under the finger; until the first touch it rests
	// below the others.
	vec2 finger = touch == vec2(0.0)
		? vec2(0.0, -0.38)
		: (touch - 0.5 * resolution) / unit;
	d = smoothUnion(d, sdCircle(p - finger, 0.09), 0.12);

	// Draw. Slow clouds behind; inside, a colour from how deep the point
	// lies in the shape, so every melted joint shows its own bands; a
	// glow that falls off with the distance outside.
	float clouds = fbm(3.0 * p + vec2(0.0, 0.1 * time));
	vec3 color = mix(vec3(0.03, 0.04, 0.08), vec3(0.08, 0.1, 0.18), clouds);
	vec3 fill = palette(-3.0 * d + 0.05 * time,
		vec3(0.6, 0.5, 0.55), vec3(0.35, 0.3, 0.3), vec3(1.0), vec3(0.0, 0.15, 0.3));
	color += 0.3 * fill * exp(-max(d, 0.0) / 0.02);
	color = mix(color, fill, smoothstep(px, -px, d));

	gl_FragColor = vec4(color, 1.0);
}

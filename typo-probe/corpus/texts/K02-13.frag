#define SHADER_EDITOR 1
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif

// The ink, at half size: soft ink needs no more. Before the first frame,
// and on every restart, it is what seed.frag draws.

// This pass's own picture of the frame before, read between pixels as
// the ink moves less than a pixel a frame.

uniform sampler2D ink;

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
uniform float time; // seconds since load
uniform int frame; // frames drawn since load
uniform float timeDelta; // seconds the frame before took
uniform float frameRate; // frames per second, counted over half a second
uniform vec3 gravity; // m/s^2 along the phone's axes; 0 without the sensor
uniform int pointerCount; // fingers on the screen now, 0..10
uniform vec3 pointers[10]; // x, y in pixels of this picture, and pressure

// A flow that swirls but never piles ink up: the curl of slow noise.
// One octave of value noise is enough here and four times cheaper than
// fbm, which matters at every pixel of every frame.
vec2 flow(vec2 p) {
	float e = 0.02;
	float dx = valueNoise(p + vec2(e, 0.0)) - valueNoise(p - vec2(e, 0.0));
	float dy = valueNoise(p + vec2(0.0, e)) - valueNoise(p - vec2(0.0, e));
	return vec2(dy, -dx) / (2.0 * e);
}

void main(void) {
	float unit = min(resolution.x, resolution.y);
	vec2 p = gl_FragCoord.xy / unit;

	// Read and shape. Downhill is against gravity, along the phone's
	// axes; in landscape the screen's axes are the phone's turned.
	vec2 down = -gravity.xy / 9.81;
	if (resolution.x > resolution.y) {
		down = (gravity.x < 0.0 ? -1.0 : 1.0) * vec2(down.y, -down.x);
	}
	// Speed in pixels of this picture per sixtieth of a second. A frame
	// moves the ink by the time it took, so the ink flows as fast on a
	// screen that draws 120 frames a second as on one that draws 60.
	vec2 v = 0.6 * flow(2.0 * p + 0.02 * time) + 0.6 * down;
	v *= 60.0 * timeDelta;

	// Move: what is here now was a little upstream a frame ago.
	vec3 color = texture2D(ink, (gl_FragCoord.xy - v)) / resolution).rgb;
	// Fade by one step of the 8 bits a channel has, 15 times a second:
	// full ink is gone after about 17 s. A smaller step would round back
	// to the same value and never fade, so the step cannot be scaled by
	// timeDelta; how many frames lie between two steps comes from the
	// frame rate instead, every fourth at 60 frames a second.
	float between = max(1.0, floor(frameRate / 15.0 + 0.5));
	color = max(color - step(mod(float(frame), between), 0.5) / 255.0, 0.0);

	// Add. Two drops wander slowly and let ink out, so the paper never
	// empties; each finger adds more where it is.
	vec3 hue = palette(0.03 * time, vec3(0.5), vec3(0.4), vec3(1.0),
		vec3(0.0, 0.25, 0.5));
	for (int i = 0; i < 2; ++i) {
		float k = float(i) * 3.0;
		vec2 drop = resolution * (0.5 + 0.35 * vec2(sin(0.11 * time + k),
			cos(0.07 * time + 2.0 * k)));
		color += 0.04 * hue * exp(-pow(distance(gl_FragCoord.xy, drop) / (0.05 * unit), 2.0));
	}
	for (int i = 0; i < 10; ++i) {
		if (i >= pointerCount) {
			break;
		}
		color += 0.2 * hue.bgr * exp(-pow(distance(gl_FragCoord.xy, pointers[i].xy) / (0.04 * unit), 2.0));
	}

	gl_FragColor = vec4(min(color, 1.0), 1.0);
}

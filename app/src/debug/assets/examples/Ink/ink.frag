#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif

// The ink, at half size: soft ink needs no more. Before the first frame,
// and on every restart, it is what seed.frag draws.
#pragma se target scale=0.5 initial="seed.frag"
// This pass's own picture of the frame before, read between pixels as
// the ink moves less than a pixel a frame.
#pragma se input "ink.frag" frame=previous filter=linear
uniform sampler2D ink;

#include "../lib/noise.glsl"
#include "../lib/color.glsl"

uniform vec2 resolution; // size of this pass's picture in pixels
uniform float time; // seconds since load
uniform int frame; // frames drawn since load
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
	// Speed in pixels of this picture per frame.
	vec2 v = 0.6 * flow(2.0 * p + 0.02 * time) + 0.6 * down;

	// Move: what is here now was a little upstream a frame ago.
	vec3 color = texture2D(ink, (gl_FragCoord.xy - v) / resolution).rgb;
	// Fade by one step of the 8 bits a channel has, every fourth frame:
	// full ink is gone after about 17 s at 60 Hz. A smaller step would
	// round back to the same value and never fade.
	color = max(color - step(mod(float(frame), 4.0), 0.5) / 255.0, 0.0);

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

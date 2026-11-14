#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif

// The first ink, drawn once at the start and on every restart: soft
// clouds, so the paper is never blank. Same size as ink.frag.
#pragma se target scale=0.5

#include "../lib/noise.glsl"
#include "../lib/color.glsl"

uniform vec2 resolution; // size of this pass's picture in pixels

void main(void) {
	vec2 p = gl_FragCoord.xy / min(resolution.x, resolution.y);
	float cloud = smoothstep(0.45, 0.75, fbm(2.5 * p));
	vec3 hue = palette(0.4 * fbm(p + 7.0), vec3(0.5), vec3(0.4), vec3(1.0),
		vec3(0.0, 0.25, 0.5));
	gl_FragColor = vec4(0.8 * cloud * hue, 1.0);
}

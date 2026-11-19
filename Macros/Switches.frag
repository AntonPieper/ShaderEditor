#version 300 es
precision highp float;

uniform vec2 resolution;
uniform float time;
out vec4 fragColor;

// 2 x 2 samples per pixel; set AA to 1 and run to dim the other arm.
#define AA 2
// Remove the // in front to switch soft edges on.
// #define SOFT

float disc(vec2 p) {
#ifdef SOFT
	return smoothstep(0.5, 0.45, length(p));
#else
	return step(length(p), 0.5);
#endif
}

void main() {
	vec2 uv = (2.0 * gl_FragCoord.xy - resolution) /
		min(resolution.x, resolution.y);
	float v = 0.0;
#if AA > 1
	for (int i = 0; i < AA; ++i) {
		for (int j = 0; j < AA; ++j) {
			vec2 o = (vec2(float(i), float(j)) + 0.5) / float(AA) - 0.5;
			v += disc(uv + 2.0 * o / resolution.y);
		}
	}
	v /= float(AA * AA);
#else
	v = disc(uv);
#endif
#if 0
	// Old colours, kept for later.
	v = 1.0 - v;
#endif
	vec3 col = mix(vec3(0.1, 0.12, 0.2), vec3(0.95, 0.8, 0.4), v);
	// Only the driver knows whether it has this extension.
#ifdef GL_EXT_shader_framebuffer_fetch
	col *= 0.95;
#else
	col *= 1.0;
#endif
	// In ES 3.00 this one is always defined.
#ifdef GL_FRAGMENT_PRECISION_HIGH
	col += 0.03 * sin(time);
#else
	col -= 0.03;
#endif
	fragColor = vec4(col, 1.0);
}

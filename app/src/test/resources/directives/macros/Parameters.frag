#version 300 es
precision highp float;

uniform vec2 resolution;
uniform float time;
out vec4 fragColor;

// Function-like macros with several parameters, one inside another.
#define S(a, b, t) smoothstep(a, b, t)
#define RING(p, r, w) S(w, 0.0, abs(length(p) - (r)))
#define MIX3(a, b, c, t) mix(mix(a, b, S(0.0, 0.5, t)), c, \
	S(0.5, 1.0, t))

void main() {
	vec2 uv = (2.0 * gl_FragCoord.xy - resolution) /
		min(resolution.x, resolution.y);
	float pulse = 0.5 + 0.5 * sin(time);
	// One macro with three sets of arguments on one line.
	float rings = RING(uv, 0.3, 0.02) + RING(uv, 0.6, 0.03) + RING(uv, 0.9, 0.04 * pulse);
	vec3 col = MIX3(vec3(0.1, 0.2, 0.5), vec3(0.9, 0.4, 0.2),
		vec3(1.0, 0.9, 0.6), length(uv));
	fragColor = vec4(col * (0.2 + rings), 1.0);
}

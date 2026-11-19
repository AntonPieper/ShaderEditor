#version 300 es
precision highp float;

uniform vec2 resolution;
uniform float time;
out vec4 fragColor;

// Object-like macros built from others.
#define PI 3.14159265
#define TAU (2.0 * PI)
#define TURNS(t) ((t) * TAU)
// Three deep: SPIN uses ROT and TURNS, TURNS uses TAU, TAU uses PI.
#define ROT(a) mat2(cos(a), sin(a), -sin(a), cos(a))
#define SPIN(t) ROT(TURNS(t))

void main() {
	vec2 uv = (2.0 * gl_FragCoord.xy - resolution) /
		min(resolution.x, resolution.y);
	uv = SPIN(time * 0.05) * uv;
	// TURNS here is in the arguments of ROT.
	vec2 q = ROT(TURNS(0.125)) * uv;
	float d = abs(max(abs(q.x), abs(q.y)) - 0.5);
	float a = atan(uv.y, uv.x) / TAU + 0.5;
	vec3 col = mix(vec3(0.1, 0.15, 0.3), vec3(0.3, 0.5, 0.8), a);
	col += vec3(0.9, 0.6, 0.2) * smoothstep(0.02, 0.0, d);
	fragColor = vec4(col, 1.0);
}

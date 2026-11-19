#version 300 es
precision highp float;

uniform vec2 resolution;
uniform float time;
out vec4 fragColor;

// A body over three lines, joined by backslashes (ES 3.00).
#define CHECKER(p, n) \
	mod(floor((p).x * (n)) + \
		floor((p).y * (n)), 2.0)
// Macros that are whole statements.
#define CLAMP01(x) x = clamp(x, 0.0, 1.0)
#define SWAP(a, b) { vec3 t_ = a; a = b; b = t_; }

void main() {
	vec2 uv = gl_FragCoord.xy / resolution;
	float c = CHECKER(uv, 8.0);
	vec3 dark = vec3(0.15, 0.2, 0.3);
	vec3 light = vec3(0.8, 0.75, 0.6);
	if (fract(time * 0.25) > 0.5) SWAP(dark, light);
	vec3 col = mix(dark, light, c);
	col += 0.3 * sin(time + uv.x * 6.0);
	CLAMP01(col);
	fragColor = vec4(col, 1.0);
}

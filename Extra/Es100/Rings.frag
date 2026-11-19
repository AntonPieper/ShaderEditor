precision mediump float;

uniform vec2 resolution;

// GLSL ES 1.00, without a #version line: it runs on OpenGL ES 2.0 too.
#define S(a, b, t) smoothstep(a, b, t)
#define RING(p, r, w) S(w, 0.0, abs(length(p) - (r)))
#define ONE 0

void main() {
	vec2 uv = (2.0 * gl_FragCoord.xy - resolution) /
		min(resolution.x, resolution.y);
#if ONE
	float rings = RING(uv, 0.5, 0.05);
#else
	float rings = RING(uv, 0.3, 0.02) + RING(uv, 0.6, 0.03);
#endif
	gl_FragColor = vec4(vec3(0.2 + rings), 1.0);
}

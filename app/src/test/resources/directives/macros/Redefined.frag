#version 300 es
precision highp float;

uniform vec2 resolution;
uniform float time;
out vec4 fragColor;

#define BASE 0.5
#define SIZE (BASE * 0.6)
#define SHAPE(p) length(p)

// A circle, with the first SIZE and SHAPE.
float left(vec2 p) {
	return SHAPE(p) - SIZE;
}

#undef SIZE
#undef SHAPE
#define SIZE (BASE * 0.9)
#define SHAPE(p) max(abs((p).x), abs((p).y))

// A square, with the second.
float right(vec2 p) {
	return SHAPE(p) - SIZE;
}

void main() {
	vec2 uv = (2.0 * gl_FragCoord.xy - resolution) /
		min(resolution.x, resolution.y);
	float d = min(left(uv + vec2(0.5, 0.0)), right(uv - vec2(0.5, 0.0)));
	vec3 col = d < 0.0 ? vec3(0.9, 0.5, 0.2) : vec3(0.1, 0.15, 0.25);
	col *= 0.8 + 0.2 * cos(60.0 * d - 4.0 * time);
	fragColor = vec4(col, 1.0);
}

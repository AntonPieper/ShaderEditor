#version 300 es
precision highp float;

uniform vec2 resolution;
uniform float time;
out vec4 fragColor;

// This pass has its own switch: set RINGS to 0 for a soft disc.
#define RINGS 1

void main() {
	vec2 uv = (2.0 * gl_FragCoord.xy - resolution) /
		min(resolution.x, resolution.y);
	float d = length(uv);
#if RINGS
	float v = 0.5 + 0.5 * sin(d * 20.0 - time * 2.0);
#else
	float v = smoothstep(0.8, 0.0, d);
#endif
	fragColor = vec4(vec3(v), 1.0);
}

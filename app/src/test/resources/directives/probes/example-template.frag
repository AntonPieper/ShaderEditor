#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif

uniform vec2 resolution;
uniform float time;

// The app's template above, in ES 1.00, and a switch of its own.
#define STRIPES 1

void main(void) {
	vec2 uv = gl_FragCoord.xy / resolution.xy;
#if STRIPES
	float v = step(0.5, fract(uv.x * 10.0 + time));
#else
	float v = uv.x;
#endif
	gl_FragColor = vec4(vec3(v) * vec3(0.3, 0.6, 0.9), 1.0);
}

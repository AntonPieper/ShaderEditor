#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif

uniform vec2 resolution;
uniform float time;

// One pass: red grows to the right, green upwards, blue pulses.
void main(void) {
	vec2 uv = gl_FragCoord.xy / resolution;
	gl_FragColor = vec4(uv, 0.5 + 0.5 * sin(time), 1.0);
}

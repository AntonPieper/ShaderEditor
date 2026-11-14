#pragma se target format=rgba16f
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif

uniform vec2 resolution;

// Left half 4.0, right half 1.0. With the picture behind the code at
// any brightness limit, both halves must look the same: no pixel is
// brighter than white.
void main(void) {
	float v = gl_FragCoord.x < resolution.x * 0.5 ? 4.0 : 1.0;
	gl_FragColor = vec4(v, v, v, 1.0);
}

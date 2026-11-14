#pragma se target format=rgba16f
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif

uniform vec2 resolution;

// Red from 0 to 4 left to right, green 3 everywhere.
void main(void) {
	gl_FragColor = vec4(4.0 * gl_FragCoord.x / resolution.x, 3.0, 0.0, 1.0);
}

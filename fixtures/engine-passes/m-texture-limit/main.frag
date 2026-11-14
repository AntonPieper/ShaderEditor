#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif

#pragma se target size=16384x16
uniform vec2 resolution;

// A probe: a target 16384 wide is refused with the device's largest
// texture size in the message when that is smaller.
void main(void) {
	gl_FragColor = vec4(gl_FragCoord.x / resolution.x, 0.0, 0.0, 1.0);
}

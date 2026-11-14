#pragma se target format=rgba16f
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif

// 1000 in red and half of 1/255 in green, which rgba8 cannot hold.
void main(void) {
	gl_FragColor = vec4(1000.0, 0.5 / 255.0, 0.0, 1.0);
}

#pragma se target format=rgba16f initial="seed.frag"
#pragma se input "state.frag" frame=previous
uniform highp sampler2D previous;
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif

uniform vec2 resolution;

// Keeps what it starts as.
void main(void) {
	gl_FragColor = texture2D(previous, gl_FragCoord.xy / resolution);
}

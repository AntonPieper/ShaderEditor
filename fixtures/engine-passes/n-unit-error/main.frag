#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif

#pragma se input "blur.frag"
uniform sampler2D blur;
uniform vec2 resolution;

void main(void) {
	gl_FragColor = texture2D(blur, gl_FragCoord.xy / resolution);
}

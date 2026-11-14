#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif

#pragma se input "state.frag"
uniform highp sampler2D state;
uniform vec2 resolution;

// Green when state.frag still holds 1000 and half of 1/255 from its
// seed after the stretch, red when either was lost.
void main(void) {
	vec4 s = texture2D(state, gl_FragCoord.xy / resolution);
	bool kept = abs(s.r - 1000.0) < 1.0 &&
			abs(s.g - 0.5 / 255.0) < 0.25 / 255.0;
	gl_FragColor = kept ? vec4(0.0, 0.8, 0.0, 1.0) : vec4(0.9, 0.0, 0.0, 1.0);
}

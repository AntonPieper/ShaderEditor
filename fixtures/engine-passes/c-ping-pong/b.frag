#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif

#pragma se input "a.frag" frame=previous
uniform sampler2D a;
uniform vec2 resolution;

// a's previous value plus 2, modulo 256, in red.
void main(void) {
	float v = floor(texture2D(a, gl_FragCoord.xy / resolution).r * 255.0 +
			0.5);
	gl_FragColor = vec4(mod(v + 2.0, 256.0) / 255.0, 0.0, 0.0, 1.0);
}

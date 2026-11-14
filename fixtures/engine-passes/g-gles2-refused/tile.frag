#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif

uniform vec2 resolution;

// A red-green gradient with a white disc in the lower left.
void main(void) {
	vec2 uv = gl_FragCoord.xy / resolution;
	float disc = step(distance(uv, vec2(0.3, 0.25)), 0.15);
	gl_FragColor = vec4(mix(vec3(uv, 0.0), vec3(1.0), disc), 1.0);
}

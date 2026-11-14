#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif

uniform vec2 resolution;
uniform float time;
uniform int frame;

// A white cross on dark blue, drawn on reset only, with time and frame
// at zero; all red if it is drawn with anything else.
void main(void) {
	if (frame != 0 || time != 0.0) {
		gl_FragColor = vec4(1.0, 0.0, 0.0, 1.0);
		return;
	}
	vec2 d = abs(gl_FragCoord.xy - resolution * vec2(0.5, 0.45));
	float w = 0.02 * resolution.x;
	bool cross = (d.x < w && d.y < resolution.y * 0.2) ||
			(d.y < w && d.x < resolution.x * 0.2);
	gl_FragColor = cross ? vec4(1.0) : vec4(0.0, 0.0, 0.3, 1.0);
}

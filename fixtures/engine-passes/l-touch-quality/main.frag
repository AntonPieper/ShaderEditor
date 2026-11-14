#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif

#pragma se target size=256x256
uniform vec2 resolution;
uniform vec2 touch;
uniform vec2 touchStart;

// A yellow ring at touch and a cyan dot at touchStart, kept after the
// finger lifts, in the pixels of a fixed 256x256 target, over a grid.
void main(void) {
	vec2 p = gl_FragCoord.xy;
	vec3 c = vec3(0.15) * mod(floor(p.x / 16.0) + floor(p.y / 16.0), 2.0);
	float d = distance(p, touch);
	if (d > 8.0 && d < 12.0) {
		c = vec3(1.0, 1.0, 0.0);
	}
	if (distance(p, touchStart) < 4.0) {
		c = vec3(0.0, 1.0, 1.0);
	}
	gl_FragColor = vec4(c, 1.0);
}

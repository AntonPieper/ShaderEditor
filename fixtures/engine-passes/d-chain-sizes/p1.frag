#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif

#pragma se target size=64x64

uniform vec2 resolution;
uniform float time;
uniform vec2 touch;
uniform int pointerCount;

// A checkerboard of 4-pixel cells of this target in color, a yellow
// line at a height that follows time, and a yellow ring around the
// finger, all in the pixels of this target.
vec3 own(vec3 color) {
	vec2 cell = floor(gl_FragCoord.xy / 4.0);
	vec3 c = color * mod(cell.x + cell.y, 2.0);
	float line = (0.5 + 0.4 * sin(time)) * resolution.y;
	if (abs(gl_FragCoord.y - line) < resolution.y * 0.004 + 0.5) {
		c = vec3(1.0, 1.0, 0.0);
	}
	float r = 0.08 * resolution.x;
	float d = distance(gl_FragCoord.xy, touch);
	if (pointerCount > 0 && d > r * 0.75 && d < r) {
		c = vec3(1.0, 1.0, 0.0);
	}
	return c;
}

// Which of four vertical strips this pixel is in, 0 to 3.
float strip() {
	return floor(gl_FragCoord.x / resolution.x * 4.0);
}

void main(void) {
	gl_FragColor = vec4(own(vec3(1.0, 0.0, 0.0)), 1.0);
}

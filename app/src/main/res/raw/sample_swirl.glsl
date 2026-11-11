#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif

uniform vec2 resolution; // size of the surface in pixels
uniform vec2 touch; // last touch in pixels; 0,0 before any; kept after the finger lifts
uniform vec2 touchStart; // where the last touch went down, in pixels; 0,0 before any
uniform sampler2D noise; // the bundled grey noise image, 256 x 256, repeats

void main(void) {
	float unit = min(resolution.x, resolution.y);
	vec2 p = (gl_FragCoord.xy - 0.5 * resolution) / unit;
	float px = 1.0 / unit;

	// Read and shape. The drag runs from where the finger went down to
	// where it is now. Its length is the swirl's radius, and dragging to
	// the right or left turns it one way or the other. The swirl stays
	// after the finger lifts and is gone with the next touch.
	vec2 center = (touchStart - 0.5 * resolution) / unit;
	vec2 drag = (touch - touchStart) / unit;
	float radius = length(drag);
	float spin = drag.x < 0.0 ? 1.0 : -1.0;

	// Distort. Turn each point about the centre, half a turn there and
	// less further out, nothing beyond the radius.
	vec2 q = p - center;
	float fall = smoothstep(radius, 0.0, length(q));
	float a = spin * 3.1415927 * fall * fall;
	vec2 uv = center + mat2(cos(a), sin(a), -sin(a), cos(a)) * q;

	// Draw the image at the distorted coordinates. The noise is enlarged
	// so its grain becomes soft blobs, then given colour; a grid drawn in
	// the same coordinates shows how they bend.
	float v = 0.6 * texture2D(noise, uv * 0.09).r + 0.4 * texture2D(noise, uv * 0.2 + 0.5).r;
	v = smoothstep(0.1, 0.55, v);
	vec3 color = mix(vec3(0.05, 0.08, 0.17), vec3(0.12, 0.45, 0.55), v);
	color = mix(color, vec3(0.95, 0.8, 0.6), 0.8 * smoothstep(0.75, 1.0, v));
	vec2 cell = abs(fract(uv * 8.0 + 0.5) - 0.5) / 8.0;
	color += 0.15 * smoothstep(1.5 * px, 0.0, min(cell.x, cell.y));

	// A faint ring where the drag began.
	color += 0.3 * step(px, radius) * smoothstep(1.5 * px, 0.0, abs(length(q) - 0.02));

	gl_FragColor = vec4(color, 1.0);
}

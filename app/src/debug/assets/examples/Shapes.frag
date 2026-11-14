#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif

uniform vec2 resolution; // size of the surface in pixels
uniform float time; // seconds since load
uniform vec2 mouse; // last touch, 0..1 across the surface; 0,0 before any touch

// 1 inside the circle, 0 outside, blending over 2 * soft at the edge.
float disc(vec2 p, vec2 center, float radius, float soft) {
	return smoothstep(radius + soft, radius - soft, distance(p, center));
}

void main(void) {
	// Origin in the middle, 1.0 = the short side of the screen.
	// Both axes use the same unit, so circles stay round when rotated.
	float unit = min(resolution.x, resolution.y);
	vec2 p = (gl_FragCoord.xy - 0.5 * resolution) / unit;
	float px = 1.0 / unit;

	// The finger, mapped into the same space. Centered until touched.
	vec2 finger = (mouse - 0.5) * resolution / unit;
	if (mouse == vec2(0.0)) {
		finger = vec2(0.0);
	}

	// The clock, mapped to an angle: one turn every 6 seconds.
	float angle = time * 6.2831853 / 6.0;
	vec2 moon = finger + 0.3 * vec2(cos(angle), sin(angle));

	// Grid lines every 0.1 units and the two axes through the origin.
	vec2 cell = abs(fract(p * 10.0 + 0.5) - 0.5) * 0.1;
	vec3 color = vec3(0.06, 0.07, 0.11);
	color += 0.06 * smoothstep(px, 0.0, min(cell.x, cell.y));
	color += 0.12 * smoothstep(px, 0.0, min(abs(p.x), abs(p.y)));

	// A ring is the distance to a circle's outline.
	float orbit = abs(distance(p, finger) - 0.3);
	color = mix(color, vec3(0.3, 0.38, 0.6), smoothstep(1.5 * px, 0.5 * px, orbit));

	// A wide soft edge glows; a one pixel soft edge just looks smooth.
	color = mix(color, vec3(1.0, 0.7, 0.3), disc(p, finger, 0.1, 0.05));
	color = mix(color, vec3(0.65, 0.85, 1.0), disc(p, moon, 0.035, px));

	gl_FragColor = vec4(color, 1.0);
}

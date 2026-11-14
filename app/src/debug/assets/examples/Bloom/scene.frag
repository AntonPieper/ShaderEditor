#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif

uniform vec2 resolution; // size of this pass's picture in pixels
uniform float time; // seconds since load
uniform vec2 touch; // last touch in pixels; kept after the finger lifts
uniform int pointerCount; // fingers on the screen now, 0..10

// 1 inside a disc of radius r around c, with a soft edge.
float lamp(vec2 p, vec2 c, float r) {
	return smoothstep(r + 0.004, r - 0.004, distance(p, c));
}

void main(void) {
	float unit = min(resolution.x, resolution.y);
	vec2 p = (gl_FragCoord.xy - 0.5 * resolution) / unit;
	vec3 warm = vec3(1.0, 0.8, 0.55);
	vec3 color = vec3(0.02, 0.025, 0.04);

	// Five lamps from dim on the left to full brightness on the right.
	// Only those above the threshold of bright.frag get a halo.
	for (int i = 0; i < 5; ++i) {
		float f = float(i);
		float level = 0.2 + 0.2 * f;
		color = mix(color, level * warm, lamp(p, vec2(0.16 * (f - 2.0), 0.15), 0.03));
	}

	// A full-bright light that circles below them, and one under a
	// finger while it is down.
	vec2 orbit = vec2(0.3 * cos(0.7 * time), -0.2 + 0.08 * sin(1.4 * time));
	color = mix(color, vec3(0.55, 0.8, 1.0), lamp(p, orbit, 0.025));
	if (pointerCount > 0) {
		vec2 finger = (touch - 0.5 * resolution) / unit;
		color = mix(color, vec3(1.0, 0.55, 0.85), lamp(p, finger, 0.04));
	}

	gl_FragColor = vec4(color, 1.0);
}

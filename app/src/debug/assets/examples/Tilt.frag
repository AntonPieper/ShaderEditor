#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif

uniform vec2 resolution; // size of the surface in pixels
uniform vec3 gravity; // m/s^2, about 9.81 long, points up; 0 without a sensor
uniform vec3 linear; // m/s^2, acceleration without gravity; near 0 at rest

// Tilt 0..1 (flat..on edge) to a distance from the centre, 0..0.33:
// steep at first so small tilts show, then easing towards the rim.
float soft(float tilt) {
	return 0.33 * (1.0 - exp(-5.0 * tilt));
}

void main(void) {
	float unit = min(resolution.x, resolution.y);
	vec2 p = (gl_FragCoord.xy - 0.5 * resolution) / unit;
	float px = 1.0 / unit;

	// Read. Both sensors use the phone's own axes (x to the right edge,
	// y to the top edge in portrait), which do not turn with the screen.
	// In landscape, the edge gravity points to says which way it turned.
	vec2 g = gravity.xy;
	vec2 a = linear.xy;
	if (resolution.x > resolution.y) {
		float turn = gravity.x < 0.0 ? -1.0 : 1.0;
		g = turn * vec2(-g.y, g.x);
		a = turn * vec2(-a.y, a.x);
	}

	// Shape. Downhill is opposite to gravity; the length of the tilt
	// vector is the sine of the tilt angle. A shake moves the phone and
	// the ball lags behind it for a moment.
	vec2 tilt = -g / 9.81;
	vec2 ball = normalize(tilt + 1e-4) * soft(length(tilt)) - 0.015 * a;
	ball /= max(1.0, length(ball) / 0.36);

	// Draw a dish with rings at 10, 20 and 30 degrees of tilt.
	float r = length(p);
	vec3 color = mix(vec3(0.05, 0.06, 0.09), vec3(0.12, 0.14, 0.2),
		smoothstep(0.43 + px, 0.43, r) * (1.0 - 0.6 * r));
	color += 0.25 * smoothstep(1.5 * px, 0.0, abs(r - 0.43));
	for (int i = 1; i <= 3; i++) {
		float ring = soft(sin(radians(10.0 * float(i))));
		color += 0.07 * smoothstep(px, 0.0, abs(r - ring));
	}

	// A soft shadow, then the ball, lit from the top left.
	color *= 1.0 - 0.5 * smoothstep(0.1, 0.05, distance(p, ball + vec2(0.012, -0.018)));
	vec2 q = (p - ball) / 0.07;
	vec3 n = vec3(q, sqrt(max(0.0, 1.0 - dot(q, q))));
	float light = max(0.0, dot(n, normalize(vec3(-0.4, 0.5, 0.8))));
	vec3 shade = vec3(1.0, 0.55, 0.2) * (0.2 + 0.8 * light) + 0.6 * pow(light, 24.0);
	color = mix(color, shade, smoothstep(1.0 + px / 0.07, 1.0, length(q)));

	gl_FragColor = vec4(color, 1.0);
}

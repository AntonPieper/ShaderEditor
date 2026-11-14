#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif

// ftime repeats every FTIME_PERIOD seconds, so the waves never drift.
#define FTIME_PERIOD 8.0

uniform vec2 resolution; // size of the surface in pixels
uniform float ftime; // -1..1, once every FTIME_PERIOD seconds
uniform float battery; // charge 0..1, refreshed every 10 seconds
uniform int powerConnected; // 1 while a charger is plugged in, else 0

void main(void) {
	float unit = min(resolution.x, resolution.y);
	vec2 uv = gl_FragCoord.xy / resolution;
	float x = gl_FragCoord.x / unit;
	float px = 1.0 / resolution.y;

	// Read.
	float charge = clamp(battery, 0.0, 1.0);
	float charging = float(powerConnected);

	// Shape. The charge is the height of the liquid, 0..1 of the screen.
	// Two slow waves make the surface; their whole numbers of turns per
	// loop keep them seamless. While charging, a pulse rises from the
	// bottom every 2 seconds and lifts the surface as it arrives.
	float loop = ftime * 0.5 + 0.5;
	float pulse = fract(loop * 4.0);
	float wave = 0.006 * sin(6.2831853 * 2.0 * loop + 5.0 * x) +
		0.004 * sin(6.2831853 * 3.0 * loop - 9.0 * x);
	float lift = 0.02 * charging * smoothstep(0.75, 0.95, pulse) * smoothstep(1.0, 0.95, pulse);
	float level = charge + wave + lift;

	// Draw. Red below 15 %, turning green by 40 %; deeper is darker.
	vec3 tint = mix(vec3(0.95, 0.3, 0.2), vec3(0.2, 0.85, 0.65), smoothstep(0.15, 0.4, charge));
	vec3 color = mix(vec3(0.03, 0.04, 0.07), vec3(0.07, 0.08, 0.12), uv.y);
	float back = charge + 0.008 + 0.006 * sin(6.2831853 * loop - 4.0 * x);
	color = mix(color, tint * 0.22, smoothstep(-px, px, back - uv.y));
	float depth = level - uv.y;
	vec3 liquid = tint * (0.25 + 0.55 * exp(-4.0 * depth));
	liquid += tint * charging * 0.5 * exp(-abs(uv.y - pulse * charge) * 30.0);
	color = mix(color, liquid, smoothstep(-px, px, depth));
	color += tint * 0.6 * smoothstep(3.0 * px, 0.0, abs(depth));

	// Faint marks every 25 % at the right edge, to read the level by.
	float mark = abs(fract(uv.y * 4.0 + 0.5) - 0.5) / 4.0;
	color += 0.12 * smoothstep(2.0 * px, 0.0, mark) * step(1.0 - 0.06 * unit / resolution.x, uv.x);

	gl_FragColor = vec4(color, 1.0);
}

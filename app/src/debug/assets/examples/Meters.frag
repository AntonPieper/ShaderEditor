#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif

uniform vec2 resolution; // size of the surface in pixels
uniform float light; // lux, about 1 at night indoors, 100000 in sunlight; 0 without a sensor
uniform float pressure; // hPa, about 1013 at sea level, less higher up; 0 without a sensor
uniform float proximity; // cm, 0 when covered; many phones know only 0 and about 5

// A bar at x from y = -h (level 0) to y = h (level 1),
// with `ticks` divisions down its left side.
vec3 bar(vec3 color, vec2 p, float x, float h, float level, float ticks,
		vec3 tint, float px) {
	vec2 q = vec2(p.x - x, (p.y + h) / (2.0 * h));
	float scale = 2.0 * h;
	float box = smoothstep(px, 0.0, abs(q.x) - 0.05) *
		smoothstep(px, 0.0, (abs(q.y - 0.5) - 0.5) * scale);
	float fill = clamp(level, 0.0, 1.0);
	color = mix(color, vec3(0.1, 0.11, 0.15), box);
	color = mix(color, tint * (0.45 + 0.55 * q.y), box * step(q.y, fill));
	float tick = abs(fract(q.y * ticks + 0.5) - 0.5) / ticks * scale;
	color += 0.4 * smoothstep(1.5 * px, 0.0, tick) * step(abs(q.x + 0.08), 0.015) *
		step(abs(q.y - 0.5), 0.5 + px);
	return mix(color, vec3(1.0), box * smoothstep(1.5 * px, 0.0, abs(q.y - fill) * scale));
}

void main(void) {
	float unit = min(resolution.x, resolution.y);
	vec2 p = (gl_FragCoord.xy - vec2(0.5, 0.45) * resolution) / unit;
	float px = 1.0 / unit;
	float h = 0.32 * resolution.y / unit;

	// Read and shape: one line per bar turns a value into 0..1.
	// To meter another uniform, declare it and change one of these lines.
	// Light spans many orders of magnitude, so the bar is logarithmic:
	// each of its 5 ticks is ten times brighter, up to 100000 lux.
	float a = log(1.0 + light) / log(10.0) / 5.0;
	// Weather moves pressure by a few hPa: 960..1060, a tick per 10 hPa.
	float b = (pressure - 960.0) / 100.0;
	// Distance 0..10 cm, a tick per cm; covering the sensor empties it.
	float c = proximity / 10.0;

	// Draw. Without a sensor the value is 0, so its bar stays empty.
	vec3 color = mix(vec3(0.03, 0.04, 0.06), vec3(0.06, 0.07, 0.1), p.y / h * 0.5 + 0.5);
	color = bar(color, p, -0.3, h, a, 5.0, vec3(1.0, 0.8, 0.35), px);
	color = bar(color, p, 0.0, h, b, 10.0, vec3(0.4, 0.7, 1.0), px);
	color = bar(color, p, 0.3, h, c, 10.0, vec3(0.45, 0.95, 0.6), px);

	gl_FragColor = vec4(color, 1.0);
}

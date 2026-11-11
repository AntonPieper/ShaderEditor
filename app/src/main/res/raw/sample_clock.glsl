#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif

uniform vec2 resolution; // size of the surface in pixels
uniform vec3 daytime; // hour 0..23, minute 0..59, second 0..59; refreshed once a second
uniform vec4 date; // year, month 0..11 (0 is January), day 1..31, seconds since midnight
uniform int nightMode; // 1 if the system uses the dark theme when the shader loads

// Distance to a hand turned `turns` clockwise from 12, with a short tail.
float hand(vec2 p, float turns, float len) {
	float a = 6.2831853 * turns;
	vec2 dir = vec2(sin(a), cos(a));
	return length(p - dir * clamp(dot(p, dir), -0.04, len));
}

void main(void) {
	float unit = min(resolution.x, resolution.y);
	vec2 p = (gl_FragCoord.xy - 0.5 * resolution) / unit;
	float px = 1.0 / unit;

	// Read and shape. Each unit becomes a fraction of a turn. A slower hand
	// also carries the faster units, so the hour hand creeps between marks.
	float second = daytime.z / 60.0;
	float minute = (daytime.y + second) / 60.0;
	float hour = (mod(daytime.x, 12.0) + minute) / 12.0;
	float month = mod(date.y + 1.0, 12.0); // the mark for its number, 0 at 12

	bool night = nightMode == 1;
	vec3 paper = night ? vec3(0.08, 0.09, 0.13) : vec3(0.93, 0.91, 0.86);
	vec3 ink = night ? vec3(0.62, 0.66, 0.75) : vec3(0.13, 0.14, 0.18);
	vec3 accent = night ? vec3(0.95, 0.5, 0.3) : vec3(0.85, 0.25, 0.15);

	// Draw the face with twelve marks; this month's mark is in colour.
	float r = length(p);
	float turn = fract(atan(p.x, p.y) / 6.2831853);
	vec3 color = mix(night ? vec3(0.02, 0.02, 0.03) : vec3(0.2, 0.22, 0.26), paper, smoothstep(0.45 + px, 0.45, r));
	float k = floor(turn * 12.0 + 0.5);
	float gap = abs(fract(turn * 12.0 + 0.5) - 0.5) / 12.0 * 6.2831853 * r;
	float mark = smoothstep(px, 0.0, gap - 0.006) * step(0.29, r) * step(r, 0.34);
	color = mix(color, abs(k - month) < 0.5 ? accent : ink, mark);

	// A dot per day of the month around the rim: done, today, to come.
	float slot = floor(turn * 31.0);
	float c = (slot + 0.5) / 31.0 * 6.2831853;
	float today = step(abs(slot + 1.0 - date.z), 0.5);
	float size = mix(0.005, 0.011, today);
	float bead = smoothstep(px, 0.0, distance(p, 0.405 * vec2(sin(c), cos(c))) - size);
	color = mix(color, mix(ink, accent, today), bead * (0.3 + 0.7 * step(slot + 1.0, date.z)));

	// Hands: hour, minute, then the second hand in colour.
	color = mix(color, ink, smoothstep(px, 0.0, hand(p, hour, 0.19) - 0.014));
	color = mix(color, ink, smoothstep(px, 0.0, hand(p, minute, 0.28) - 0.009));
	color = mix(color, accent, smoothstep(px, 0.0, hand(p, second, 0.31) - 0.003));
	color = mix(color, accent, smoothstep(px, 0.0, r - 0.014));

	gl_FragColor = vec4(color, 1.0);
}

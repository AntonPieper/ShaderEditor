#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif

uniform vec2 resolution; // size of the surface in pixels
uniform float time; // seconds since load
uniform int notificationCount; // notifications shown now; 0 without access
uniform float lastNotificationTime; // seconds since the newest arrived; NaN if none

void main(void) {
	float unit = min(resolution.x, resolution.y);
	vec2 p = (gl_FragCoord.xy - 0.5 * resolution) / unit;
	float px = 1.0 / unit;

	// Read and shape. Twenty slots; with more, all twenty are lit.
	float count = min(float(notificationCount), 20.0);
	// NaN means none has arrived since access was granted. GLSL ES 1.00
	// has no isnan(), and a compiler may fold t != t to false. But every
	// comparison with NaN is false, so asking for t >= 0 rejects it.
	float t = lastNotificationTime;
	bool none = !(t >= 0.0);
	// A pulse that is 1 when one arrives and fades out over three seconds.
	float pulse = none ? 0.0 : exp(-1.2 * t);

	// Draw a dot in each of the slots around the centre: the nearest slot
	// is found from the angle, so there is no loop.
	float r = length(p);
	vec3 color = mix(vec3(0.09, 0.08, 0.12), vec3(0.03, 0.03, 0.05), min(1.0, 1.6 * r));
	float turn = fract(atan(p.x, p.y) / 6.2831853);
	float slot = mod(floor(turn * 20.0 + 0.5), 20.0);
	float a = slot / 20.0 * 6.2831853;
	float lit = step(slot + 0.5, count);
	float newest = lit * step(count - 1.5, slot);
	float size = mix(0.012, 0.02, lit) + 0.025 * newest * pulse;
	float dist = distance(p, 0.32 * vec2(sin(a), cos(a)));
	vec3 warm = vec3(1.0, 0.68, 0.3);
	vec3 bead = mix(vec3(0.18, 0.19, 0.26), mix(warm, vec3(1.0), newest * pulse), lit);
	color += lit * warm * 0.3 * exp(-dist / 0.012);
	color = mix(color, bead, smoothstep(px, 0.0, dist - size));

	// The hub: a grey outline that breathes while nothing has arrived,
	// warm once something has, and filled by the pulse.
	float hub = r - 0.1;
	float breathe = none ? 0.5 + 0.5 * sin(1.5 * time) : 1.0;
	vec3 rim = none ? vec3(0.3, 0.32, 0.42) : warm;
	color = mix(color, rim, (0.4 + 0.6 * breathe) * smoothstep(px, 0.0, abs(hub) - 0.006));
	color += pulse * mix(warm, vec3(1.0), 0.5 * pulse) * smoothstep(px, 0.0, hub);

	// A ring that leaves the hub when a notification arrives.
	color += pulse * warm * smoothstep(3.0 * px, 0.0, abs(r - 0.1 - 0.3 * (1.0 - pulse)));

	gl_FragColor = vec4(color, 1.0);
}

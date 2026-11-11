#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif

uniform vec2 resolution; // size of the surface in pixels
uniform vec3 orientation; // radians; x: azimuth -pi..pi, 0 facing north, pi/2 east
uniform vec3 magnetic; // microtesla per axis; 0 without a sensor
uniform float inclination; // radians the field rises above level, -pi/2..pi/2

float line(vec2 p, vec2 a, vec2 b) {
	vec2 ab = b - a;
	return length(p - a - ab * clamp(dot(p - a, ab) / dot(ab, ab), 0.0, 1.0));
}

void main(void) {
	float unit = min(resolution.x, resolution.y);
	vec2 p = (gl_FragCoord.xy - 0.5 * resolution) / unit;
	float px = 1.0 / unit;
	vec2 along = resolution.x > resolution.y ? vec2(1.0, 0.0) : vec2(0.0, -1.0);
	vec2 dial = p + 0.2 * along;
	vec2 meter = p - 0.45 * along - vec2(0.17, 0.0);
	vec2 dip = p - 0.45 * along + vec2(0.17, 0.0);

	// Shape. North lies the azimuth back the other way, so the needle
	// turns against the phone. Earth's field is 25..65 microtesla, so
	// 0..100 fills the meter; past 80, something nearby is magnetic.
	// North of the magnetic equator the field points into the ground,
	// so the inclination is negative there: about -1.1 in central Europe.
	float a = -orientation.x;
	vec2 north = vec2(sin(a), cos(a));
	vec2 q = vec2(dot(dial, vec2(north.y, -north.x)), dot(dial, north));
	float field = length(magnetic) / 100.0;
	vec2 slope = vec2(cos(inclination), sin(inclination));

	// The dial turns with north; ticks every 30 degrees, red tip north.
	float r = length(q);
	vec3 color = vec3(0.05, 0.06, 0.09) + 0.07 * smoothstep(0.32 + px, 0.32, r);
	float tick = abs(fract(atan(q.x, q.y) / 6.2831853 * 12.0 + 0.5) - 0.5) * 0.52 * r;
	color += 0.5 * smoothstep(1.5 * px, 0.0, tick) * step(0.27, r) * step(r, 0.31);
	float width = 0.035 * (1.0 - abs(q.y) / 0.26);
	float needle = smoothstep(px, 0.0, abs(q.x) - width) * step(abs(q.y), 0.26);
	color = mix(color, q.y > 0.0 ? vec3(0.95, 0.25, 0.2) : vec3(0.85), needle);

	// Field strength fills a ring clockwise; the Earth band is lighter.
	float f = fract(atan(meter.x, meter.y) / 6.2831853);
	float ring = smoothstep(px, 0.0, abs(length(meter) - 0.1) - 0.012);
	vec3 fill = field > 0.8 ? vec3(1.0, 0.35, 0.25) : vec3(0.35, 0.8, 1.0);
	color += ring * (0.08 + 0.1 * step(0.25, f) * step(f, 0.65));
	color = mix(color, fill, ring * step(f, field));

	// The dip seen from the side, north to the right; red end along the field.
	color += 0.15 * smoothstep(px, 0.0, line(dip, vec2(-0.12, 0.0), vec2(0.12, 0.0)));
	color = mix(color, vec3(0.35, 0.8, 1.0),
		smoothstep(px, 0.0, line(dip, -0.11 * slope, 0.11 * slope) - 0.006));
	color = mix(color, vec3(0.95, 0.25, 0.2), smoothstep(0.02 + px, 0.02, distance(dip, 0.11 * slope)));

	gl_FragColor = vec4(color, 1.0);
}

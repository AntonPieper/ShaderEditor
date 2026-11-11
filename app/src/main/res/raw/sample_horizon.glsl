#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif

// What keeps it cheap: per pixel, a sky gradient, two discs, four hashes
// and three ridge lines of three sines each. No loops, no textures and no
// noise octaves, so it can run all day as a wallpaper.

uniform vec2 resolution; // size of the surface in pixels
uniform vec2 offset; // home screen page, x 0..1 from the first page to the last; 0 outside a live wallpaper
uniform vec3 daytime; // hour 0..23, minute 0..59, second 0..59; refreshed once a second
uniform int nightMode; // 1 if the system uses the dark theme when the shader loads

// 0..1 from a cell position.
float hash(vec2 c) {
	return fract(sin(dot(c, vec2(27.17, 91.31))) * 41231.7);
}

// A ridge line, -1..1: three sines whose wavelengths do not line up.
float ridge(float x, float seed) {
	return 0.5 * sin(1.7 * x + seed) + 0.3 * sin(4.3 * x + 2.1 * seed) + 0.2 * sin(9.7 * x - seed);
}

// Fills everything below a ridge with `tint`, paler towards its foot
// where mist lies in the valley, with a faint rim of sky light on top.
vec3 layer(vec3 color, vec2 p, float base, float height, float seed,
		vec3 tint, vec3 mist, float px) {
	float top = base + height * ridge(p.x, seed);
	vec3 fill = mix(tint, mist, 0.55 * smoothstep(top - 0.02, top - 0.4, p.y));
	fill = mix(fill, mist, 0.25 * smoothstep(top - 0.025, top, p.y));
	return mix(color, fill, smoothstep(top + px, top - px, p.y));
}

void main(void) {
	float unit = min(resolution.x, resolution.y);
	vec2 p = (gl_FragCoord.xy - 0.5 * resolution) / unit;
	float px = 1.0 / unit;
	float top = 0.5 * resolution.y / unit;
	float ground = -0.2 * top;
	float above = clamp((p.y - ground) / (top - ground), 0.0, 1.0);

	// Read and shape the clock: the sun's height, -1 at midnight, 0 at 6
	// and 18, 1 at noon. Real sunrise moves with the seasons; a wallpaper
	// can ignore that.
	float hour = daytime.x + daytime.y / 60.0;
	float arc = (hour - 6.0) / 12.0 * 3.1415927;
	float sun = sin(arc);
	float day = smoothstep(-0.1, 0.35, sun);
	float dusk = exp(-abs(sun) / 0.15);

	// Read and shape the page: each layer moves by its own share of a
	// swipe across all pages. The sky barely moves, the near hills most;
	// that difference is the depth.
	float page = offset.x;

	// Sky: night blue to day blue, warm near the horizon at dawn and dusk.
	vec3 low = mix(vec3(0.12, 0.15, 0.28), vec3(0.62, 0.78, 0.92), day);
	low = mix(low, vec3(0.98, 0.55, 0.35), 0.85 * dusk);
	vec3 high = mix(vec3(0.015, 0.02, 0.07), vec3(0.17, 0.38, 0.78), day);
	high = mix(high, vec3(0.22, 0.2, 0.42), 0.6 * dusk);
	vec3 color = mix(low, high, sqrt(above));

	// Stars at night, and the sun or the moon on an arc over the hills.
	vec2 sky = p + vec2(0.04 * page, 0.0);
	vec2 cell = floor(sky * 28.0);
	vec2 star = (cell + 0.2 + 0.6 * vec2(hash(cell + 3.1), hash(cell + 7.7))) / 28.0;
	color += smoothstep(0.05, -0.15, sun) * step(0.93, hash(cell)) * smoothstep(1.8 * px, 0.0, distance(sky, star)) * above;
	vec2 orbit = vec2(-0.42 * resolution.x / unit, 0.6 * (top - ground));
	vec2 sunAt = vec2(0.0, ground) + orbit * vec2(cos(arc), sun);
	vec2 moonAt = vec2(0.0, ground) - orbit * vec2(cos(arc), sun);
	float ds = distance(sky, sunAt);
	color += vec3(1.0, 0.8, 0.5) * (smoothstep(px, 0.0, ds - 0.05) + 0.35 * exp(-ds / 0.12));
	float dm = distance(sky, moonAt);
	color += (1.0 - day) * vec3(0.6, 0.7, 0.9) * 0.15 * exp(-dm / 0.1);
	color = mix(color, vec3(0.85, 0.88, 0.95), (1.0 - day) * smoothstep(px, 0.0, dm - 0.035));

	// Three layers of hills, far to near, darker and less misty as they near.
	vec3 deep = mix(vec3(0.02, 0.025, 0.05), vec3(0.06, 0.13, 0.15), day);
	deep = mix(deep, vec3(0.12, 0.06, 0.12), 0.5 * dusk);
	// The far range is drawn narrower, so it has more peaks; the nearest
	// hill has no mist and darkens towards the bottom, behind the icons.
	color = layer(color, vec2(2.2 * (p.x + 0.15 * page), p.y), ground + 0.1, 0.09, 1.0, mix(low, deep, 0.35), low, px);
	color = layer(color, vec2(p.x + 0.4 * page, p.y), ground - 0.04, 0.1, 4.0, mix(low, deep, 0.65), low, px);
	color = layer(color, vec2(0.8 * (p.x + 0.9 * page), p.y), ground - 0.2, 0.12, 9.0, deep, 0.5 * deep, px);

	// The dark theme asks for a quieter picture: dimmer, cooler and less
	// saturated, so white icons and text stay readable over it.
	if (nightMode == 1) {
		float grey = dot(color, vec3(0.3, 0.55, 0.15));
		color = mix(color, grey * vec3(0.7, 0.75, 1.0), 0.4) * 0.75;
	}

	// A little noise against banding in the smooth gradients.
	color += (hash(gl_FragCoord.xy) - 0.5) / 255.0;
	gl_FragColor = vec4(color, 1.0);
}

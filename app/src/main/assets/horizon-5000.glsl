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

// copy 1

// 0..1 from a cell position.
float hash_1(vec2 c) {
	return fract(sin(dot(c, vec2(27.17, 91.31))) * 41231.7);
}

// A ridge_1 line, -1..1: three sines whose wavelengths do not line up.
float ridge_1(float x, float seed) {
	return 0.5 * sin(1.7 * x + seed) + 0.3 * sin(4.3 * x + 2.1 * seed) + 0.2 * sin(9.7 * x - seed);
}

// Fills everything below a ridge_1 with `tint`, paler towards its foot
// where mist lies in the valley, with a faint rim of sky light on top.
vec3 layer_1(vec3 color, vec2 p, float base, float height, float seed,
		vec3 tint, vec3 mist, float px) {
	float top = base + height * ridge_1(p.x, seed);
	vec3 fill = mix(tint, mist, 0.55 * smoothstep(top - 0.02, top - 0.4, p.y));
	fill = mix(fill, mist, 0.25 * smoothstep(top - 0.025, top, p.y));
	return mix(color, fill, smoothstep(top + px, top - px, p.y));
}

void main_1(void) {
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

	// Read and shape the page: each layer_1 moves by its own share of a
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
	vec2 star = (cell + 0.2 + 0.6 * vec2(hash_1(cell + 3.1), hash_1(cell + 7.7))) / 28.0;
	color += smoothstep(0.05, -0.15, sun) * step(0.93, hash_1(cell)) * smoothstep(1.8 * px, 0.0, distance(sky, star)) * above;
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
	color = layer_1(color, vec2(2.2 * (p.x + 0.15 * page), p.y), ground + 0.1, 0.09, 1.0, mix(low, deep, 0.35), low, px);
	color = layer_1(color, vec2(p.x + 0.4 * page, p.y), ground - 0.04, 0.1, 4.0, mix(low, deep, 0.65), low, px);
	color = layer_1(color, vec2(0.8 * (p.x + 0.9 * page), p.y), ground - 0.2, 0.12, 9.0, deep, 0.5 * deep, px);

	// The dark theme asks for a quieter picture: dimmer, cooler and less
	// saturated, so white icons and text stay readable over it.
	if (nightMode == 1) {
		float grey = dot(color, vec3(0.3, 0.55, 0.15));
		color = mix(color, grey * vec3(0.7, 0.75, 1.0), 0.4) * 0.75;
	}

	// A little noise against banding in the smooth gradients.
	color += (hash_1(gl_FragCoord.xy) - 0.5) / 255.0;
	gl_FragColor = vec4(color, 1.0);
}

// copy 2

// 0..1 from a cell position.
float hash_2(vec2 c) {
	return fract(sin(dot(c, vec2(27.17, 91.31))) * 41231.7);
}

// A ridge_2 line, -1..1: three sines whose wavelengths do not line up.
float ridge_2(float x, float seed) {
	return 0.5 * sin(1.7 * x + seed) + 0.3 * sin(4.3 * x + 2.1 * seed) + 0.2 * sin(9.7 * x - seed);
}

// Fills everything below a ridge_2 with `tint`, paler towards its foot
// where mist lies in the valley, with a faint rim of sky light on top.
vec3 layer_2(vec3 color, vec2 p, float base, float height, float seed,
		vec3 tint, vec3 mist, float px) {
	float top = base + height * ridge_2(p.x, seed);
	vec3 fill = mix(tint, mist, 0.55 * smoothstep(top - 0.02, top - 0.4, p.y));
	fill = mix(fill, mist, 0.25 * smoothstep(top - 0.025, top, p.y));
	return mix(color, fill, smoothstep(top + px, top - px, p.y));
}

void main_2(void) {
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

	// Read and shape the page: each layer_2 moves by its own share of a
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
	vec2 star = (cell + 0.2 + 0.6 * vec2(hash_2(cell + 3.1), hash_2(cell + 7.7))) / 28.0;
	color += smoothstep(0.05, -0.15, sun) * step(0.93, hash_2(cell)) * smoothstep(1.8 * px, 0.0, distance(sky, star)) * above;
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
	color = layer_2(color, vec2(2.2 * (p.x + 0.15 * page), p.y), ground + 0.1, 0.09, 1.0, mix(low, deep, 0.35), low, px);
	color = layer_2(color, vec2(p.x + 0.4 * page, p.y), ground - 0.04, 0.1, 4.0, mix(low, deep, 0.65), low, px);
	color = layer_2(color, vec2(0.8 * (p.x + 0.9 * page), p.y), ground - 0.2, 0.12, 9.0, deep, 0.5 * deep, px);

	// The dark theme asks for a quieter picture: dimmer, cooler and less
	// saturated, so white icons and text stay readable over it.
	if (nightMode == 1) {
		float grey = dot(color, vec3(0.3, 0.55, 0.15));
		color = mix(color, grey * vec3(0.7, 0.75, 1.0), 0.4) * 0.75;
	}

	// A little noise against banding in the smooth gradients.
	color += (hash_2(gl_FragCoord.xy) - 0.5) / 255.0;
	gl_FragColor = vec4(color, 1.0);
}

// copy 3

// 0..1 from a cell position.
float hash_3(vec2 c) {
	return fract(sin(dot(c, vec2(27.17, 91.31))) * 41231.7);
}

// A ridge_3 line, -1..1: three sines whose wavelengths do not line up.
float ridge_3(float x, float seed) {
	return 0.5 * sin(1.7 * x + seed) + 0.3 * sin(4.3 * x + 2.1 * seed) + 0.2 * sin(9.7 * x - seed);
}

// Fills everything below a ridge_3 with `tint`, paler towards its foot
// where mist lies in the valley, with a faint rim of sky light on top.
vec3 layer_3(vec3 color, vec2 p, float base, float height, float seed,
		vec3 tint, vec3 mist, float px) {
	float top = base + height * ridge_3(p.x, seed);
	vec3 fill = mix(tint, mist, 0.55 * smoothstep(top - 0.02, top - 0.4, p.y));
	fill = mix(fill, mist, 0.25 * smoothstep(top - 0.025, top, p.y));
	return mix(color, fill, smoothstep(top + px, top - px, p.y));
}

void main_3(void) {
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

	// Read and shape the page: each layer_3 moves by its own share of a
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
	vec2 star = (cell + 0.2 + 0.6 * vec2(hash_3(cell + 3.1), hash_3(cell + 7.7))) / 28.0;
	color += smoothstep(0.05, -0.15, sun) * step(0.93, hash_3(cell)) * smoothstep(1.8 * px, 0.0, distance(sky, star)) * above;
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
	color = layer_3(color, vec2(2.2 * (p.x + 0.15 * page), p.y), ground + 0.1, 0.09, 1.0, mix(low, deep, 0.35), low, px);
	color = layer_3(color, vec2(p.x + 0.4 * page, p.y), ground - 0.04, 0.1, 4.0, mix(low, deep, 0.65), low, px);
	color = layer_3(color, vec2(0.8 * (p.x + 0.9 * page), p.y), ground - 0.2, 0.12, 9.0, deep, 0.5 * deep, px);

	// The dark theme asks for a quieter picture: dimmer, cooler and less
	// saturated, so white icons and text stay readable over it.
	if (nightMode == 1) {
		float grey = dot(color, vec3(0.3, 0.55, 0.15));
		color = mix(color, grey * vec3(0.7, 0.75, 1.0), 0.4) * 0.75;
	}

	// A little noise against banding in the smooth gradients.
	color += (hash_3(gl_FragCoord.xy) - 0.5) / 255.0;
	gl_FragColor = vec4(color, 1.0);
}

// copy 4

// 0..1 from a cell position.
float hash_4(vec2 c) {
	return fract(sin(dot(c, vec2(27.17, 91.31))) * 41231.7);
}

// A ridge_4 line, -1..1: three sines whose wavelengths do not line up.
float ridge_4(float x, float seed) {
	return 0.5 * sin(1.7 * x + seed) + 0.3 * sin(4.3 * x + 2.1 * seed) + 0.2 * sin(9.7 * x - seed);
}

// Fills everything below a ridge_4 with `tint`, paler towards its foot
// where mist lies in the valley, with a faint rim of sky light on top.
vec3 layer_4(vec3 color, vec2 p, float base, float height, float seed,
		vec3 tint, vec3 mist, float px) {
	float top = base + height * ridge_4(p.x, seed);
	vec3 fill = mix(tint, mist, 0.55 * smoothstep(top - 0.02, top - 0.4, p.y));
	fill = mix(fill, mist, 0.25 * smoothstep(top - 0.025, top, p.y));
	return mix(color, fill, smoothstep(top + px, top - px, p.y));
}

void main_4(void) {
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

	// Read and shape the page: each layer_4 moves by its own share of a
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
	vec2 star = (cell + 0.2 + 0.6 * vec2(hash_4(cell + 3.1), hash_4(cell + 7.7))) / 28.0;
	color += smoothstep(0.05, -0.15, sun) * step(0.93, hash_4(cell)) * smoothstep(1.8 * px, 0.0, distance(sky, star)) * above;
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
	color = layer_4(color, vec2(2.2 * (p.x + 0.15 * page), p.y), ground + 0.1, 0.09, 1.0, mix(low, deep, 0.35), low, px);
	color = layer_4(color, vec2(p.x + 0.4 * page, p.y), ground - 0.04, 0.1, 4.0, mix(low, deep, 0.65), low, px);
	color = layer_4(color, vec2(0.8 * (p.x + 0.9 * page), p.y), ground - 0.2, 0.12, 9.0, deep, 0.5 * deep, px);

	// The dark theme asks for a quieter picture: dimmer, cooler and less
	// saturated, so white icons and text stay readable over it.
	if (nightMode == 1) {
		float grey = dot(color, vec3(0.3, 0.55, 0.15));
		color = mix(color, grey * vec3(0.7, 0.75, 1.0), 0.4) * 0.75;
	}

	// A little noise against banding in the smooth gradients.
	color += (hash_4(gl_FragCoord.xy) - 0.5) / 255.0;
	gl_FragColor = vec4(color, 1.0);
}

// copy 5

// 0..1 from a cell position.
float hash_5(vec2 c) {
	return fract(sin(dot(c, vec2(27.17, 91.31))) * 41231.7);
}

// A ridge_5 line, -1..1: three sines whose wavelengths do not line up.
float ridge_5(float x, float seed) {
	return 0.5 * sin(1.7 * x + seed) + 0.3 * sin(4.3 * x + 2.1 * seed) + 0.2 * sin(9.7 * x - seed);
}

// Fills everything below a ridge_5 with `tint`, paler towards its foot
// where mist lies in the valley, with a faint rim of sky light on top.
vec3 layer_5(vec3 color, vec2 p, float base, float height, float seed,
		vec3 tint, vec3 mist, float px) {
	float top = base + height * ridge_5(p.x, seed);
	vec3 fill = mix(tint, mist, 0.55 * smoothstep(top - 0.02, top - 0.4, p.y));
	fill = mix(fill, mist, 0.25 * smoothstep(top - 0.025, top, p.y));
	return mix(color, fill, smoothstep(top + px, top - px, p.y));
}

void main_5(void) {
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

	// Read and shape the page: each layer_5 moves by its own share of a
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
	vec2 star = (cell + 0.2 + 0.6 * vec2(hash_5(cell + 3.1), hash_5(cell + 7.7))) / 28.0;
	color += smoothstep(0.05, -0.15, sun) * step(0.93, hash_5(cell)) * smoothstep(1.8 * px, 0.0, distance(sky, star)) * above;
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
	color = layer_5(color, vec2(2.2 * (p.x + 0.15 * page), p.y), ground + 0.1, 0.09, 1.0, mix(low, deep, 0.35), low, px);
	color = layer_5(color, vec2(p.x + 0.4 * page, p.y), ground - 0.04, 0.1, 4.0, mix(low, deep, 0.65), low, px);
	color = layer_5(color, vec2(0.8 * (p.x + 0.9 * page), p.y), ground - 0.2, 0.12, 9.0, deep, 0.5 * deep, px);

	// The dark theme asks for a quieter picture: dimmer, cooler and less
	// saturated, so white icons and text stay readable over it.
	if (nightMode == 1) {
		float grey = dot(color, vec3(0.3, 0.55, 0.15));
		color = mix(color, grey * vec3(0.7, 0.75, 1.0), 0.4) * 0.75;
	}

	// A little noise against banding in the smooth gradients.
	color += (hash_5(gl_FragCoord.xy) - 0.5) / 255.0;
	gl_FragColor = vec4(color, 1.0);
}

// copy 6

// 0..1 from a cell position.
float hash_6(vec2 c) {
	return fract(sin(dot(c, vec2(27.17, 91.31))) * 41231.7);
}

// A ridge_6 line, -1..1: three sines whose wavelengths do not line up.
float ridge_6(float x, float seed) {
	return 0.5 * sin(1.7 * x + seed) + 0.3 * sin(4.3 * x + 2.1 * seed) + 0.2 * sin(9.7 * x - seed);
}

// Fills everything below a ridge_6 with `tint`, paler towards its foot
// where mist lies in the valley, with a faint rim of sky light on top.
vec3 layer_6(vec3 color, vec2 p, float base, float height, float seed,
		vec3 tint, vec3 mist, float px) {
	float top = base + height * ridge_6(p.x, seed);
	vec3 fill = mix(tint, mist, 0.55 * smoothstep(top - 0.02, top - 0.4, p.y));
	fill = mix(fill, mist, 0.25 * smoothstep(top - 0.025, top, p.y));
	return mix(color, fill, smoothstep(top + px, top - px, p.y));
}

void main_6(void) {
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

	// Read and shape the page: each layer_6 moves by its own share of a
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
	vec2 star = (cell + 0.2 + 0.6 * vec2(hash_6(cell + 3.1), hash_6(cell + 7.7))) / 28.0;
	color += smoothstep(0.05, -0.15, sun) * step(0.93, hash_6(cell)) * smoothstep(1.8 * px, 0.0, distance(sky, star)) * above;
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
	color = layer_6(color, vec2(2.2 * (p.x + 0.15 * page), p.y), ground + 0.1, 0.09, 1.0, mix(low, deep, 0.35), low, px);
	color = layer_6(color, vec2(p.x + 0.4 * page, p.y), ground - 0.04, 0.1, 4.0, mix(low, deep, 0.65), low, px);
	color = layer_6(color, vec2(0.8 * (p.x + 0.9 * page), p.y), ground - 0.2, 0.12, 9.0, deep, 0.5 * deep, px);

	// The dark theme asks for a quieter picture: dimmer, cooler and less
	// saturated, so white icons and text stay readable over it.
	if (nightMode == 1) {
		float grey = dot(color, vec3(0.3, 0.55, 0.15));
		color = mix(color, grey * vec3(0.7, 0.75, 1.0), 0.4) * 0.75;
	}

	// A little noise against banding in the smooth gradients.
	color += (hash_6(gl_FragCoord.xy) - 0.5) / 255.0;
	gl_FragColor = vec4(color, 1.0);
}

// copy 7

// 0..1 from a cell position.
float hash_7(vec2 c) {
	return fract(sin(dot(c, vec2(27.17, 91.31))) * 41231.7);
}

// A ridge_7 line, -1..1: three sines whose wavelengths do not line up.
float ridge_7(float x, float seed) {
	return 0.5 * sin(1.7 * x + seed) + 0.3 * sin(4.3 * x + 2.1 * seed) + 0.2 * sin(9.7 * x - seed);
}

// Fills everything below a ridge_7 with `tint`, paler towards its foot
// where mist lies in the valley, with a faint rim of sky light on top.
vec3 layer_7(vec3 color, vec2 p, float base, float height, float seed,
		vec3 tint, vec3 mist, float px) {
	float top = base + height * ridge_7(p.x, seed);
	vec3 fill = mix(tint, mist, 0.55 * smoothstep(top - 0.02, top - 0.4, p.y));
	fill = mix(fill, mist, 0.25 * smoothstep(top - 0.025, top, p.y));
	return mix(color, fill, smoothstep(top + px, top - px, p.y));
}

void main_7(void) {
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

	// Read and shape the page: each layer_7 moves by its own share of a
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
	vec2 star = (cell + 0.2 + 0.6 * vec2(hash_7(cell + 3.1), hash_7(cell + 7.7))) / 28.0;
	color += smoothstep(0.05, -0.15, sun) * step(0.93, hash_7(cell)) * smoothstep(1.8 * px, 0.0, distance(sky, star)) * above;
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
	color = layer_7(color, vec2(2.2 * (p.x + 0.15 * page), p.y), ground + 0.1, 0.09, 1.0, mix(low, deep, 0.35), low, px);
	color = layer_7(color, vec2(p.x + 0.4 * page, p.y), ground - 0.04, 0.1, 4.0, mix(low, deep, 0.65), low, px);
	color = layer_7(color, vec2(0.8 * (p.x + 0.9 * page), p.y), ground - 0.2, 0.12, 9.0, deep, 0.5 * deep, px);

	// The dark theme asks for a quieter picture: dimmer, cooler and less
	// saturated, so white icons and text stay readable over it.
	if (nightMode == 1) {
		float grey = dot(color, vec3(0.3, 0.55, 0.15));
		color = mix(color, grey * vec3(0.7, 0.75, 1.0), 0.4) * 0.75;
	}

	// A little noise against banding in the smooth gradients.
	color += (hash_7(gl_FragCoord.xy) - 0.5) / 255.0;
	gl_FragColor = vec4(color, 1.0);
}

// copy 8

// 0..1 from a cell position.
float hash_8(vec2 c) {
	return fract(sin(dot(c, vec2(27.17, 91.31))) * 41231.7);
}

// A ridge_8 line, -1..1: three sines whose wavelengths do not line up.
float ridge_8(float x, float seed) {
	return 0.5 * sin(1.7 * x + seed) + 0.3 * sin(4.3 * x + 2.1 * seed) + 0.2 * sin(9.7 * x - seed);
}

// Fills everything below a ridge_8 with `tint`, paler towards its foot
// where mist lies in the valley, with a faint rim of sky light on top.
vec3 layer_8(vec3 color, vec2 p, float base, float height, float seed,
		vec3 tint, vec3 mist, float px) {
	float top = base + height * ridge_8(p.x, seed);
	vec3 fill = mix(tint, mist, 0.55 * smoothstep(top - 0.02, top - 0.4, p.y));
	fill = mix(fill, mist, 0.25 * smoothstep(top - 0.025, top, p.y));
	return mix(color, fill, smoothstep(top + px, top - px, p.y));
}

void main_8(void) {
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

	// Read and shape the page: each layer_8 moves by its own share of a
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
	vec2 star = (cell + 0.2 + 0.6 * vec2(hash_8(cell + 3.1), hash_8(cell + 7.7))) / 28.0;
	color += smoothstep(0.05, -0.15, sun) * step(0.93, hash_8(cell)) * smoothstep(1.8 * px, 0.0, distance(sky, star)) * above;
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
	color = layer_8(color, vec2(2.2 * (p.x + 0.15 * page), p.y), ground + 0.1, 0.09, 1.0, mix(low, deep, 0.35), low, px);
	color = layer_8(color, vec2(p.x + 0.4 * page, p.y), ground - 0.04, 0.1, 4.0, mix(low, deep, 0.65), low, px);
	color = layer_8(color, vec2(0.8 * (p.x + 0.9 * page), p.y), ground - 0.2, 0.12, 9.0, deep, 0.5 * deep, px);

	// The dark theme asks for a quieter picture: dimmer, cooler and less
	// saturated, so white icons and text stay readable over it.
	if (nightMode == 1) {
		float grey = dot(color, vec3(0.3, 0.55, 0.15));
		color = mix(color, grey * vec3(0.7, 0.75, 1.0), 0.4) * 0.75;
	}

	// A little noise against banding in the smooth gradients.
	color += (hash_8(gl_FragCoord.xy) - 0.5) / 255.0;
	gl_FragColor = vec4(color, 1.0);
}

// copy 9

// 0..1 from a cell position.
float hash_9(vec2 c) {
	return fract(sin(dot(c, vec2(27.17, 91.31))) * 41231.7);
}

// A ridge_9 line, -1..1: three sines whose wavelengths do not line up.
float ridge_9(float x, float seed) {
	return 0.5 * sin(1.7 * x + seed) + 0.3 * sin(4.3 * x + 2.1 * seed) + 0.2 * sin(9.7 * x - seed);
}

// Fills everything below a ridge_9 with `tint`, paler towards its foot
// where mist lies in the valley, with a faint rim of sky light on top.
vec3 layer_9(vec3 color, vec2 p, float base, float height, float seed,
		vec3 tint, vec3 mist, float px) {
	float top = base + height * ridge_9(p.x, seed);
	vec3 fill = mix(tint, mist, 0.55 * smoothstep(top - 0.02, top - 0.4, p.y));
	fill = mix(fill, mist, 0.25 * smoothstep(top - 0.025, top, p.y));
	return mix(color, fill, smoothstep(top + px, top - px, p.y));
}

void main_9(void) {
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

	// Read and shape the page: each layer_9 moves by its own share of a
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
	vec2 star = (cell + 0.2 + 0.6 * vec2(hash_9(cell + 3.1), hash_9(cell + 7.7))) / 28.0;
	color += smoothstep(0.05, -0.15, sun) * step(0.93, hash_9(cell)) * smoothstep(1.8 * px, 0.0, distance(sky, star)) * above;
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
	color = layer_9(color, vec2(2.2 * (p.x + 0.15 * page), p.y), ground + 0.1, 0.09, 1.0, mix(low, deep, 0.35), low, px);
	color = layer_9(color, vec2(p.x + 0.4 * page, p.y), ground - 0.04, 0.1, 4.0, mix(low, deep, 0.65), low, px);
	color = layer_9(color, vec2(0.8 * (p.x + 0.9 * page), p.y), ground - 0.2, 0.12, 9.0, deep, 0.5 * deep, px);

	// The dark theme asks for a quieter picture: dimmer, cooler and less
	// saturated, so white icons and text stay readable over it.
	if (nightMode == 1) {
		float grey = dot(color, vec3(0.3, 0.55, 0.15));
		color = mix(color, grey * vec3(0.7, 0.75, 1.0), 0.4) * 0.75;
	}

	// A little noise against banding in the smooth gradients.
	color += (hash_9(gl_FragCoord.xy) - 0.5) / 255.0;
	gl_FragColor = vec4(color, 1.0);
}

// copy 10

// 0..1 from a cell position.
float hash_10(vec2 c) {
	return fract(sin(dot(c, vec2(27.17, 91.31))) * 41231.7);
}

// A ridge_10 line, -1..1: three sines whose wavelengths do not line up.
float ridge_10(float x, float seed) {
	return 0.5 * sin(1.7 * x + seed) + 0.3 * sin(4.3 * x + 2.1 * seed) + 0.2 * sin(9.7 * x - seed);
}

// Fills everything below a ridge_10 with `tint`, paler towards its foot
// where mist lies in the valley, with a faint rim of sky light on top.
vec3 layer_10(vec3 color, vec2 p, float base, float height, float seed,
		vec3 tint, vec3 mist, float px) {
	float top = base + height * ridge_10(p.x, seed);
	vec3 fill = mix(tint, mist, 0.55 * smoothstep(top - 0.02, top - 0.4, p.y));
	fill = mix(fill, mist, 0.25 * smoothstep(top - 0.025, top, p.y));
	return mix(color, fill, smoothstep(top + px, top - px, p.y));
}

void main_10(void) {
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

	// Read and shape the page: each layer_10 moves by its own share of a
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
	vec2 star = (cell + 0.2 + 0.6 * vec2(hash_10(cell + 3.1), hash_10(cell + 7.7))) / 28.0;
	color += smoothstep(0.05, -0.15, sun) * step(0.93, hash_10(cell)) * smoothstep(1.8 * px, 0.0, distance(sky, star)) * above;
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
	color = layer_10(color, vec2(2.2 * (p.x + 0.15 * page), p.y), ground + 0.1, 0.09, 1.0, mix(low, deep, 0.35), low, px);
	color = layer_10(color, vec2(p.x + 0.4 * page, p.y), ground - 0.04, 0.1, 4.0, mix(low, deep, 0.65), low, px);
	color = layer_10(color, vec2(0.8 * (p.x + 0.9 * page), p.y), ground - 0.2, 0.12, 9.0, deep, 0.5 * deep, px);

	// The dark theme asks for a quieter picture: dimmer, cooler and less
	// saturated, so white icons and text stay readable over it.
	if (nightMode == 1) {
		float grey = dot(color, vec3(0.3, 0.55, 0.15));
		color = mix(color, grey * vec3(0.7, 0.75, 1.0), 0.4) * 0.75;
	}

	// A little noise against banding in the smooth gradients.
	color += (hash_10(gl_FragCoord.xy) - 0.5) / 255.0;
	gl_FragColor = vec4(color, 1.0);
}

// copy 11

// 0..1 from a cell position.
float hash_11(vec2 c) {
	return fract(sin(dot(c, vec2(27.17, 91.31))) * 41231.7);
}

// A ridge_11 line, -1..1: three sines whose wavelengths do not line up.
float ridge_11(float x, float seed) {
	return 0.5 * sin(1.7 * x + seed) + 0.3 * sin(4.3 * x + 2.1 * seed) + 0.2 * sin(9.7 * x - seed);
}

// Fills everything below a ridge_11 with `tint`, paler towards its foot
// where mist lies in the valley, with a faint rim of sky light on top.
vec3 layer_11(vec3 color, vec2 p, float base, float height, float seed,
		vec3 tint, vec3 mist, float px) {
	float top = base + height * ridge_11(p.x, seed);
	vec3 fill = mix(tint, mist, 0.55 * smoothstep(top - 0.02, top - 0.4, p.y));
	fill = mix(fill, mist, 0.25 * smoothstep(top - 0.025, top, p.y));
	return mix(color, fill, smoothstep(top + px, top - px, p.y));
}

void main_11(void) {
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

	// Read and shape the page: each layer_11 moves by its own share of a
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
	vec2 star = (cell + 0.2 + 0.6 * vec2(hash_11(cell + 3.1), hash_11(cell + 7.7))) / 28.0;
	color += smoothstep(0.05, -0.15, sun) * step(0.93, hash_11(cell)) * smoothstep(1.8 * px, 0.0, distance(sky, star)) * above;
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
	color = layer_11(color, vec2(2.2 * (p.x + 0.15 * page), p.y), ground + 0.1, 0.09, 1.0, mix(low, deep, 0.35), low, px);
	color = layer_11(color, vec2(p.x + 0.4 * page, p.y), ground - 0.04, 0.1, 4.0, mix(low, deep, 0.65), low, px);
	color = layer_11(color, vec2(0.8 * (p.x + 0.9 * page), p.y), ground - 0.2, 0.12, 9.0, deep, 0.5 * deep, px);

	// The dark theme asks for a quieter picture: dimmer, cooler and less
	// saturated, so white icons and text stay readable over it.
	if (nightMode == 1) {
		float grey = dot(color, vec3(0.3, 0.55, 0.15));
		color = mix(color, grey * vec3(0.7, 0.75, 1.0), 0.4) * 0.75;
	}

	// A little noise against banding in the smooth gradients.
	color += (hash_11(gl_FragCoord.xy) - 0.5) / 255.0;
	gl_FragColor = vec4(color, 1.0);
}

// copy 12

// 0..1 from a cell position.
float hash_12(vec2 c) {
	return fract(sin(dot(c, vec2(27.17, 91.31))) * 41231.7);
}

// A ridge_12 line, -1..1: three sines whose wavelengths do not line up.
float ridge_12(float x, float seed) {
	return 0.5 * sin(1.7 * x + seed) + 0.3 * sin(4.3 * x + 2.1 * seed) + 0.2 * sin(9.7 * x - seed);
}

// Fills everything below a ridge_12 with `tint`, paler towards its foot
// where mist lies in the valley, with a faint rim of sky light on top.
vec3 layer_12(vec3 color, vec2 p, float base, float height, float seed,
		vec3 tint, vec3 mist, float px) {
	float top = base + height * ridge_12(p.x, seed);
	vec3 fill = mix(tint, mist, 0.55 * smoothstep(top - 0.02, top - 0.4, p.y));
	fill = mix(fill, mist, 0.25 * smoothstep(top - 0.025, top, p.y));
	return mix(color, fill, smoothstep(top + px, top - px, p.y));
}

void main_12(void) {
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

	// Read and shape the page: each layer_12 moves by its own share of a
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
	vec2 star = (cell + 0.2 + 0.6 * vec2(hash_12(cell + 3.1), hash_12(cell + 7.7))) / 28.0;
	color += smoothstep(0.05, -0.15, sun) * step(0.93, hash_12(cell)) * smoothstep(1.8 * px, 0.0, distance(sky, star)) * above;
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
	color = layer_12(color, vec2(2.2 * (p.x + 0.15 * page), p.y), ground + 0.1, 0.09, 1.0, mix(low, deep, 0.35), low, px);
	color = layer_12(color, vec2(p.x + 0.4 * page, p.y), ground - 0.04, 0.1, 4.0, mix(low, deep, 0.65), low, px);
	color = layer_12(color, vec2(0.8 * (p.x + 0.9 * page), p.y), ground - 0.2, 0.12, 9.0, deep, 0.5 * deep, px);

	// The dark theme asks for a quieter picture: dimmer, cooler and less
	// saturated, so white icons and text stay readable over it.
	if (nightMode == 1) {
		float grey = dot(color, vec3(0.3, 0.55, 0.15));
		color = mix(color, grey * vec3(0.7, 0.75, 1.0), 0.4) * 0.75;
	}

	// A little noise against banding in the smooth gradients.
	color += (hash_12(gl_FragCoord.xy) - 0.5) / 255.0;
	gl_FragColor = vec4(color, 1.0);
}

// copy 13

// 0..1 from a cell position.
float hash_13(vec2 c) {
	return fract(sin(dot(c, vec2(27.17, 91.31))) * 41231.7);
}

// A ridge_13 line, -1..1: three sines whose wavelengths do not line up.
float ridge_13(float x, float seed) {
	return 0.5 * sin(1.7 * x + seed) + 0.3 * sin(4.3 * x + 2.1 * seed) + 0.2 * sin(9.7 * x - seed);
}

// Fills everything below a ridge_13 with `tint`, paler towards its foot
// where mist lies in the valley, with a faint rim of sky light on top.
vec3 layer_13(vec3 color, vec2 p, float base, float height, float seed,
		vec3 tint, vec3 mist, float px) {
	float top = base + height * ridge_13(p.x, seed);
	vec3 fill = mix(tint, mist, 0.55 * smoothstep(top - 0.02, top - 0.4, p.y));
	fill = mix(fill, mist, 0.25 * smoothstep(top - 0.025, top, p.y));
	return mix(color, fill, smoothstep(top + px, top - px, p.y));
}

void main_13(void) {
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

	// Read and shape the page: each layer_13 moves by its own share of a
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
	vec2 star = (cell + 0.2 + 0.6 * vec2(hash_13(cell + 3.1), hash_13(cell + 7.7))) / 28.0;
	color += smoothstep(0.05, -0.15, sun) * step(0.93, hash_13(cell)) * smoothstep(1.8 * px, 0.0, distance(sky, star)) * above;
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
	color = layer_13(color, vec2(2.2 * (p.x + 0.15 * page), p.y), ground + 0.1, 0.09, 1.0, mix(low, deep, 0.35), low, px);
	color = layer_13(color, vec2(p.x + 0.4 * page, p.y), ground - 0.04, 0.1, 4.0, mix(low, deep, 0.65), low, px);
	color = layer_13(color, vec2(0.8 * (p.x + 0.9 * page), p.y), ground - 0.2, 0.12, 9.0, deep, 0.5 * deep, px);

	// The dark theme asks for a quieter picture: dimmer, cooler and less
	// saturated, so white icons and text stay readable over it.
	if (nightMode == 1) {
		float grey = dot(color, vec3(0.3, 0.55, 0.15));
		color = mix(color, grey * vec3(0.7, 0.75, 1.0), 0.4) * 0.75;
	}

	// A little noise against banding in the smooth gradients.
	color += (hash_13(gl_FragCoord.xy) - 0.5) / 255.0;
	gl_FragColor = vec4(color, 1.0);
}

// copy 14

// 0..1 from a cell position.
float hash_14(vec2 c) {
	return fract(sin(dot(c, vec2(27.17, 91.31))) * 41231.7);
}

// A ridge_14 line, -1..1: three sines whose wavelengths do not line up.
float ridge_14(float x, float seed) {
	return 0.5 * sin(1.7 * x + seed) + 0.3 * sin(4.3 * x + 2.1 * seed) + 0.2 * sin(9.7 * x - seed);
}

// Fills everything below a ridge_14 with `tint`, paler towards its foot
// where mist lies in the valley, with a faint rim of sky light on top.
vec3 layer_14(vec3 color, vec2 p, float base, float height, float seed,
		vec3 tint, vec3 mist, float px) {
	float top = base + height * ridge_14(p.x, seed);
	vec3 fill = mix(tint, mist, 0.55 * smoothstep(top - 0.02, top - 0.4, p.y));
	fill = mix(fill, mist, 0.25 * smoothstep(top - 0.025, top, p.y));
	return mix(color, fill, smoothstep(top + px, top - px, p.y));
}

void main_14(void) {
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

	// Read and shape the page: each layer_14 moves by its own share of a
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
	vec2 star = (cell + 0.2 + 0.6 * vec2(hash_14(cell + 3.1), hash_14(cell + 7.7))) / 28.0;
	color += smoothstep(0.05, -0.15, sun) * step(0.93, hash_14(cell)) * smoothstep(1.8 * px, 0.0, distance(sky, star)) * above;
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
	color = layer_14(color, vec2(2.2 * (p.x + 0.15 * page), p.y), ground + 0.1, 0.09, 1.0, mix(low, deep, 0.35), low, px);
	color = layer_14(color, vec2(p.x + 0.4 * page, p.y), ground - 0.04, 0.1, 4.0, mix(low, deep, 0.65), low, px);
	color = layer_14(color, vec2(0.8 * (p.x + 0.9 * page), p.y), ground - 0.2, 0.12, 9.0, deep, 0.5 * deep, px);

	// The dark theme asks for a quieter picture: dimmer, cooler and less
	// saturated, so white icons and text stay readable over it.
	if (nightMode == 1) {
		float grey = dot(color, vec3(0.3, 0.55, 0.15));
		color = mix(color, grey * vec3(0.7, 0.75, 1.0), 0.4) * 0.75;
	}

	// A little noise against banding in the smooth gradients.
	color += (hash_14(gl_FragCoord.xy) - 0.5) / 255.0;
	gl_FragColor = vec4(color, 1.0);
}

// copy 15

// 0..1 from a cell position.
float hash_15(vec2 c) {
	return fract(sin(dot(c, vec2(27.17, 91.31))) * 41231.7);
}

// A ridge_15 line, -1..1: three sines whose wavelengths do not line up.
float ridge_15(float x, float seed) {
	return 0.5 * sin(1.7 * x + seed) + 0.3 * sin(4.3 * x + 2.1 * seed) + 0.2 * sin(9.7 * x - seed);
}

// Fills everything below a ridge_15 with `tint`, paler towards its foot
// where mist lies in the valley, with a faint rim of sky light on top.
vec3 layer_15(vec3 color, vec2 p, float base, float height, float seed,
		vec3 tint, vec3 mist, float px) {
	float top = base + height * ridge_15(p.x, seed);
	vec3 fill = mix(tint, mist, 0.55 * smoothstep(top - 0.02, top - 0.4, p.y));
	fill = mix(fill, mist, 0.25 * smoothstep(top - 0.025, top, p.y));
	return mix(color, fill, smoothstep(top + px, top - px, p.y));
}

void main_15(void) {
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

	// Read and shape the page: each layer_15 moves by its own share of a
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
	vec2 star = (cell + 0.2 + 0.6 * vec2(hash_15(cell + 3.1), hash_15(cell + 7.7))) / 28.0;
	color += smoothstep(0.05, -0.15, sun) * step(0.93, hash_15(cell)) * smoothstep(1.8 * px, 0.0, distance(sky, star)) * above;
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
	color = layer_15(color, vec2(2.2 * (p.x + 0.15 * page), p.y), ground + 0.1, 0.09, 1.0, mix(low, deep, 0.35), low, px);
	color = layer_15(color, vec2(p.x + 0.4 * page, p.y), ground - 0.04, 0.1, 4.0, mix(low, deep, 0.65), low, px);
	color = layer_15(color, vec2(0.8 * (p.x + 0.9 * page), p.y), ground - 0.2, 0.12, 9.0, deep, 0.5 * deep, px);

	// The dark theme asks for a quieter picture: dimmer, cooler and less
	// saturated, so white icons and text stay readable over it.
	if (nightMode == 1) {
		float grey = dot(color, vec3(0.3, 0.55, 0.15));
		color = mix(color, grey * vec3(0.7, 0.75, 1.0), 0.4) * 0.75;
	}

	// A little noise against banding in the smooth gradients.
	color += (hash_15(gl_FragCoord.xy) - 0.5) / 255.0;
	gl_FragColor = vec4(color, 1.0);
}

// copy 16

// 0..1 from a cell position.
float hash_16(vec2 c) {
	return fract(sin(dot(c, vec2(27.17, 91.31))) * 41231.7);
}

// A ridge_16 line, -1..1: three sines whose wavelengths do not line up.
float ridge_16(float x, float seed) {
	return 0.5 * sin(1.7 * x + seed) + 0.3 * sin(4.3 * x + 2.1 * seed) + 0.2 * sin(9.7 * x - seed);
}

// Fills everything below a ridge_16 with `tint`, paler towards its foot
// where mist lies in the valley, with a faint rim of sky light on top.
vec3 layer_16(vec3 color, vec2 p, float base, float height, float seed,
		vec3 tint, vec3 mist, float px) {
	float top = base + height * ridge_16(p.x, seed);
	vec3 fill = mix(tint, mist, 0.55 * smoothstep(top - 0.02, top - 0.4, p.y));
	fill = mix(fill, mist, 0.25 * smoothstep(top - 0.025, top, p.y));
	return mix(color, fill, smoothstep(top + px, top - px, p.y));
}

void main_16(void) {
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

	// Read and shape the page: each layer_16 moves by its own share of a
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
	vec2 star = (cell + 0.2 + 0.6 * vec2(hash_16(cell + 3.1), hash_16(cell + 7.7))) / 28.0;
	color += smoothstep(0.05, -0.15, sun) * step(0.93, hash_16(cell)) * smoothstep(1.8 * px, 0.0, distance(sky, star)) * above;
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
	color = layer_16(color, vec2(2.2 * (p.x + 0.15 * page), p.y), ground + 0.1, 0.09, 1.0, mix(low, deep, 0.35), low, px);
	color = layer_16(color, vec2(p.x + 0.4 * page, p.y), ground - 0.04, 0.1, 4.0, mix(low, deep, 0.65), low, px);
	color = layer_16(color, vec2(0.8 * (p.x + 0.9 * page), p.y), ground - 0.2, 0.12, 9.0, deep, 0.5 * deep, px);

	// The dark theme asks for a quieter picture: dimmer, cooler and less
	// saturated, so white icons and text stay readable over it.
	if (nightMode == 1) {
		float grey = dot(color, vec3(0.3, 0.55, 0.15));
		color = mix(color, grey * vec3(0.7, 0.75, 1.0), 0.4) * 0.75;
	}

	// A little noise against banding in the smooth gradients.
	color += (hash_16(gl_FragCoord.xy) - 0.5) / 255.0;
	gl_FragColor = vec4(color, 1.0);
}

// copy 17

// 0..1 from a cell position.
float hash_17(vec2 c) {
	return fract(sin(dot(c, vec2(27.17, 91.31))) * 41231.7);
}

// A ridge_17 line, -1..1: three sines whose wavelengths do not line up.
float ridge_17(float x, float seed) {
	return 0.5 * sin(1.7 * x + seed) + 0.3 * sin(4.3 * x + 2.1 * seed) + 0.2 * sin(9.7 * x - seed);
}

// Fills everything below a ridge_17 with `tint`, paler towards its foot
// where mist lies in the valley, with a faint rim of sky light on top.
vec3 layer_17(vec3 color, vec2 p, float base, float height, float seed,
		vec3 tint, vec3 mist, float px) {
	float top = base + height * ridge_17(p.x, seed);
	vec3 fill = mix(tint, mist, 0.55 * smoothstep(top - 0.02, top - 0.4, p.y));
	fill = mix(fill, mist, 0.25 * smoothstep(top - 0.025, top, p.y));
	return mix(color, fill, smoothstep(top + px, top - px, p.y));
}

void main_17(void) {
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

	// Read and shape the page: each layer_17 moves by its own share of a
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
	vec2 star = (cell + 0.2 + 0.6 * vec2(hash_17(cell + 3.1), hash_17(cell + 7.7))) / 28.0;
	color += smoothstep(0.05, -0.15, sun) * step(0.93, hash_17(cell)) * smoothstep(1.8 * px, 0.0, distance(sky, star)) * above;
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
	color = layer_17(color, vec2(2.2 * (p.x + 0.15 * page), p.y), ground + 0.1, 0.09, 1.0, mix(low, deep, 0.35), low, px);
	color = layer_17(color, vec2(p.x + 0.4 * page, p.y), ground - 0.04, 0.1, 4.0, mix(low, deep, 0.65), low, px);
	color = layer_17(color, vec2(0.8 * (p.x + 0.9 * page), p.y), ground - 0.2, 0.12, 9.0, deep, 0.5 * deep, px);

	// The dark theme asks for a quieter picture: dimmer, cooler and less
	// saturated, so white icons and text stay readable over it.
	if (nightMode == 1) {
		float grey = dot(color, vec3(0.3, 0.55, 0.15));
		color = mix(color, grey * vec3(0.7, 0.75, 1.0), 0.4) * 0.75;
	}

	// A little noise against banding in the smooth gradients.
	color += (hash_17(gl_FragCoord.xy) - 0.5) / 255.0;
	gl_FragColor = vec4(color, 1.0);
}

// copy 18

// 0..1 from a cell position.
float hash_18(vec2 c) {
	return fract(sin(dot(c, vec2(27.17, 91.31))) * 41231.7);
}

// A ridge_18 line, -1..1: three sines whose wavelengths do not line up.
float ridge_18(float x, float seed) {
	return 0.5 * sin(1.7 * x + seed) + 0.3 * sin(4.3 * x + 2.1 * seed) + 0.2 * sin(9.7 * x - seed);
}

// Fills everything below a ridge_18 with `tint`, paler towards its foot
// where mist lies in the valley, with a faint rim of sky light on top.
vec3 layer_18(vec3 color, vec2 p, float base, float height, float seed,
		vec3 tint, vec3 mist, float px) {
	float top = base + height * ridge_18(p.x, seed);
	vec3 fill = mix(tint, mist, 0.55 * smoothstep(top - 0.02, top - 0.4, p.y));
	fill = mix(fill, mist, 0.25 * smoothstep(top - 0.025, top, p.y));
	return mix(color, fill, smoothstep(top + px, top - px, p.y));
}

void main_18(void) {
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

	// Read and shape the page: each layer_18 moves by its own share of a
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
	vec2 star = (cell + 0.2 + 0.6 * vec2(hash_18(cell + 3.1), hash_18(cell + 7.7))) / 28.0;
	color += smoothstep(0.05, -0.15, sun) * step(0.93, hash_18(cell)) * smoothstep(1.8 * px, 0.0, distance(sky, star)) * above;
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
	color = layer_18(color, vec2(2.2 * (p.x + 0.15 * page), p.y), ground + 0.1, 0.09, 1.0, mix(low, deep, 0.35), low, px);
	color = layer_18(color, vec2(p.x + 0.4 * page, p.y), ground - 0.04, 0.1, 4.0, mix(low, deep, 0.65), low, px);
	color = layer_18(color, vec2(0.8 * (p.x + 0.9 * page), p.y), ground - 0.2, 0.12, 9.0, deep, 0.5 * deep, px);

	// The dark theme asks for a quieter picture: dimmer, cooler and less
	// saturated, so white icons and text stay readable over it.
	if (nightMode == 1) {
		float grey = dot(color, vec3(0.3, 0.55, 0.15));
		color = mix(color, grey * vec3(0.7, 0.75, 1.0), 0.4) * 0.75;
	}

	// A little noise against banding in the smooth gradients.
	color += (hash_18(gl_FragCoord.xy) - 0.5) / 255.0;
	gl_FragColor = vec4(color, 1.0);
}

// copy 19

// 0..1 from a cell position.
float hash_19(vec2 c) {
	return fract(sin(dot(c, vec2(27.17, 91.31))) * 41231.7);
}

// A ridge_19 line, -1..1: three sines whose wavelengths do not line up.
float ridge_19(float x, float seed) {
	return 0.5 * sin(1.7 * x + seed) + 0.3 * sin(4.3 * x + 2.1 * seed) + 0.2 * sin(9.7 * x - seed);
}

// Fills everything below a ridge_19 with `tint`, paler towards its foot
// where mist lies in the valley, with a faint rim of sky light on top.
vec3 layer_19(vec3 color, vec2 p, float base, float height, float seed,
		vec3 tint, vec3 mist, float px) {
	float top = base + height * ridge_19(p.x, seed);
	vec3 fill = mix(tint, mist, 0.55 * smoothstep(top - 0.02, top - 0.4, p.y));
	fill = mix(fill, mist, 0.25 * smoothstep(top - 0.025, top, p.y));
	return mix(color, fill, smoothstep(top + px, top - px, p.y));
}

void main_19(void) {
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

	// Read and shape the page: each layer_19 moves by its own share of a
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
	vec2 star = (cell + 0.2 + 0.6 * vec2(hash_19(cell + 3.1), hash_19(cell + 7.7))) / 28.0;
	color += smoothstep(0.05, -0.15, sun) * step(0.93, hash_19(cell)) * smoothstep(1.8 * px, 0.0, distance(sky, star)) * above;
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
	color = layer_19(color, vec2(2.2 * (p.x + 0.15 * page), p.y), ground + 0.1, 0.09, 1.0, mix(low, deep, 0.35), low, px);
	color = layer_19(color, vec2(p.x + 0.4 * page, p.y), ground - 0.04, 0.1, 4.0, mix(low, deep, 0.65), low, px);
	color = layer_19(color, vec2(0.8 * (p.x + 0.9 * page), p.y), ground - 0.2, 0.12, 9.0, deep, 0.5 * deep, px);

	// The dark theme asks for a quieter picture: dimmer, cooler and less
	// saturated, so white icons and text stay readable over it.
	if (nightMode == 1) {
		float grey = dot(color, vec3(0.3, 0.55, 0.15));
		color = mix(color, grey * vec3(0.7, 0.75, 1.0), 0.4) * 0.75;
	}

	// A little noise against banding in the smooth gradients.
	color += (hash_19(gl_FragCoord.xy) - 0.5) / 255.0;
	gl_FragColor = vec4(color, 1.0);
}

// copy 20

// 0..1 from a cell position.
float hash_20(vec2 c) {
	return fract(sin(dot(c, vec2(27.17, 91.31))) * 41231.7);
}

// A ridge_20 line, -1..1: three sines whose wavelengths do not line up.
float ridge_20(float x, float seed) {
	return 0.5 * sin(1.7 * x + seed) + 0.3 * sin(4.3 * x + 2.1 * seed) + 0.2 * sin(9.7 * x - seed);
}

// Fills everything below a ridge_20 with `tint`, paler towards its foot
// where mist lies in the valley, with a faint rim of sky light on top.
vec3 layer_20(vec3 color, vec2 p, float base, float height, float seed,
		vec3 tint, vec3 mist, float px) {
	float top = base + height * ridge_20(p.x, seed);
	vec3 fill = mix(tint, mist, 0.55 * smoothstep(top - 0.02, top - 0.4, p.y));
	fill = mix(fill, mist, 0.25 * smoothstep(top - 0.025, top, p.y));
	return mix(color, fill, smoothstep(top + px, top - px, p.y));
}

void main_20(void) {
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

	// Read and shape the page: each layer_20 moves by its own share of a
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
	vec2 star = (cell + 0.2 + 0.6 * vec2(hash_20(cell + 3.1), hash_20(cell + 7.7))) / 28.0;
	color += smoothstep(0.05, -0.15, sun) * step(0.93, hash_20(cell)) * smoothstep(1.8 * px, 0.0, distance(sky, star)) * above;
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
	color = layer_20(color, vec2(2.2 * (p.x + 0.15 * page), p.y), ground + 0.1, 0.09, 1.0, mix(low, deep, 0.35), low, px);
	color = layer_20(color, vec2(p.x + 0.4 * page, p.y), ground - 0.04, 0.1, 4.0, mix(low, deep, 0.65), low, px);
	color = layer_20(color, vec2(0.8 * (p.x + 0.9 * page), p.y), ground - 0.2, 0.12, 9.0, deep, 0.5 * deep, px);

	// The dark theme asks for a quieter picture: dimmer, cooler and less
	// saturated, so white icons and text stay readable over it.
	if (nightMode == 1) {
		float grey = dot(color, vec3(0.3, 0.55, 0.15));
		color = mix(color, grey * vec3(0.7, 0.75, 1.0), 0.4) * 0.75;
	}

	// A little noise against banding in the smooth gradients.
	color += (hash_20(gl_FragCoord.xy) - 0.5) / 255.0;
	gl_FragColor = vec4(color, 1.0);
}

// copy 21

// 0..1 from a cell position.
float hash_21(vec2 c) {
	return fract(sin(dot(c, vec2(27.17, 91.31))) * 41231.7);
}

// A ridge_21 line, -1..1: three sines whose wavelengths do not line up.
float ridge_21(float x, float seed) {
	return 0.5 * sin(1.7 * x + seed) + 0.3 * sin(4.3 * x + 2.1 * seed) + 0.2 * sin(9.7 * x - seed);
}

// Fills everything below a ridge_21 with `tint`, paler towards its foot
// where mist lies in the valley, with a faint rim of sky light on top.
vec3 layer_21(vec3 color, vec2 p, float base, float height, float seed,
		vec3 tint, vec3 mist, float px) {
	float top = base + height * ridge_21(p.x, seed);
	vec3 fill = mix(tint, mist, 0.55 * smoothstep(top - 0.02, top - 0.4, p.y));
	fill = mix(fill, mist, 0.25 * smoothstep(top - 0.025, top, p.y));
	return mix(color, fill, smoothstep(top + px, top - px, p.y));
}

void main_21(void) {
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

	// Read and shape the page: each layer_21 moves by its own share of a
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
	vec2 star = (cell + 0.2 + 0.6 * vec2(hash_21(cell + 3.1), hash_21(cell + 7.7))) / 28.0;
	color += smoothstep(0.05, -0.15, sun) * step(0.93, hash_21(cell)) * smoothstep(1.8 * px, 0.0, distance(sky, star)) * above;
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
	color = layer_21(color, vec2(2.2 * (p.x + 0.15 * page), p.y), ground + 0.1, 0.09, 1.0, mix(low, deep, 0.35), low, px);
	color = layer_21(color, vec2(p.x + 0.4 * page, p.y), ground - 0.04, 0.1, 4.0, mix(low, deep, 0.65), low, px);
	color = layer_21(color, vec2(0.8 * (p.x + 0.9 * page), p.y), ground - 0.2, 0.12, 9.0, deep, 0.5 * deep, px);

	// The dark theme asks for a quieter picture: dimmer, cooler and less
	// saturated, so white icons and text stay readable over it.
	if (nightMode == 1) {
		float grey = dot(color, vec3(0.3, 0.55, 0.15));
		color = mix(color, grey * vec3(0.7, 0.75, 1.0), 0.4) * 0.75;
	}

	// A little noise against banding in the smooth gradients.
	color += (hash_21(gl_FragCoord.xy) - 0.5) / 255.0;
	gl_FragColor = vec4(color, 1.0);
}

// copy 22

// 0..1 from a cell position.
float hash_22(vec2 c) {
	return fract(sin(dot(c, vec2(27.17, 91.31))) * 41231.7);
}

// A ridge_22 line, -1..1: three sines whose wavelengths do not line up.
float ridge_22(float x, float seed) {
	return 0.5 * sin(1.7 * x + seed) + 0.3 * sin(4.3 * x + 2.1 * seed) + 0.2 * sin(9.7 * x - seed);
}

// Fills everything below a ridge_22 with `tint`, paler towards its foot
// where mist lies in the valley, with a faint rim of sky light on top.
vec3 layer_22(vec3 color, vec2 p, float base, float height, float seed,
		vec3 tint, vec3 mist, float px) {
	float top = base + height * ridge_22(p.x, seed);
	vec3 fill = mix(tint, mist, 0.55 * smoothstep(top - 0.02, top - 0.4, p.y));
	fill = mix(fill, mist, 0.25 * smoothstep(top - 0.025, top, p.y));
	return mix(color, fill, smoothstep(top + px, top - px, p.y));
}

void main_22(void) {
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

	// Read and shape the page: each layer_22 moves by its own share of a
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
	vec2 star = (cell + 0.2 + 0.6 * vec2(hash_22(cell + 3.1), hash_22(cell + 7.7))) / 28.0;
	color += smoothstep(0.05, -0.15, sun) * step(0.93, hash_22(cell)) * smoothstep(1.8 * px, 0.0, distance(sky, star)) * above;
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
	color = layer_22(color, vec2(2.2 * (p.x + 0.15 * page), p.y), ground + 0.1, 0.09, 1.0, mix(low, deep, 0.35), low, px);
	color = layer_22(color, vec2(p.x + 0.4 * page, p.y), ground - 0.04, 0.1, 4.0, mix(low, deep, 0.65), low, px);
	color = layer_22(color, vec2(0.8 * (p.x + 0.9 * page), p.y), ground - 0.2, 0.12, 9.0, deep, 0.5 * deep, px);

	// The dark theme asks for a quieter picture: dimmer, cooler and less
	// saturated, so white icons and text stay readable over it.
	if (nightMode == 1) {
		float grey = dot(color, vec3(0.3, 0.55, 0.15));
		color = mix(color, grey * vec3(0.7, 0.75, 1.0), 0.4) * 0.75;
	}

	// A little noise against banding in the smooth gradients.
	color += (hash_22(gl_FragCoord.xy) - 0.5) / 255.0;
	gl_FragColor = vec4(color, 1.0);
}

// copy 23

// 0..1 from a cell position.
float hash_23(vec2 c) {
	return fract(sin(dot(c, vec2(27.17, 91.31))) * 41231.7);
}

// A ridge_23 line, -1..1: three sines whose wavelengths do not line up.
float ridge_23(float x, float seed) {
	return 0.5 * sin(1.7 * x + seed) + 0.3 * sin(4.3 * x + 2.1 * seed) + 0.2 * sin(9.7 * x - seed);
}

// Fills everything below a ridge_23 with `tint`, paler towards its foot
// where mist lies in the valley, with a faint rim of sky light on top.
vec3 layer_23(vec3 color, vec2 p, float base, float height, float seed,
		vec3 tint, vec3 mist, float px) {
	float top = base + height * ridge_23(p.x, seed);
	vec3 fill = mix(tint, mist, 0.55 * smoothstep(top - 0.02, top - 0.4, p.y));
	fill = mix(fill, mist, 0.25 * smoothstep(top - 0.025, top, p.y));
	return mix(color, fill, smoothstep(top + px, top - px, p.y));
}

void main_23(void) {
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

	// Read and shape the page: each layer_23 moves by its own share of a
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
	vec2 star = (cell + 0.2 + 0.6 * vec2(hash_23(cell + 3.1), hash_23(cell + 7.7))) / 28.0;
	color += smoothstep(0.05, -0.15, sun) * step(0.93, hash_23(cell)) * smoothstep(1.8 * px, 0.0, distance(sky, star)) * above;
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
	color = layer_23(color, vec2(2.2 * (p.x + 0.15 * page), p.y), ground + 0.1, 0.09, 1.0, mix(low, deep, 0.35), low, px);
	color = layer_23(color, vec2(p.x + 0.4 * page, p.y), ground - 0.04, 0.1, 4.0, mix(low, deep, 0.65), low, px);
	color = layer_23(color, vec2(0.8 * (p.x + 0.9 * page), p.y), ground - 0.2, 0.12, 9.0, deep, 0.5 * deep, px);

	// The dark theme asks for a quieter picture: dimmer, cooler and less
	// saturated, so white icons and text stay readable over it.
	if (nightMode == 1) {
		float grey = dot(color, vec3(0.3, 0.55, 0.15));
		color = mix(color, grey * vec3(0.7, 0.75, 1.0), 0.4) * 0.75;
	}

	// A little noise against banding in the smooth gradients.
	color += (hash_23(gl_FragCoord.xy) - 0.5) / 255.0;
	gl_FragColor = vec4(color, 1.0);
}

// copy 24

// 0..1 from a cell position.
float hash_24(vec2 c) {
	return fract(sin(dot(c, vec2(27.17, 91.31))) * 41231.7);
}

// A ridge_24 line, -1..1: three sines whose wavelengths do not line up.
float ridge_24(float x, float seed) {
	return 0.5 * sin(1.7 * x + seed) + 0.3 * sin(4.3 * x + 2.1 * seed) + 0.2 * sin(9.7 * x - seed);
}

// Fills everything below a ridge_24 with `tint`, paler towards its foot
// where mist lies in the valley, with a faint rim of sky light on top.
vec3 layer_24(vec3 color, vec2 p, float base, float height, float seed,
		vec3 tint, vec3 mist, float px) {
	float top = base + height * ridge_24(p.x, seed);
	vec3 fill = mix(tint, mist, 0.55 * smoothstep(top - 0.02, top - 0.4, p.y));
	fill = mix(fill, mist, 0.25 * smoothstep(top - 0.025, top, p.y));
	return mix(color, fill, smoothstep(top + px, top - px, p.y));
}

void main_24(void) {
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

	// Read and shape the page: each layer_24 moves by its own share of a
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
	vec2 star = (cell + 0.2 + 0.6 * vec2(hash_24(cell + 3.1), hash_24(cell + 7.7))) / 28.0;
	color += smoothstep(0.05, -0.15, sun) * step(0.93, hash_24(cell)) * smoothstep(1.8 * px, 0.0, distance(sky, star)) * above;
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
	color = layer_24(color, vec2(2.2 * (p.x + 0.15 * page), p.y), ground + 0.1, 0.09, 1.0, mix(low, deep, 0.35), low, px);
	color = layer_24(color, vec2(p.x + 0.4 * page, p.y), ground - 0.04, 0.1, 4.0, mix(low, deep, 0.65), low, px);
	color = layer_24(color, vec2(0.8 * (p.x + 0.9 * page), p.y), ground - 0.2, 0.12, 9.0, deep, 0.5 * deep, px);

	// The dark theme asks for a quieter picture: dimmer, cooler and less
	// saturated, so white icons and text stay readable over it.
	if (nightMode == 1) {
		float grey = dot(color, vec3(0.3, 0.55, 0.15));
		color = mix(color, grey * vec3(0.7, 0.75, 1.0), 0.4) * 0.75;
	}

	// A little noise against banding in the smooth gradients.
	color += (hash_24(gl_FragCoord.xy) - 0.5) / 255.0;
	gl_FragColor = vec4(color, 1.0);
}

// copy 25

// 0..1 from a cell position.
float hash_25(vec2 c) {
	return fract(sin(dot(c, vec2(27.17, 91.31))) * 41231.7);
}

// A ridge_25 line, -1..1: three sines whose wavelengths do not line up.
float ridge_25(float x, float seed) {
	return 0.5 * sin(1.7 * x + seed) + 0.3 * sin(4.3 * x + 2.1 * seed) + 0.2 * sin(9.7 * x - seed);
}

// Fills everything below a ridge_25 with `tint`, paler towards its foot
// where mist lies in the valley, with a faint rim of sky light on top.
vec3 layer_25(vec3 color, vec2 p, float base, float height, float seed,
		vec3 tint, vec3 mist, float px) {
	float top = base + height * ridge_25(p.x, seed);
	vec3 fill = mix(tint, mist, 0.55 * smoothstep(top - 0.02, top - 0.4, p.y));
	fill = mix(fill, mist, 0.25 * smoothstep(top - 0.025, top, p.y));
	return mix(color, fill, smoothstep(top + px, top - px, p.y));
}

void main_25(void) {
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

	// Read and shape the page: each layer_25 moves by its own share of a
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
	vec2 star = (cell + 0.2 + 0.6 * vec2(hash_25(cell + 3.1), hash_25(cell + 7.7))) / 28.0;
	color += smoothstep(0.05, -0.15, sun) * step(0.93, hash_25(cell)) * smoothstep(1.8 * px, 0.0, distance(sky, star)) * above;
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
	color = layer_25(color, vec2(2.2 * (p.x + 0.15 * page), p.y), ground + 0.1, 0.09, 1.0, mix(low, deep, 0.35), low, px);
	color = layer_25(color, vec2(p.x + 0.4 * page, p.y), ground - 0.04, 0.1, 4.0, mix(low, deep, 0.65), low, px);
	color = layer_25(color, vec2(0.8 * (p.x + 0.9 * page), p.y), ground - 0.2, 0.12, 9.0, deep, 0.5 * deep, px);

	// The dark theme asks for a quieter picture: dimmer, cooler and less
	// saturated, so white icons and text stay readable over it.
	if (nightMode == 1) {
		float grey = dot(color, vec3(0.3, 0.55, 0.15));
		color = mix(color, grey * vec3(0.7, 0.75, 1.0), 0.4) * 0.75;
	}

	// A little noise against banding in the smooth gradients.
	color += (hash_25(gl_FragCoord.xy) - 0.5) / 255.0;
	gl_FragColor = vec4(color, 1.0);
}

// copy 26

// 0..1 from a cell position.
float hash_26(vec2 c) {
	return fract(sin(dot(c, vec2(27.17, 91.31))) * 41231.7);
}

// A ridge_26 line, -1..1: three sines whose wavelengths do not line up.
float ridge_26(float x, float seed) {
	return 0.5 * sin(1.7 * x + seed) + 0.3 * sin(4.3 * x + 2.1 * seed) + 0.2 * sin(9.7 * x - seed);
}

// Fills everything below a ridge_26 with `tint`, paler towards its foot
// where mist lies in the valley, with a faint rim of sky light on top.
vec3 layer_26(vec3 color, vec2 p, float base, float height, float seed,
		vec3 tint, vec3 mist, float px) {
	float top = base + height * ridge_26(p.x, seed);
	vec3 fill = mix(tint, mist, 0.55 * smoothstep(top - 0.02, top - 0.4, p.y));
	fill = mix(fill, mist, 0.25 * smoothstep(top - 0.025, top, p.y));
	return mix(color, fill, smoothstep(top + px, top - px, p.y));
}

void main_26(void) {
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

	// Read and shape the page: each layer_26 moves by its own share of a
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
	vec2 star = (cell + 0.2 + 0.6 * vec2(hash_26(cell + 3.1), hash_26(cell + 7.7))) / 28.0;
	color += smoothstep(0.05, -0.15, sun) * step(0.93, hash_26(cell)) * smoothstep(1.8 * px, 0.0, distance(sky, star)) * above;
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
	color = layer_26(color, vec2(2.2 * (p.x + 0.15 * page), p.y), ground + 0.1, 0.09, 1.0, mix(low, deep, 0.35), low, px);
	color = layer_26(color, vec2(p.x + 0.4 * page, p.y), ground - 0.04, 0.1, 4.0, mix(low, deep, 0.65), low, px);
	color = layer_26(color, vec2(0.8 * (p.x + 0.9 * page), p.y), ground - 0.2, 0.12, 9.0, deep, 0.5 * deep, px);

	// The dark theme asks for a quieter picture: dimmer, cooler and less
	// saturated, so white icons and text stay readable over it.
	if (nightMode == 1) {
		float grey = dot(color, vec3(0.3, 0.55, 0.15));
		color = mix(color, grey * vec3(0.7, 0.75, 1.0), 0.4) * 0.75;
	}

	// A little noise against banding in the smooth gradients.
	color += (hash_26(gl_FragCoord.xy) - 0.5) / 255.0;
	gl_FragColor = vec4(color, 1.0);
}

// copy 27

// 0..1 from a cell position.
float hash_27(vec2 c) {
	return fract(sin(dot(c, vec2(27.17, 91.31))) * 41231.7);
}

// A ridge_27 line, -1..1: three sines whose wavelengths do not line up.
float ridge_27(float x, float seed) {
	return 0.5 * sin(1.7 * x + seed) + 0.3 * sin(4.3 * x + 2.1 * seed) + 0.2 * sin(9.7 * x - seed);
}

// Fills everything below a ridge_27 with `tint`, paler towards its foot
// where mist lies in the valley, with a faint rim of sky light on top.
vec3 layer_27(vec3 color, vec2 p, float base, float height, float seed,
		vec3 tint, vec3 mist, float px) {
	float top = base + height * ridge_27(p.x, seed);
	vec3 fill = mix(tint, mist, 0.55 * smoothstep(top - 0.02, top - 0.4, p.y));
	fill = mix(fill, mist, 0.25 * smoothstep(top - 0.025, top, p.y));
	return mix(color, fill, smoothstep(top + px, top - px, p.y));
}

void main_27(void) {
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

	// Read and shape the page: each layer_27 moves by its own share of a
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
	vec2 star = (cell + 0.2 + 0.6 * vec2(hash_27(cell + 3.1), hash_27(cell + 7.7))) / 28.0;
	color += smoothstep(0.05, -0.15, sun) * step(0.93, hash_27(cell)) * smoothstep(1.8 * px, 0.0, distance(sky, star)) * above;
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
	color = layer_27(color, vec2(2.2 * (p.x + 0.15 * page), p.y), ground + 0.1, 0.09, 1.0, mix(low, deep, 0.35), low, px);
	color = layer_27(color, vec2(p.x + 0.4 * page, p.y), ground - 0.04, 0.1, 4.0, mix(low, deep, 0.65), low, px);
	color = layer_27(color, vec2(0.8 * (p.x + 0.9 * page), p.y), ground - 0.2, 0.12, 9.0, deep, 0.5 * deep, px);

	// The dark theme asks for a quieter picture: dimmer, cooler and less
	// saturated, so white icons and text stay readable over it.
	if (nightMode == 1) {
		float grey = dot(color, vec3(0.3, 0.55, 0.15));
		color = mix(color, grey * vec3(0.7, 0.75, 1.0), 0.4) * 0.75;
	}

	// A little noise against banding in the smooth gradients.
	color += (hash_27(gl_FragCoord.xy) - 0.5) / 255.0;
	gl_FragColor = vec4(color, 1.0);
}

// copy 28

// 0..1 from a cell position.
float hash_28(vec2 c) {
	return fract(sin(dot(c, vec2(27.17, 91.31))) * 41231.7);
}

// A ridge_28 line, -1..1: three sines whose wavelengths do not line up.
float ridge_28(float x, float seed) {
	return 0.5 * sin(1.7 * x + seed) + 0.3 * sin(4.3 * x + 2.1 * seed) + 0.2 * sin(9.7 * x - seed);
}

// Fills everything below a ridge_28 with `tint`, paler towards its foot
// where mist lies in the valley, with a faint rim of sky light on top.
vec3 layer_28(vec3 color, vec2 p, float base, float height, float seed,
		vec3 tint, vec3 mist, float px) {
	float top = base + height * ridge_28(p.x, seed);
	vec3 fill = mix(tint, mist, 0.55 * smoothstep(top - 0.02, top - 0.4, p.y));
	fill = mix(fill, mist, 0.25 * smoothstep(top - 0.025, top, p.y));
	return mix(color, fill, smoothstep(top + px, top - px, p.y));
}

void main_28(void) {
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

	// Read and shape the page: each layer_28 moves by its own share of a
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
	vec2 star = (cell + 0.2 + 0.6 * vec2(hash_28(cell + 3.1), hash_28(cell + 7.7))) / 28.0;
	color += smoothstep(0.05, -0.15, sun) * step(0.93, hash_28(cell)) * smoothstep(1.8 * px, 0.0, distance(sky, star)) * above;
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
	color = layer_28(color, vec2(2.2 * (p.x + 0.15 * page), p.y), ground + 0.1, 0.09, 1.0, mix(low, deep, 0.35), low, px);
	color = layer_28(color, vec2(p.x + 0.4 * page, p.y), ground - 0.04, 0.1, 4.0, mix(low, deep, 0.65), low, px);
	color = layer_28(color, vec2(0.8 * (p.x + 0.9 * page), p.y), ground - 0.2, 0.12, 9.0, deep, 0.5 * deep, px);

	// The dark theme asks for a quieter picture: dimmer, cooler and less
	// saturated, so white icons and text stay readable over it.
	if (nightMode == 1) {
		float grey = dot(color, vec3(0.3, 0.55, 0.15));
		color = mix(color, grey * vec3(0.7, 0.75, 1.0), 0.4) * 0.75;
	}

	// A little noise against banding in the smooth gradients.
	color += (hash_28(gl_FragCoord.xy) - 0.5) / 255.0;
	gl_FragColor = vec4(color, 1.0);
}

// copy 29

// 0..1 from a cell position.
float hash_29(vec2 c) {
	return fract(sin(dot(c, vec2(27.17, 91.31))) * 41231.7);
}

// A ridge_29 line, -1..1: three sines whose wavelengths do not line up.
float ridge_29(float x, float seed) {
	return 0.5 * sin(1.7 * x + seed) + 0.3 * sin(4.3 * x + 2.1 * seed) + 0.2 * sin(9.7 * x - seed);
}

// Fills everything below a ridge_29 with `tint`, paler towards its foot
// where mist lies in the valley, with a faint rim of sky light on top.
vec3 layer_29(vec3 color, vec2 p, float base, float height, float seed,
		vec3 tint, vec3 mist, float px) {
	float top = base + height * ridge_29(p.x, seed);
	vec3 fill = mix(tint, mist, 0.55 * smoothstep(top - 0.02, top - 0.4, p.y));
	fill = mix(fill, mist, 0.25 * smoothstep(top - 0.025, top, p.y));
	return mix(color, fill, smoothstep(top + px, top - px, p.y));
}

void main_29(void) {
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

	// Read and shape the page: each layer_29 moves by its own share of a
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
	vec2 star = (cell + 0.2 + 0.6 * vec2(hash_29(cell + 3.1), hash_29(cell + 7.7))) / 28.0;
	color += smoothstep(0.05, -0.15, sun) * step(0.93, hash_29(cell)) * smoothstep(1.8 * px, 0.0, distance(sky, star)) * above;
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
	color = layer_29(color, vec2(2.2 * (p.x + 0.15 * page), p.y), ground + 0.1, 0.09, 1.0, mix(low, deep, 0.35), low, px);
	color = layer_29(color, vec2(p.x + 0.4 * page, p.y), ground - 0.04, 0.1, 4.0, mix(low, deep, 0.65), low, px);
	color = layer_29(color, vec2(0.8 * (p.x + 0.9 * page), p.y), ground - 0.2, 0.12, 9.0, deep, 0.5 * deep, px);

	// The dark theme asks for a quieter picture: dimmer, cooler and less
	// saturated, so white icons and text stay readable over it.
	if (nightMode == 1) {
		float grey = dot(color, vec3(0.3, 0.55, 0.15));
		color = mix(color, grey * vec3(0.7, 0.75, 1.0), 0.4) * 0.75;
	}

	// A little noise against banding in the smooth gradients.
	color += (hash_29(gl_FragCoord.xy) - 0.5) / 255.0;
	gl_FragColor = vec4(color, 1.0);
}

// copy 30

// 0..1 from a cell position.
float hash_30(vec2 c) {
	return fract(sin(dot(c, vec2(27.17, 91.31))) * 41231.7);
}

// A ridge_30 line, -1..1: three sines whose wavelengths do not line up.
float ridge_30(float x, float seed) {
	return 0.5 * sin(1.7 * x + seed) + 0.3 * sin(4.3 * x + 2.1 * seed) + 0.2 * sin(9.7 * x - seed);
}

// Fills everything below a ridge_30 with `tint`, paler towards its foot
// where mist lies in the valley, with a faint rim of sky light on top.
vec3 layer_30(vec3 color, vec2 p, float base, float height, float seed,
		vec3 tint, vec3 mist, float px) {
	float top = base + height * ridge_30(p.x, seed);
	vec3 fill = mix(tint, mist, 0.55 * smoothstep(top - 0.02, top - 0.4, p.y));
	fill = mix(fill, mist, 0.25 * smoothstep(top - 0.025, top, p.y));
	return mix(color, fill, smoothstep(top + px, top - px, p.y));
}

void main_30(void) {
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

	// Read and shape the page: each layer_30 moves by its own share of a
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
	vec2 star = (cell + 0.2 + 0.6 * vec2(hash_30(cell + 3.1), hash_30(cell + 7.7))) / 28.0;
	color += smoothstep(0.05, -0.15, sun) * step(0.93, hash_30(cell)) * smoothstep(1.8 * px, 0.0, distance(sky, star)) * above;
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
	color = layer_30(color, vec2(2.2 * (p.x + 0.15 * page), p.y), ground + 0.1, 0.09, 1.0, mix(low, deep, 0.35), low, px);
	color = layer_30(color, vec2(p.x + 0.4 * page, p.y), ground - 0.04, 0.1, 4.0, mix(low, deep, 0.65), low, px);
	color = layer_30(color, vec2(0.8 * (p.x + 0.9 * page), p.y), ground - 0.2, 0.12, 9.0, deep, 0.5 * deep, px);

	// The dark theme asks for a quieter picture: dimmer, cooler and less
	// saturated, so white icons and text stay readable over it.
	if (nightMode == 1) {
		float grey = dot(color, vec3(0.3, 0.55, 0.15));
		color = mix(color, grey * vec3(0.7, 0.75, 1.0), 0.4) * 0.75;
	}

	// A little noise against banding in the smooth gradients.
	color += (hash_30(gl_FragCoord.xy) - 0.5) / 255.0;
	gl_FragColor = vec4(color, 1.0);
}

// copy 31

// 0..1 from a cell position.
float hash_31(vec2 c) {
	return fract(sin(dot(c, vec2(27.17, 91.31))) * 41231.7);
}

// A ridge_31 line, -1..1: three sines whose wavelengths do not line up.
float ridge_31(float x, float seed) {
	return 0.5 * sin(1.7 * x + seed) + 0.3 * sin(4.3 * x + 2.1 * seed) + 0.2 * sin(9.7 * x - seed);
}

// Fills everything below a ridge_31 with `tint`, paler towards its foot
// where mist lies in the valley, with a faint rim of sky light on top.
vec3 layer_31(vec3 color, vec2 p, float base, float height, float seed,
		vec3 tint, vec3 mist, float px) {
	float top = base + height * ridge_31(p.x, seed);
	vec3 fill = mix(tint, mist, 0.55 * smoothstep(top - 0.02, top - 0.4, p.y));
	fill = mix(fill, mist, 0.25 * smoothstep(top - 0.025, top, p.y));
	return mix(color, fill, smoothstep(top + px, top - px, p.y));
}

void main_31(void) {
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

	// Read and shape the page: each layer_31 moves by its own share of a
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
	vec2 star = (cell + 0.2 + 0.6 * vec2(hash_31(cell + 3.1), hash_31(cell + 7.7))) / 28.0;
	color += smoothstep(0.05, -0.15, sun) * step(0.93, hash_31(cell)) * smoothstep(1.8 * px, 0.0, distance(sky, star)) * above;
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
	color = layer_31(color, vec2(2.2 * (p.x + 0.15 * page), p.y), ground + 0.1, 0.09, 1.0, mix(low, deep, 0.35), low, px);
	color = layer_31(color, vec2(p.x + 0.4 * page, p.y), ground - 0.04, 0.1, 4.0, mix(low, deep, 0.65), low, px);
	color = layer_31(color, vec2(0.8 * (p.x + 0.9 * page), p.y), ground - 0.2, 0.12, 9.0, deep, 0.5 * deep, px);

	// The dark theme asks for a quieter picture: dimmer, cooler and less
	// saturated, so white icons and text stay readable over it.
	if (nightMode == 1) {
		float grey = dot(color, vec3(0.3, 0.55, 0.15));
		color = mix(color, grey * vec3(0.7, 0.75, 1.0), 0.4) * 0.75;
	}

	// A little noise against banding in the smooth gradients.
	color += (hash_31(gl_FragCoord.xy) - 0.5) / 255.0;
	gl_FragColor = vec4(color, 1.0);
}

// copy 32

// 0..1 from a cell position.
float hash_32(vec2 c) {
	return fract(sin(dot(c, vec2(27.17, 91.31))) * 41231.7);
}

// A ridge_32 line, -1..1: three sines whose wavelengths do not line up.
float ridge_32(float x, float seed) {
	return 0.5 * sin(1.7 * x + seed) + 0.3 * sin(4.3 * x + 2.1 * seed) + 0.2 * sin(9.7 * x - seed);
}

// Fills everything below a ridge_32 with `tint`, paler towards its foot
// where mist lies in the valley, with a faint rim of sky light on top.
vec3 layer_32(vec3 color, vec2 p, float base, float height, float seed,
		vec3 tint, vec3 mist, float px) {
	float top = base + height * ridge_32(p.x, seed);
	vec3 fill = mix(tint, mist, 0.55 * smoothstep(top - 0.02, top - 0.4, p.y));
	fill = mix(fill, mist, 0.25 * smoothstep(top - 0.025, top, p.y));
	return mix(color, fill, smoothstep(top + px, top - px, p.y));
}

void main_32(void) {
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

	// Read and shape the page: each layer_32 moves by its own share of a
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
	vec2 star = (cell + 0.2 + 0.6 * vec2(hash_32(cell + 3.1), hash_32(cell + 7.7))) / 28.0;
	color += smoothstep(0.05, -0.15, sun) * step(0.93, hash_32(cell)) * smoothstep(1.8 * px, 0.0, distance(sky, star)) * above;
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
	color = layer_32(color, vec2(2.2 * (p.x + 0.15 * page), p.y), ground + 0.1, 0.09, 1.0, mix(low, deep, 0.35), low, px);
	color = layer_32(color, vec2(p.x + 0.4 * page, p.y), ground - 0.04, 0.1, 4.0, mix(low, deep, 0.65), low, px);
	color = layer_32(color, vec2(0.8 * (p.x + 0.9 * page), p.y), ground - 0.2, 0.12, 9.0, deep, 0.5 * deep, px);

	// The dark theme asks for a quieter picture: dimmer, cooler and less
	// saturated, so white icons and text stay readable over it.
	if (nightMode == 1) {
		float grey = dot(color, vec3(0.3, 0.55, 0.15));
		color = mix(color, grey * vec3(0.7, 0.75, 1.0), 0.4) * 0.75;
	}

	// A little noise against banding in the smooth gradients.
	color += (hash_32(gl_FragCoord.xy) - 0.5) / 255.0;
	gl_FragColor = vec4(color, 1.0);
}

// copy 33

// 0..1 from a cell position.
float hash_33(vec2 c) {
	return fract(sin(dot(c, vec2(27.17, 91.31))) * 41231.7);
}

// A ridge_33 line, -1..1: three sines whose wavelengths do not line up.
float ridge_33(float x, float seed) {
	return 0.5 * sin(1.7 * x + seed) + 0.3 * sin(4.3 * x + 2.1 * seed) + 0.2 * sin(9.7 * x - seed);
}

// Fills everything below a ridge_33 with `tint`, paler towards its foot
// where mist lies in the valley, with a faint rim of sky light on top.
vec3 layer_33(vec3 color, vec2 p, float base, float height, float seed,
		vec3 tint, vec3 mist, float px) {
	float top = base + height * ridge_33(p.x, seed);
	vec3 fill = mix(tint, mist, 0.55 * smoothstep(top - 0.02, top - 0.4, p.y));
	fill = mix(fill, mist, 0.25 * smoothstep(top - 0.025, top, p.y));
	return mix(color, fill, smoothstep(top + px, top - px, p.y));
}

void main_33(void) {
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

	// Read and shape the page: each layer_33 moves by its own share of a
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
	vec2 star = (cell + 0.2 + 0.6 * vec2(hash_33(cell + 3.1), hash_33(cell + 7.7))) / 28.0;
	color += smoothstep(0.05, -0.15, sun) * step(0.93, hash_33(cell)) * smoothstep(1.8 * px, 0.0, distance(sky, star)) * above;
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
	color = layer_33(color, vec2(2.2 * (p.x + 0.15 * page), p.y), ground + 0.1, 0.09, 1.0, mix(low, deep, 0.35), low, px);
	color = layer_33(color, vec2(p.x + 0.4 * page, p.y), ground - 0.04, 0.1, 4.0, mix(low, deep, 0.65), low, px);
	color = layer_33(color, vec2(0.8 * (p.x + 0.9 * page), p.y), ground - 0.2, 0.12, 9.0, deep, 0.5 * deep, px);

	// The dark theme asks for a quieter picture: dimmer, cooler and less
	// saturated, so white icons and text stay readable over it.
	if (nightMode == 1) {
		float grey = dot(color, vec3(0.3, 0.55, 0.15));
		color = mix(color, grey * vec3(0.7, 0.75, 1.0), 0.4) * 0.75;
	}

	// A little noise against banding in the smooth gradients.
	color += (hash_33(gl_FragCoord.xy) - 0.5) / 255.0;
	gl_FragColor = vec4(color, 1.0);
}

// copy 34

// 0..1 from a cell position.
float hash_34(vec2 c) {
	return fract(sin(dot(c, vec2(27.17, 91.31))) * 41231.7);
}

// A ridge_34 line, -1..1: three sines whose wavelengths do not line up.
float ridge_34(float x, float seed) {
	return 0.5 * sin(1.7 * x + seed) + 0.3 * sin(4.3 * x + 2.1 * seed) + 0.2 * sin(9.7 * x - seed);
}

// Fills everything below a ridge_34 with `tint`, paler towards its foot
// where mist lies in the valley, with a faint rim of sky light on top.
vec3 layer_34(vec3 color, vec2 p, float base, float height, float seed,
		vec3 tint, vec3 mist, float px) {
	float top = base + height * ridge_34(p.x, seed);
	vec3 fill = mix(tint, mist, 0.55 * smoothstep(top - 0.02, top - 0.4, p.y));
	fill = mix(fill, mist, 0.25 * smoothstep(top - 0.025, top, p.y));
	return mix(color, fill, smoothstep(top + px, top - px, p.y));
}

void main_34(void) {
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

	// Read and shape the page: each layer_34 moves by its own share of a
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
	vec2 star = (cell + 0.2 + 0.6 * vec2(hash_34(cell + 3.1), hash_34(cell + 7.7))) / 28.0;
	color += smoothstep(0.05, -0.15, sun) * step(0.93, hash_34(cell)) * smoothstep(1.8 * px, 0.0, distance(sky, star)) * above;
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
	color = layer_34(color, vec2(2.2 * (p.x + 0.15 * page), p.y), ground + 0.1, 0.09, 1.0, mix(low, deep, 0.35), low, px);
	color = layer_34(color, vec2(p.x + 0.4 * page, p.y), ground - 0.04, 0.1, 4.0, mix(low, deep, 0.65), low, px);
	color = layer_34(color, vec2(0.8 * (p.x + 0.9 * page), p.y), ground - 0.2, 0.12, 9.0, deep, 0.5 * deep, px);

	// The dark theme asks for a quieter picture: dimmer, cooler and less
	// saturated, so white icons and text stay readable over it.
	if (nightMode == 1) {
		float grey = dot(color, vec3(0.3, 0.55, 0.15));
		color = mix(color, grey * vec3(0.7, 0.75, 1.0), 0.4) * 0.75;
	}

	// A little noise against banding in the smooth gradients.
	color += (hash_34(gl_FragCoord.xy) - 0.5) / 255.0;
	gl_FragColor = vec4(color, 1.0);
}

// copy 35

// 0..1 from a cell position.
float hash_35(vec2 c) {
	return fract(sin(dot(c, vec2(27.17, 91.31))) * 41231.7);
}

// A ridge_35 line, -1..1: three sines whose wavelengths do not line up.
float ridge_35(float x, float seed) {
	return 0.5 * sin(1.7 * x + seed) + 0.3 * sin(4.3 * x + 2.1 * seed) + 0.2 * sin(9.7 * x - seed);
}

// Fills everything below a ridge_35 with `tint`, paler towards its foot
// where mist lies in the valley, with a faint rim of sky light on top.
vec3 layer_35(vec3 color, vec2 p, float base, float height, float seed,
		vec3 tint, vec3 mist, float px) {
	float top = base + height * ridge_35(p.x, seed);
	vec3 fill = mix(tint, mist, 0.55 * smoothstep(top - 0.02, top - 0.4, p.y));
	fill = mix(fill, mist, 0.25 * smoothstep(top - 0.025, top, p.y));
	return mix(color, fill, smoothstep(top + px, top - px, p.y));
}

void main_35(void) {
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

	// Read and shape the page: each layer_35 moves by its own share of a
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
	vec2 star = (cell + 0.2 + 0.6 * vec2(hash_35(cell + 3.1), hash_35(cell + 7.7))) / 28.0;
	color += smoothstep(0.05, -0.15, sun) * step(0.93, hash_35(cell)) * smoothstep(1.8 * px, 0.0, distance(sky, star)) * above;
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
	color = layer_35(color, vec2(2.2 * (p.x + 0.15 * page), p.y), ground + 0.1, 0.09, 1.0, mix(low, deep, 0.35), low, px);
	color = layer_35(color, vec2(p.x + 0.4 * page, p.y), ground - 0.04, 0.1, 4.0, mix(low, deep, 0.65), low, px);
	color = layer_35(color, vec2(0.8 * (p.x + 0.9 * page), p.y), ground - 0.2, 0.12, 9.0, deep, 0.5 * deep, px);

	// The dark theme asks for a quieter picture: dimmer, cooler and less
	// saturated, so white icons and text stay readable over it.
	if (nightMode == 1) {
		float grey = dot(color, vec3(0.3, 0.55, 0.15));
		color = mix(color, grey * vec3(0.7, 0.75, 1.0), 0.4) * 0.75;
	}

	// A little noise against banding in the smooth gradients.
	color += (hash_35(gl_FragCoord.xy) - 0.5) / 255.0;
	gl_FragColor = vec4(color, 1.0);
}

// copy 36

// 0..1 from a cell position.
float hash_36(vec2 c) {
	return fract(sin(dot(c, vec2(27.17, 91.31))) * 41231.7);
}

// A ridge_36 line, -1..1: three sines whose wavelengths do not line up.
float ridge_36(float x, float seed) {
	return 0.5 * sin(1.7 * x + seed) + 0.3 * sin(4.3 * x + 2.1 * seed) + 0.2 * sin(9.7 * x - seed);
}

// Fills everything below a ridge_36 with `tint`, paler towards its foot
// where mist lies in the valley, with a faint rim of sky light on top.
vec3 layer_36(vec3 color, vec2 p, float base, float height, float seed,
		vec3 tint, vec3 mist, float px) {
	float top = base + height * ridge_36(p.x, seed);
	vec3 fill = mix(tint, mist, 0.55 * smoothstep(top - 0.02, top - 0.4, p.y));
	fill = mix(fill, mist, 0.25 * smoothstep(top - 0.025, top, p.y));
	return mix(color, fill, smoothstep(top + px, top - px, p.y));
}

void main_36(void) {
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

	// Read and shape the page: each layer_36 moves by its own share of a
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
	vec2 star = (cell + 0.2 + 0.6 * vec2(hash_36(cell + 3.1), hash_36(cell + 7.7))) / 28.0;
	color += smoothstep(0.05, -0.15, sun) * step(0.93, hash_36(cell)) * smoothstep(1.8 * px, 0.0, distance(sky, star)) * above;
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
	color = layer_36(color, vec2(2.2 * (p.x + 0.15 * page), p.y), ground + 0.1, 0.09, 1.0, mix(low, deep, 0.35), low, px);
	color = layer_36(color, vec2(p.x + 0.4 * page, p.y), ground - 0.04, 0.1, 4.0, mix(low, deep, 0.65), low, px);
	color = layer_36(color, vec2(0.8 * (p.x + 0.9 * page), p.y), ground - 0.2, 0.12, 9.0, deep, 0.5 * deep, px);

	// The dark theme asks for a quieter picture: dimmer, cooler and less
	// saturated, so white icons and text stay readable over it.
	if (nightMode == 1) {
		float grey = dot(color, vec3(0.3, 0.55, 0.15));
		color = mix(color, grey * vec3(0.7, 0.75, 1.0), 0.4) * 0.75;
	}

	// A little noise against banding in the smooth gradients.
	color += (hash_36(gl_FragCoord.xy) - 0.5) / 255.0;
	gl_FragColor = vec4(color, 1.0);
}

// copy 37

// 0..1 from a cell position.
float hash_37(vec2 c) {
	return fract(sin(dot(c, vec2(27.17, 91.31))) * 41231.7);
}

// A ridge_37 line, -1..1: three sines whose wavelengths do not line up.
float ridge_37(float x, float seed) {
	return 0.5 * sin(1.7 * x + seed) + 0.3 * sin(4.3 * x + 2.1 * seed) + 0.2 * sin(9.7 * x - seed);
}

// Fills everything below a ridge_37 with `tint`, paler towards its foot
// where mist lies in the valley, with a faint rim of sky light on top.
vec3 layer_37(vec3 color, vec2 p, float base, float height, float seed,
		vec3 tint, vec3 mist, float px) {
	float top = base + height * ridge_37(p.x, seed);
	vec3 fill = mix(tint, mist, 0.55 * smoothstep(top - 0.02, top - 0.4, p.y));
	fill = mix(fill, mist, 0.25 * smoothstep(top - 0.025, top, p.y));
	return mix(color, fill, smoothstep(top + px, top - px, p.y));
}

void main_37(void) {
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

	// Read and shape the page: each layer_37 moves by its own share of a
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
	vec2 star = (cell + 0.2 + 0.6 * vec2(hash_37(cell + 3.1), hash_37(cell + 7.7))) / 28.0;
	color += smoothstep(0.05, -0.15, sun) * step(0.93, hash_37(cell)) * smoothstep(1.8 * px, 0.0, distance(sky, star)) * above;
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
	color = layer_37(color, vec2(2.2 * (p.x + 0.15 * page), p.y), ground + 0.1, 0.09, 1.0, mix(low, deep, 0.35), low, px);
	color = layer_37(color, vec2(p.x + 0.4 * page, p.y), ground - 0.04, 0.1, 4.0, mix(low, deep, 0.65), low, px);
	color = layer_37(color, vec2(0.8 * (p.x + 0.9 * page), p.y), ground - 0.2, 0.12, 9.0, deep, 0.5 * deep, px);

	// The dark theme asks for a quieter picture: dimmer, cooler and less
	// saturated, so white icons and text stay readable over it.
	if (nightMode == 1) {
		float grey = dot(color, vec3(0.3, 0.55, 0.15));
		color = mix(color, grey * vec3(0.7, 0.75, 1.0), 0.4) * 0.75;
	}

	// A little noise against banding in the smooth gradients.
	color += (hash_37(gl_FragCoord.xy) - 0.5) / 255.0;
	gl_FragColor = vec4(color, 1.0);
}

// copy 38

// 0..1 from a cell position.
float hash_38(vec2 c) {
	return fract(sin(dot(c, vec2(27.17, 91.31))) * 41231.7);
}

// A ridge_38 line, -1..1: three sines whose wavelengths do not line up.
float ridge_38(float x, float seed) {
	return 0.5 * sin(1.7 * x + seed) + 0.3 * sin(4.3 * x + 2.1 * seed) + 0.2 * sin(9.7 * x - seed);
}

// Fills everything below a ridge_38 with `tint`, paler towards its foot
// where mist lies in the valley, with a faint rim of sky light on top.
vec3 layer_38(vec3 color, vec2 p, float base, float height, float seed,
		vec3 tint, vec3 mist, float px) {
	float top = base + height * ridge_38(p.x, seed);
	vec3 fill = mix(tint, mist, 0.55 * smoothstep(top - 0.02, top - 0.4, p.y));
	fill = mix(fill, mist, 0.25 * smoothstep(top - 0.025, top, p.y));
	return mix(color, fill, smoothstep(top + px, top - px, p.y));
}

void main_38(void) {
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

	// Read and shape the page: each layer_38 moves by its own share of a
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
	vec2 star = (cell + 0.2 + 0.6 * vec2(hash_38(cell + 3.1), hash_38(cell + 7.7))) / 28.0;
	color += smoothstep(0.05, -0.15, sun) * step(0.93, hash_38(cell)) * smoothstep(1.8 * px, 0.0, distance(sky, star)) * above;
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
	color = layer_38(color, vec2(2.2 * (p.x + 0.15 * page), p.y), ground + 0.1, 0.09, 1.0, mix(low, deep, 0.35), low, px);
	color = layer_38(color, vec2(p.x + 0.4 * page, p.y), ground - 0.04, 0.1, 4.0, mix(low, deep, 0.65), low, px);
	color = layer_38(color, vec2(0.8 * (p.x + 0.9 * page), p.y), ground - 0.2, 0.12, 9.0, deep, 0.5 * deep, px);

	// The dark theme asks for a quieter picture: dimmer, cooler and less
	// saturated, so white icons and text stay readable over it.
	if (nightMode == 1) {
		float grey = dot(color, vec3(0.3, 0.55, 0.15));
		color = mix(color, grey * vec3(0.7, 0.75, 1.0), 0.4) * 0.75;
	}

	// A little noise against banding in the smooth gradients.
	color += (hash_38(gl_FragCoord.xy) - 0.5) / 255.0;
	gl_FragColor = vec4(color, 1.0);
}

// copy 39

// 0..1 from a cell position.
float hash_39(vec2 c) {
	return fract(sin(dot(c, vec2(27.17, 91.31))) * 41231.7);
}

// A ridge_39 line, -1..1: three sines whose wavelengths do not line up.
float ridge_39(float x, float seed) {
	return 0.5 * sin(1.7 * x + seed) + 0.3 * sin(4.3 * x + 2.1 * seed) + 0.2 * sin(9.7 * x - seed);
}

// Fills everything below a ridge_39 with `tint`, paler towards its foot
// where mist lies in the valley, with a faint rim of sky light on top.
vec3 layer_39(vec3 color, vec2 p, float base, float height, float seed,
		vec3 tint, vec3 mist, float px) {
	float top = base + height * ridge_39(p.x, seed);
	vec3 fill = mix(tint, mist, 0.55 * smoothstep(top - 0.02, top - 0.4, p.y));
	fill = mix(fill, mist, 0.25 * smoothstep(top - 0.025, top, p.y));
	return mix(color, fill, smoothstep(top + px, top - px, p.y));
}

void main_39(void) {
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

	// Read and shape the page: each layer_39 moves by its own share of a
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
	vec2 star = (cell + 0.2 + 0.6 * vec2(hash_39(cell + 3.1), hash_39(cell + 7.7))) / 28.0;
	color += smoothstep(0.05, -0.15, sun) * step(0.93, hash_39(cell)) * smoothstep(1.8 * px, 0.0, distance(sky, star)) * above;
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
	color = layer_39(color, vec2(2.2 * (p.x + 0.15 * page), p.y), ground + 0.1, 0.09, 1.0, mix(low, deep, 0.35), low, px);
	color = layer_39(color, vec2(p.x + 0.4 * page, p.y), ground - 0.04, 0.1, 4.0, mix(low, deep, 0.65), low, px);
	color = layer_39(color, vec2(0.8 * (p.x + 0.9 * page), p.y), ground - 0.2, 0.12, 9.0, deep, 0.5 * deep, px);

	// The dark theme asks for a quieter picture: dimmer, cooler and less
	// saturated, so white icons and text stay readable over it.
	if (nightMode == 1) {
		float grey = dot(color, vec3(0.3, 0.55, 0.15));
		color = mix(color, grey * vec3(0.7, 0.75, 1.0), 0.4) * 0.75;
	}

	// A little noise against banding in the smooth gradients.
	color += (hash_39(gl_FragCoord.xy) - 0.5) / 255.0;
	gl_FragColor = vec4(color, 1.0);
}

// copy 40

// 0..1 from a cell position.
float hash_40(vec2 c) {
	return fract(sin(dot(c, vec2(27.17, 91.31))) * 41231.7);
}

// A ridge_40 line, -1..1: three sines whose wavelengths do not line up.
float ridge_40(float x, float seed) {
	return 0.5 * sin(1.7 * x + seed) + 0.3 * sin(4.3 * x + 2.1 * seed) + 0.2 * sin(9.7 * x - seed);
}

// Fills everything below a ridge_40 with `tint`, paler towards its foot
// where mist lies in the valley, with a faint rim of sky light on top.
vec3 layer_40(vec3 color, vec2 p, float base, float height, float seed,
		vec3 tint, vec3 mist, float px) {
	float top = base + height * ridge_40(p.x, seed);
	vec3 fill = mix(tint, mist, 0.55 * smoothstep(top - 0.02, top - 0.4, p.y));
	fill = mix(fill, mist, 0.25 * smoothstep(top - 0.025, top, p.y));
	return mix(color, fill, smoothstep(top + px, top - px, p.y));
}

void main_40(void) {
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

	// Read and shape the page: each layer_40 moves by its own share of a
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
	vec2 star = (cell + 0.2 + 0.6 * vec2(hash_40(cell + 3.1), hash_40(cell + 7.7))) / 28.0;
	color += smoothstep(0.05, -0.15, sun) * step(0.93, hash_40(cell)) * smoothstep(1.8 * px, 0.0, distance(sky, star)) * above;
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
	color = layer_40(color, vec2(2.2 * (p.x + 0.15 * page), p.y), ground + 0.1, 0.09, 1.0, mix(low, deep, 0.35), low, px);
	color = layer_40(color, vec2(p.x + 0.4 * page, p.y), ground - 0.04, 0.1, 4.0, mix(low, deep, 0.65), low, px);
	color = layer_40(color, vec2(0.8 * (p.x + 0.9 * page), p.y), ground - 0.2, 0.12, 9.0, deep, 0.5 * deep, px);

	// The dark theme asks for a quieter picture: dimmer, cooler and less
	// saturated, so white icons and text stay readable over it.
	if (nightMode == 1) {
		float grey = dot(color, vec3(0.3, 0.55, 0.15));
		color = mix(color, grey * vec3(0.7, 0.75, 1.0), 0.4) * 0.75;
	}

	// A little noise against banding in the smooth gradients.
	color += (hash_40(gl_FragCoord.xy) - 0.5) / 255.0;
	gl_FragColor = vec4(color, 1.0);
}

// copy 41

// 0..1 from a cell position.
float hash_41(vec2 c) {
	return fract(sin(dot(c, vec2(27.17, 91.31))) * 41231.7);
}

// A ridge_41 line, -1..1: three sines whose wavelengths do not line up.
float ridge_41(float x, float seed) {
	return 0.5 * sin(1.7 * x + seed) + 0.3 * sin(4.3 * x + 2.1 * seed) + 0.2 * sin(9.7 * x - seed);
}

// Fills everything below a ridge_41 with `tint`, paler towards its foot
// where mist lies in the valley, with a faint rim of sky light on top.
vec3 layer_41(vec3 color, vec2 p, float base, float height, float seed,
		vec3 tint, vec3 mist, float px) {
	float top = base + height * ridge_41(p.x, seed);
	vec3 fill = mix(tint, mist, 0.55 * smoothstep(top - 0.02, top - 0.4, p.y));
	fill = mix(fill, mist, 0.25 * smoothstep(top - 0.025, top, p.y));
	return mix(color, fill, smoothstep(top + px, top - px, p.y));
}

void main_41(void) {
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

	// Read and shape the page: each layer_41 moves by its own share of a
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
	vec2 star = (cell + 0.2 + 0.6 * vec2(hash_41(cell + 3.1), hash_41(cell + 7.7))) / 28.0;
	color += smoothstep(0.05, -0.15, sun) * step(0.93, hash_41(cell)) * smoothstep(1.8 * px, 0.0, distance(sky, star)) * above;
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
	color = layer_41(color, vec2(2.2 * (p.x + 0.15 * page), p.y), ground + 0.1, 0.09, 1.0, mix(low, deep, 0.35), low, px);
	color = layer_41(color, vec2(p.x + 0.4 * page, p.y), ground - 0.04, 0.1, 4.0, mix(low, deep, 0.65), low, px);
	color = layer_41(color, vec2(0.8 * (p.x + 0.9 * page), p.y), ground - 0.2, 0.12, 9.0, deep, 0.5 * deep, px);

	// The dark theme asks for a quieter picture: dimmer, cooler and less
	// saturated, so white icons and text stay readable over it.
	if (nightMode == 1) {
		float grey = dot(color, vec3(0.3, 0.55, 0.15));
		color = mix(color, grey * vec3(0.7, 0.75, 1.0), 0.4) * 0.75;
	}

	// A little noise against banding in the smooth gradients.
	color += (hash_41(gl_FragCoord.xy) - 0.5) / 255.0;
	gl_FragColor = vec4(color, 1.0);
}

// copy 42

// 0..1 from a cell position.
float hash_42(vec2 c) {
	return fract(sin(dot(c, vec2(27.17, 91.31))) * 41231.7);
}

// A ridge_42 line, -1..1: three sines whose wavelengths do not line up.
float ridge_42(float x, float seed) {
	return 0.5 * sin(1.7 * x + seed) + 0.3 * sin(4.3 * x + 2.1 * seed) + 0.2 * sin(9.7 * x - seed);
}

// Fills everything below a ridge_42 with `tint`, paler towards its foot
// where mist lies in the valley, with a faint rim of sky light on top.
vec3 layer_42(vec3 color, vec2 p, float base, float height, float seed,
		vec3 tint, vec3 mist, float px) {
	float top = base + height * ridge_42(p.x, seed);
	vec3 fill = mix(tint, mist, 0.55 * smoothstep(top - 0.02, top - 0.4, p.y));
	fill = mix(fill, mist, 0.25 * smoothstep(top - 0.025, top, p.y));
	return mix(color, fill, smoothstep(top + px, top - px, p.y));
}

void main_42(void) {
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

	// Read and shape the page: each layer_42 moves by its own share of a
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
	vec2 star = (cell + 0.2 + 0.6 * vec2(hash_42(cell + 3.1), hash_42(cell + 7.7))) / 28.0;
	color += smoothstep(0.05, -0.15, sun) * step(0.93, hash_42(cell)) * smoothstep(1.8 * px, 0.0, distance(sky, star)) * above;
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
	color = layer_42(color, vec2(2.2 * (p.x + 0.15 * page), p.y), ground + 0.1, 0.09, 1.0, mix(low, deep, 0.35), low, px);
	color = layer_42(color, vec2(p.x + 0.4 * page, p.y), ground - 0.04, 0.1, 4.0, mix(low, deep, 0.65), low, px);
	color = layer_42(color, vec2(0.8 * (p.x + 0.9 * page), p.y), ground - 0.2, 0.12, 9.0, deep, 0.5 * deep, px);

	// The dark theme asks for a quieter picture: dimmer, cooler and less
	// saturated, so white icons and text stay readable over it.
	if (nightMode == 1) {
		float grey = dot(color, vec3(0.3, 0.55, 0.15));
		color = mix(color, grey * vec3(0.7, 0.75, 1.0), 0.4) * 0.75;
	}

	// A little noise against banding in the smooth gradients.
	color += (hash_42(gl_FragCoord.xy) - 0.5) / 255.0;
	gl_FragColor = vec4(color, 1.0);
}

// copy 43

// 0..1 from a cell position.
float hash_43(vec2 c) {
	return fract(sin(dot(c, vec2(27.17, 91.31))) * 41231.7);
}

// A ridge_43 line, -1..1: three sines whose wavelengths do not line up.
float ridge_43(float x, float seed) {
	return 0.5 * sin(1.7 * x + seed) + 0.3 * sin(4.3 * x + 2.1 * seed) + 0.2 * sin(9.7 * x - seed);
}

// Fills everything below a ridge_43 with `tint`, paler towards its foot
// where mist lies in the valley, with a faint rim of sky light on top.
vec3 layer_43(vec3 color, vec2 p, float base, float height, float seed,
		vec3 tint, vec3 mist, float px) {
	float top = base + height * ridge_43(p.x, seed);
	vec3 fill = mix(tint, mist, 0.55 * smoothstep(top - 0.02, top - 0.4, p.y));
	fill = mix(fill, mist, 0.25 * smoothstep(top - 0.025, top, p.y));
	return mix(color, fill, smoothstep(top + px, top - px, p.y));
}

void main_43(void) {
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

	// Read and shape the page: each layer_43 moves by its own share of a
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
	vec2 star = (cell + 0.2 + 0.6 * vec2(hash_43(cell + 3.1), hash_43(cell + 7.7))) / 28.0;
	color += smoothstep(0.05, -0.15, sun) * step(0.93, hash_43(cell)) * smoothstep(1.8 * px, 0.0, distance(sky, star)) * above;
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
	color = layer_43(color, vec2(2.2 * (p.x + 0.15 * page), p.y), ground + 0.1, 0.09, 1.0, mix(low, deep, 0.35), low, px);
	color = layer_43(color, vec2(p.x + 0.4 * page, p.y), ground - 0.04, 0.1, 4.0, mix(low, deep, 0.65), low, px);
	color = layer_43(color, vec2(0.8 * (p.x + 0.9 * page), p.y), ground - 0.2, 0.12, 9.0, deep, 0.5 * deep, px);

	// The dark theme asks for a quieter picture: dimmer, cooler and less
	// saturated, so white icons and text stay readable over it.
	if (nightMode == 1) {
		float grey = dot(color, vec3(0.3, 0.55, 0.15));
		color = mix(color, grey * vec3(0.7, 0.75, 1.0), 0.4) * 0.75;
	}

	// A little noise against banding in the smooth gradients.
	color += (hash_43(gl_FragCoord.xy) - 0.5) / 255.0;
	gl_FragColor = vec4(color, 1.0);
}

// copy 44

// 0..1 from a cell position.
float hash_44(vec2 c) {
	return fract(sin(dot(c, vec2(27.17, 91.31))) * 41231.7);
}

// A ridge_44 line, -1..1: three sines whose wavelengths do not line up.
float ridge_44(float x, float seed) {
	return 0.5 * sin(1.7 * x + seed) + 0.3 * sin(4.3 * x + 2.1 * seed) + 0.2 * sin(9.7 * x - seed);
}

// Fills everything below a ridge_44 with `tint`, paler towards its foot
// where mist lies in the valley, with a faint rim of sky light on top.
vec3 layer_44(vec3 color, vec2 p, float base, float height, float seed,
		vec3 tint, vec3 mist, float px) {
	float top = base + height * ridge_44(p.x, seed);
	vec3 fill = mix(tint, mist, 0.55 * smoothstep(top - 0.02, top - 0.4, p.y));
	fill = mix(fill, mist, 0.25 * smoothstep(top - 0.025, top, p.y));
	return mix(color, fill, smoothstep(top + px, top - px, p.y));
}

void main_44(void) {
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

	// Read and shape the page: each layer_44 moves by its own share of a
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
	vec2 star = (cell + 0.2 + 0.6 * vec2(hash_44(cell + 3.1), hash_44(cell + 7.7))) / 28.0;
	color += smoothstep(0.05, -0.15, sun) * step(0.93, hash_44(cell)) * smoothstep(1.8 * px, 0.0, distance(sky, star)) * above;
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
	color = layer_44(color, vec2(2.2 * (p.x + 0.15 * page), p.y), ground + 0.1, 0.09, 1.0, mix(low, deep, 0.35), low, px);
	color = layer_44(color, vec2(p.x + 0.4 * page, p.y), ground - 0.04, 0.1, 4.0, mix(low, deep, 0.65), low, px);
	color = layer_44(color, vec2(0.8 * (p.x + 0.9 * page), p.y), ground - 0.2, 0.12, 9.0, deep, 0.5 * deep, px);

	// The dark theme asks for a quieter picture: dimmer, cooler and less
	// saturated, so white icons and text stay readable over it.
	if (nightMode == 1) {
		float grey = dot(color, vec3(0.3, 0.55, 0.15));
		color = mix(color, grey * vec3(0.7, 0.75, 1.0), 0.4) * 0.75;
	}

	// A little noise against banding in the smooth gradients.
	color += (hash_44(gl_FragCoord.xy) - 0.5) / 255.0;
	gl_FragColor = vec4(color, 1.0);
}

// copy 45

// 0..1 from a cell position.
float hash_45(vec2 c) {
	return fract(sin(dot(c, vec2(27.17, 91.31))) * 41231.7);
}

// A ridge_45 line, -1..1: three sines whose wavelengths do not line up.
float ridge_45(float x, float seed) {
	return 0.5 * sin(1.7 * x + seed) + 0.3 * sin(4.3 * x + 2.1 * seed) + 0.2 * sin(9.7 * x - seed);
}

// Fills everything below a ridge_45 with `tint`, paler towards its foot
// where mist lies in the valley, with a faint rim of sky light on top.
vec3 layer_45(vec3 color, vec2 p, float base, float height, float seed,
		vec3 tint, vec3 mist, float px) {
	float top = base + height * ridge_45(p.x, seed);
	vec3 fill = mix(tint, mist, 0.55 * smoothstep(top - 0.02, top - 0.4, p.y));
	fill = mix(fill, mist, 0.25 * smoothstep(top - 0.025, top, p.y));
	return mix(color, fill, smoothstep(top + px, top - px, p.y));
}

void main_45(void) {
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

	// Read and shape the page: each layer_45 moves by its own share of a
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
	vec2 star = (cell + 0.2 + 0.6 * vec2(hash_45(cell + 3.1), hash_45(cell + 7.7))) / 28.0;
	color += smoothstep(0.05, -0.15, sun) * step(0.93, hash_45(cell)) * smoothstep(1.8 * px, 0.0, distance(sky, star)) * above;
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
	color = layer_45(color, vec2(2.2 * (p.x + 0.15 * page), p.y), ground + 0.1, 0.09, 1.0, mix(low, deep, 0.35), low, px);
	color = layer_45(color, vec2(p.x + 0.4 * page, p.y), ground - 0.04, 0.1, 4.0, mix(low, deep, 0.65), low, px);
	color = layer_45(color, vec2(0.8 * (p.x + 0.9 * page), p.y), ground - 0.2, 0.12, 9.0, deep, 0.5 * deep, px);

	// The dark theme asks for a quieter picture: dimmer, cooler and less
	// saturated, so white icons and text stay readable over it.
	if (nightMode == 1) {
		float grey = dot(color, vec3(0.3, 0.55, 0.15));
		color = mix(color, grey * vec3(0.7, 0.75, 1.0), 0.4) * 0.75;
	}

	// A little noise against banding in the smooth gradients.
	color += (hash_45(gl_FragCoord.xy) - 0.5) / 255.0;
	gl_FragColor = vec4(color, 1.0);
}

// copy 46

// 0..1 from a cell position.
float hash_46(vec2 c) {
	return fract(sin(dot(c, vec2(27.17, 91.31))) * 41231.7);
}

// A ridge_46 line, -1..1: three sines whose wavelengths do not line up.
float ridge_46(float x, float seed) {
	return 0.5 * sin(1.7 * x + seed) + 0.3 * sin(4.3 * x + 2.1 * seed) + 0.2 * sin(9.7 * x - seed);
}

// Fills everything below a ridge_46 with `tint`, paler towards its foot
// where mist lies in the valley, with a faint rim of sky light on top.
vec3 layer_46(vec3 color, vec2 p, float base, float height, float seed,
		vec3 tint, vec3 mist, float px) {
	float top = base + height * ridge_46(p.x, seed);
	vec3 fill = mix(tint, mist, 0.55 * smoothstep(top - 0.02, top - 0.4, p.y));
	fill = mix(fill, mist, 0.25 * smoothstep(top - 0.025, top, p.y));
	return mix(color, fill, smoothstep(top + px, top - px, p.y));
}

void main_46(void) {
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

	// Read and shape the page: each layer_46 moves by its own share of a
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
	vec2 star = (cell + 0.2 + 0.6 * vec2(hash_46(cell + 3.1), hash_46(cell + 7.7))) / 28.0;
	color += smoothstep(0.05, -0.15, sun) * step(0.93, hash_46(cell)) * smoothstep(1.8 * px, 0.0, distance(sky, star)) * above;
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
	color = layer_46(color, vec2(2.2 * (p.x + 0.15 * page), p.y), ground + 0.1, 0.09, 1.0, mix(low, deep, 0.35), low, px);
	color = layer_46(color, vec2(p.x + 0.4 * page, p.y), ground - 0.04, 0.1, 4.0, mix(low, deep, 0.65), low, px);
	color = layer_46(color, vec2(0.8 * (p.x + 0.9 * page), p.y), ground - 0.2, 0.12, 9.0, deep, 0.5 * deep, px);

	// The dark theme asks for a quieter picture: dimmer, cooler and less
	// saturated, so white icons and text stay readable over it.
	if (nightMode == 1) {
		float grey = dot(color, vec3(0.3, 0.55, 0.15));
		color = mix(color, grey * vec3(0.7, 0.75, 1.0), 0.4) * 0.75;
	}

	// A little noise against banding in the smooth gradients.
	color += (hash_46(gl_FragCoord.xy) - 0.5) / 255.0;
	gl_FragColor = vec4(color, 1.0);
}

// copy 47

// 0..1 from a cell position.
float hash_47(vec2 c) {
	return fract(sin(dot(c, vec2(27.17, 91.31))) * 41231.7);
}

// A ridge_47 line, -1..1: three sines whose wavelengths do not line up.
float ridge_47(float x, float seed) {
	return 0.5 * sin(1.7 * x + seed) + 0.3 * sin(4.3 * x + 2.1 * seed) + 0.2 * sin(9.7 * x - seed);
}

// Fills everything below a ridge_47 with `tint`, paler towards its foot
// where mist lies in the valley, with a faint rim of sky light on top.
vec3 layer_47(vec3 color, vec2 p, float base, float height, float seed,
		vec3 tint, vec3 mist, float px) {
	float top = base + height * ridge_47(p.x, seed);
	vec3 fill = mix(tint, mist, 0.55 * smoothstep(top - 0.02, top - 0.4, p.y));
	fill = mix(fill, mist, 0.25 * smoothstep(top - 0.025, top, p.y));
	return mix(color, fill, smoothstep(top + px, top - px, p.y));
}

void main_47(void) {
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

	// Read and shape the page: each layer_47 moves by its own share of a
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
	vec2 star = (cell + 0.2 + 0.6 * vec2(hash_47(cell + 3.1), hash_47(cell + 7.7))) / 28.0;
	color += smoothstep(0.05, -0.15, sun) * step(0.93, hash_47(cell)) * smoothstep(1.8 * px, 0.0, distance(sky, star)) * above;
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
	color = layer_47(color, vec2(2.2 * (p.x + 0.15 * page), p.y), ground + 0.1, 0.09, 1.0, mix(low, deep, 0.35), low, px);
	color = layer_47(color, vec2(p.x + 0.4 * page, p.y), ground - 0.04, 0.1, 4.0, mix(low, deep, 0.65), low, px);
	color = layer_47(color, vec2(0.8 * (p.x + 0.9 * page), p.y), ground - 0.2, 0.12, 9.0, deep, 0.5 * deep, px);

	// The dark theme asks for a quieter picture: dimmer, cooler and less
	// saturated, so white icons and text stay readable over it.
	if (nightMode == 1) {
		float grey = dot(color, vec3(0.3, 0.55, 0.15));
		color = mix(color, grey * vec3(0.7, 0.75, 1.0), 0.4) * 0.75;
	}

	// A little noise against banding in the smooth gradients.
	color += (hash_47(gl_FragCoord.xy) - 0.5) / 255.0;
	gl_FragColor = vec4(color, 1.0);
}

// copy 48

// 0..1 from a cell position.
float hash_48(vec2 c) {
	return fract(sin(dot(c, vec2(27.17, 91.31))) * 41231.7);
}

// A ridge_48 line, -1..1: three sines whose wavelengths do not line up.
float ridge_48(float x, float seed) {
	return 0.5 * sin(1.7 * x + seed) + 0.3 * sin(4.3 * x + 2.1 * seed) + 0.2 * sin(9.7 * x - seed);
}

// Fills everything below a ridge_48 with `tint`, paler towards its foot
// where mist lies in the valley, with a faint rim of sky light on top.
vec3 layer_48(vec3 color, vec2 p, float base, float height, float seed,
		vec3 tint, vec3 mist, float px) {
	float top = base + height * ridge_48(p.x, seed);
	vec3 fill = mix(tint, mist, 0.55 * smoothstep(top - 0.02, top - 0.4, p.y));
	fill = mix(fill, mist, 0.25 * smoothstep(top - 0.025, top, p.y));
	return mix(color, fill, smoothstep(top + px, top - px, p.y));
}

void main_48(void) {
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

	// Read and shape the page: each layer_48 moves by its own share of a
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
	vec2 star = (cell + 0.2 + 0.6 * vec2(hash_48(cell + 3.1), hash_48(cell + 7.7))) / 28.0;
	color += smoothstep(0.05, -0.15, sun) * step(0.93, hash_48(cell)) * smoothstep(1.8 * px, 0.0, distance(sky, star)) * above;
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
	color = layer_48(color, vec2(2.2 * (p.x + 0.15 * page), p.y), ground + 0.1, 0.09, 1.0, mix(low, deep, 0.35), low, px);
	color = layer_48(color, vec2(p.x + 0.4 * page, p.y), ground - 0.04, 0.1, 4.0, mix(low, deep, 0.65), low, px);
	color = layer_48(color, vec2(0.8 * (p.x + 0.9 * page), p.y), ground - 0.2, 0.12, 9.0, deep, 0.5 * deep, px);

	// The dark theme asks for a quieter picture: dimmer, cooler and less
	// saturated, so white icons and text stay readable over it.
	if (nightMode == 1) {
		float grey = dot(color, vec3(0.3, 0.55, 0.15));
		color = mix(color, grey * vec3(0.7, 0.75, 1.0), 0.4) * 0.75;
	}

	// A little noise against banding in the smooth gradients.
	color += (hash_48(gl_FragCoord.xy) - 0.5) / 255.0;
	gl_FragColor = vec4(color, 1.0);
}

// copy 49

// 0..1 from a cell position.
float hash_49(vec2 c) {
	return fract(sin(dot(c, vec2(27.17, 91.31))) * 41231.7);
}

// A ridge_49 line, -1..1: three sines whose wavelengths do not line up.
float ridge_49(float x, float seed) {
	return 0.5 * sin(1.7 * x + seed) + 0.3 * sin(4.3 * x + 2.1 * seed) + 0.2 * sin(9.7 * x - seed);
}

// Fills everything below a ridge_49 with `tint`, paler towards its foot
// where mist lies in the valley, with a faint rim of sky light on top.
vec3 layer_49(vec3 color, vec2 p, float base, float height, float seed,
		vec3 tint, vec3 mist, float px) {
	float top = base + height * ridge_49(p.x, seed);
	vec3 fill = mix(tint, mist, 0.55 * smoothstep(top - 0.02, top - 0.4, p.y));
	fill = mix(fill, mist, 0.25 * smoothstep(top - 0.025, top, p.y));
	return mix(color, fill, smoothstep(top + px, top - px, p.y));
}

void main_49(void) {
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

	// Read and shape the page: each layer_49 moves by its own share of a
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
	vec2 star = (cell + 0.2 + 0.6 * vec2(hash_49(cell + 3.1), hash_49(cell + 7.7))) / 28.0;
	color += smoothstep(0.05, -0.15, sun) * step(0.93, hash_49(cell)) * smoothstep(1.8 * px, 0.0, distance(sky, star)) * above;
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
	color = layer_49(color, vec2(2.2 * (p.x + 0.15 * page), p.y), ground + 0.1, 0.09, 1.0, mix(low, deep, 0.35), low, px);
	color = layer_49(color, vec2(p.x + 0.4 * page, p.y), ground - 0.04, 0.1, 4.0, mix(low, deep, 0.65), low, px);
	color = layer_49(color, vec2(0.8 * (p.x + 0.9 * page), p.y), ground - 0.2, 0.12, 9.0, deep, 0.5 * deep, px);

	// The dark theme asks for a quieter picture: dimmer, cooler and less
	// saturated, so white icons and text stay readable over it.
	if (nightMode == 1) {
		float grey = dot(color, vec3(0.3, 0.55, 0.15));
		color = mix(color, grey * vec3(0.7, 0.75, 1.0), 0.4) * 0.75;
	}

	// A little noise against banding in the smooth gradients.
	color += (hash_49(gl_FragCoord.xy) - 0.5) / 255.0;
	gl_FragColor = vec4(color, 1.0);
}

// copy 50

// 0..1 from a cell position.
float hash_50(vec2 c) {
	return fract(sin(dot(c, vec2(27.17, 91.31))) * 41231.7);
}

// A ridge_50 line, -1..1: three sines whose wavelengths do not line up.
float ridge_50(float x, float seed) {
	return 0.5 * sin(1.7 * x + seed) + 0.3 * sin(4.3 * x + 2.1 * seed) + 0.2 * sin(9.7 * x - seed);
}

// Fills everything below a ridge_50 with `tint`, paler towards its foot
// where mist lies in the valley, with a faint rim of sky light on top.
vec3 layer_50(vec3 color, vec2 p, float base, float height, float seed,
		vec3 tint, vec3 mist, float px) {
	float top = base + height * ridge_50(p.x, seed);
	vec3 fill = mix(tint, mist, 0.55 * smoothstep(top - 0.02, top - 0.4, p.y));
	fill = mix(fill, mist, 0.25 * smoothstep(top - 0.025, top, p.y));
	return mix(color, fill, smoothstep(top + px, top - px, p.y));
}

void main_50(void) {
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

	// Read and shape the page: each layer_50 moves by its own share of a
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
	vec2 star = (cell + 0.2 + 0.6 * vec2(hash_50(cell + 3.1), hash_50(cell + 7.7))) / 28.0;
	color += smoothstep(0.05, -0.15, sun) * step(0.93, hash_50(cell)) * smoothstep(1.8 * px, 0.0, distance(sky, star)) * above;
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
	color = layer_50(color, vec2(2.2 * (p.x + 0.15 * page), p.y), ground + 0.1, 0.09, 1.0, mix(low, deep, 0.35), low, px);
	color = layer_50(color, vec2(p.x + 0.4 * page, p.y), ground - 0.04, 0.1, 4.0, mix(low, deep, 0.65), low, px);
	color = layer_50(color, vec2(0.8 * (p.x + 0.9 * page), p.y), ground - 0.2, 0.12, 9.0, deep, 0.5 * deep, px);

	// The dark theme asks for a quieter picture: dimmer, cooler and less
	// saturated, so white icons and text stay readable over it.
	if (nightMode == 1) {
		float grey = dot(color, vec3(0.3, 0.55, 0.15));
		color = mix(color, grey * vec3(0.7, 0.75, 1.0), 0.4) * 0.75;
	}

	// A little noise against banding in the smooth gradients.
	color += (hash_50(gl_FragCoord.xy) - 0.5) / 255.0;
	gl_FragColor = vec4(color, 1.0);
}

// copy 51

// 0..1 from a cell position.
float hash_51(vec2 c) {
	return fract(sin(dot(c, vec2(27.17, 91.31))) * 41231.7);
}

// A ridge_51 line, -1..1: three sines whose wavelengths do not line up.
float ridge_51(float x, float seed) {
	return 0.5 * sin(1.7 * x + seed) + 0.3 * sin(4.3 * x + 2.1 * seed) + 0.2 * sin(9.7 * x - seed);
}

// Fills everything below a ridge_51 with `tint`, paler towards its foot
// where mist lies in the valley, with a faint rim of sky light on top.
vec3 layer_51(vec3 color, vec2 p, float base, float height, float seed,
		vec3 tint, vec3 mist, float px) {
	float top = base + height * ridge_51(p.x, seed);
	vec3 fill = mix(tint, mist, 0.55 * smoothstep(top - 0.02, top - 0.4, p.y));
	fill = mix(fill, mist, 0.25 * smoothstep(top - 0.025, top, p.y));
	return mix(color, fill, smoothstep(top + px, top - px, p.y));
}

void main_51(void) {
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

	// Read and shape the page: each layer_51 moves by its own share of a
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
	vec2 star = (cell + 0.2 + 0.6 * vec2(hash_51(cell + 3.1), hash_51(cell + 7.7))) / 28.0;
	color += smoothstep(0.05, -0.15, sun) * step(0.93, hash_51(cell)) * smoothstep(1.8 * px, 0.0, distance(sky, star)) * above;
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
	color = layer_51(color, vec2(2.2 * (p.x + 0.15 * page), p.y), ground + 0.1, 0.09, 1.0, mix(low, deep, 0.35), low, px);
	color = layer_51(color, vec2(p.x + 0.4 * page, p.y), ground - 0.04, 0.1, 4.0, mix(low, deep, 0.65), low, px);
	color = layer_51(color, vec2(0.8 * (p.x + 0.9 * page), p.y), ground - 0.2, 0.12, 9.0, deep, 0.5 * deep, px);

	// The dark theme asks for a quieter picture: dimmer, cooler and less
	// saturated, so white icons and text stay readable over it.
	if (nightMode == 1) {
		float grey = dot(color, vec3(0.3, 0.55, 0.15));
		color = mix(color, grey * vec3(0.7, 0.75, 1.0), 0.4) * 0.75;
	}

	// A little noise against banding in the smooth gradients.
	color += (hash_51(gl_FragCoord.xy) - 0.5) / 255.0;
	gl_FragColor = vec4(color, 1.0);
}

// copy 52

// 0..1 from a cell position.
float hash_52(vec2 c) {
	return fract(sin(dot(c, vec2(27.17, 91.31))) * 41231.7);
}

// A ridge_52 line, -1..1: three sines whose wavelengths do not line up.
float ridge_52(float x, float seed) {
	return 0.5 * sin(1.7 * x + seed) + 0.3 * sin(4.3 * x + 2.1 * seed) + 0.2 * sin(9.7 * x - seed);
}

// Fills everything below a ridge_52 with `tint`, paler towards its foot
// where mist lies in the valley, with a faint rim of sky light on top.
vec3 layer_52(vec3 color, vec2 p, float base, float height, float seed,
		vec3 tint, vec3 mist, float px) {
	float top = base + height * ridge_52(p.x, seed);
	vec3 fill = mix(tint, mist, 0.55 * smoothstep(top - 0.02, top - 0.4, p.y));
	fill = mix(fill, mist, 0.25 * smoothstep(top - 0.025, top, p.y));
	return mix(color, fill, smoothstep(top + px, top - px, p.y));
}

void main_52(void) {
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

	// Read and shape the page: each layer_52 moves by its own share of a
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
	vec2 star = (cell + 0.2 + 0.6 * vec2(hash_52(cell + 3.1), hash_52(cell + 7.7))) / 28.0;
	color += smoothstep(0.05, -0.15, sun) * step(0.93, hash_52(cell)) * smoothstep(1.8 * px, 0.0, distance(sky, star)) * above;
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
	color = layer_52(color, vec2(2.2 * (p.x + 0.15 * page), p.y), ground + 0.1, 0.09, 1.0, mix(low, deep, 0.35), low, px);
	color = layer_52(color, vec2(p.x + 0.4 * page, p.y), ground - 0.04, 0.1, 4.0, mix(low, deep, 0.65), low, px);
	color = layer_52(color, vec2(0.8 * (p.x + 0.9 * page), p.y), ground - 0.2, 0.12, 9.0, deep, 0.5 * deep, px);

	// The dark theme asks for a quieter picture: dimmer, cooler and less
	// saturated, so white icons and text stay readable over it.
	if (nightMode == 1) {
		float grey = dot(color, vec3(0.3, 0.55, 0.15));
		color = mix(color, grey * vec3(0.7, 0.75, 1.0), 0.4) * 0.75;
	}

	// A little noise against banding in the smooth gradients.
	color += (hash_52(gl_FragCoord.xy) - 0.5) / 255.0;
	gl_FragColor = vec4(color, 1.0);
}

// copy 53

// 0..1 from a cell position.
float hash_53(vec2 c) {
	return fract(sin(dot(c, vec2(27.17, 91.31))) * 41231.7);
}

// A ridge_53 line, -1..1: three sines whose wavelengths do not line up.
float ridge_53(float x, float seed) {
	return 0.5 * sin(1.7 * x + seed) + 0.3 * sin(4.3 * x + 2.1 * seed) + 0.2 * sin(9.7 * x - seed);
}

// Fills everything below a ridge_53 with `tint`, paler towards its foot
// where mist lies in the valley, with a faint rim of sky light on top.
vec3 layer_53(vec3 color, vec2 p, float base, float height, float seed,
		vec3 tint, vec3 mist, float px) {
	float top = base + height * ridge_53(p.x, seed);
	vec3 fill = mix(tint, mist, 0.55 * smoothstep(top - 0.02, top - 0.4, p.y));
	fill = mix(fill, mist, 0.25 * smoothstep(top - 0.025, top, p.y));
	return mix(color, fill, smoothstep(top + px, top - px, p.y));
}

void main_53(void) {
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

	// Read and shape the page: each layer_53 moves by its own share of a
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
	vec2 star = (cell + 0.2 + 0.6 * vec2(hash_53(cell + 3.1), hash_53(cell + 7.7))) / 28.0;
	color += smoothstep(0.05, -0.15, sun) * step(0.93, hash_53(cell)) * smoothstep(1.8 * px, 0.0, distance(sky, star)) * above;
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
	color = layer_53(color, vec2(2.2 * (p.x + 0.15 * page), p.y), ground + 0.1, 0.09, 1.0, mix(low, deep, 0.35), low, px);
	color = layer_53(color, vec2(p.x + 0.4 * page, p.y), ground - 0.04, 0.1, 4.0, mix(low, deep, 0.65), low, px);
	color = layer_53(color, vec2(0.8 * (p.x + 0.9 * page), p.y), ground - 0.2, 0.12, 9.0, deep, 0.5 * deep, px);

	// The dark theme asks for a quieter picture: dimmer, cooler and less
	// saturated, so white icons and text stay readable over it.
	if (nightMode == 1) {
		float grey = dot(color, vec3(0.3, 0.55, 0.15));
		color = mix(color, grey * vec3(0.7, 0.75, 1.0), 0.4) * 0.75;
	}

	// A little noise against banding in the smooth gradients.
	color += (hash_53(gl_FragCoord.xy) - 0.5) / 255.0;
	gl_FragColor = vec4(color, 1.0);
}

// copy 54

// 0..1 from a cell position.
float hash_54(vec2 c) {
	return fract(sin(dot(c, vec2(27.17, 91.31))) * 41231.7);
}

// A ridge_54 line, -1..1: three sines whose wavelengths do not line up.
float ridge_54(float x, float seed) {
	return 0.5 * sin(1.7 * x + seed) + 0.3 * sin(4.3 * x + 2.1 * seed) + 0.2 * sin(9.7 * x - seed);
}

// Fills everything below a ridge_54 with `tint`, paler towards its foot
// where mist lies in the valley, with a faint rim of sky light on top.
vec3 layer_54(vec3 color, vec2 p, float base, float height, float seed,
		vec3 tint, vec3 mist, float px) {
	float top = base + height * ridge_54(p.x, seed);
	vec3 fill = mix(tint, mist, 0.55 * smoothstep(top - 0.02, top - 0.4, p.y));
	fill = mix(fill, mist, 0.25 * smoothstep(top - 0.025, top, p.y));
	return mix(color, fill, smoothstep(top + px, top - px, p.y));
}

void main_54(void) {
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

	// Read and shape the page: each layer_54 moves by its own share of a
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
	vec2 star = (cell + 0.2 + 0.6 * vec2(hash_54(cell + 3.1), hash_54(cell + 7.7))) / 28.0;
	color += smoothstep(0.05, -0.15, sun) * step(0.93, hash_54(cell)) * smoothstep(1.8 * px, 0.0, distance(sky, star)) * above;
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
	color = layer_54(color, vec2(2.2 * (p.x + 0.15 * page), p.y), ground + 0.1, 0.09, 1.0, mix(low, deep, 0.35), low, px);
	color = layer_54(color, vec2(p.x + 0.4 * page, p.y), ground - 0.04, 0.1, 4.0, mix(low, deep, 0.65), low, px);
	color = layer_54(color, vec2(0.8 * (p.x + 0.9 * page), p.y), ground - 0.2, 0.12, 9.0, deep, 0.5 * deep, px);

	// The dark theme asks for a quieter picture: dimmer, cooler and less
	// saturated, so white icons and text stay readable over it.
	if (nightMode == 1) {
		float grey = dot(color, vec3(0.3, 0.55, 0.15));
		color = mix(color, grey * vec3(0.7, 0.75, 1.0), 0.4) * 0.75;
	}

	// A little noise against banding in the smooth gradients.
	color += (hash_54(gl_FragCoord.xy) - 0.5) / 255.0;
	gl_FragColor = vec4(color, 1.0);
}

// copy 55

// 0..1 from a cell position.
float hash_55(vec2 c) {
	return fract(sin(dot(c, vec2(27.17, 91.31))) * 41231.7);
}

// A ridge_55 line, -1..1: three sines whose wavelengths do not line up.
float ridge_55(float x, float seed) {
	return 0.5 * sin(1.7 * x + seed) + 0.3 * sin(4.3 * x + 2.1 * seed) + 0.2 * sin(9.7 * x - seed);
}

// Fills everything below a ridge_55 with `tint`, paler towards its foot
// where mist lies in the valley, with a faint rim of sky light on top.
vec3 layer_55(vec3 color, vec2 p, float base, float height, float seed,
		vec3 tint, vec3 mist, float px) {
	float top = base + height * ridge_55(p.x, seed);
	vec3 fill = mix(tint, mist, 0.55 * smoothstep(top - 0.02, top - 0.4, p.y));
	fill = mix(fill, mist, 0.25 * smoothstep(top - 0.025, top, p.y));
	return mix(color, fill, smoothstep(top + px, top - px, p.y));
}

void main_55(void) {
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

	// Read and shape the page: each layer_55 moves by its own share of a
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
	vec2 star = (cell + 0.2 + 0.6 * vec2(hash_55(cell + 3.1), hash_55(cell + 7.7))) / 28.0;
	color += smoothstep(0.05, -0.15, sun) * step(0.93, hash_55(cell)) * smoothstep(1.8 * px, 0.0, distance(sky, star)) * above;
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
	color = layer_55(color, vec2(2.2 * (p.x + 0.15 * page), p.y), ground + 0.1, 0.09, 1.0, mix(low, deep, 0.35), low, px);
	color = layer_55(color, vec2(p.x + 0.4 * page, p.y), ground - 0.04, 0.1, 4.0, mix(low, deep, 0.65), low, px);
	color = layer_55(color, vec2(0.8 * (p.x + 0.9 * page), p.y), ground - 0.2, 0.12, 9.0, deep, 0.5 * deep, px);

	// The dark theme asks for a quieter picture: dimmer, cooler and less
	// saturated, so white icons and text stay readable over it.
	if (nightMode == 1) {
		float grey = dot(color, vec3(0.3, 0.55, 0.15));
		color = mix(color, grey * vec3(0.7, 0.75, 1.0), 0.4) * 0.75;
	}

	// A little noise against banding in the smooth gradients.
	color += (hash_55(gl_FragCoord.xy) - 0.5) / 255.0;
	gl_FragColor = vec4(color, 1.0);
}

// copy 56

// 0..1 from a cell position.
float hash_56(vec2 c) {
	return fract(sin(dot(c, vec2(27.17, 91.31))) * 41231.7);
}

// A ridge_56 line, -1..1: three sines whose wavelengths do not line up.
float ridge_56(float x, float seed) {
	return 0.5 * sin(1.7 * x + seed) + 0.3 * sin(4.3 * x + 2.1 * seed) + 0.2 * sin(9.7 * x - seed);
}

// Fills everything below a ridge_56 with `tint`, paler towards its foot
// where mist lies in the valley, with a faint rim of sky light on top.
vec3 layer_56(vec3 color, vec2 p, float base, float height, float seed,
		vec3 tint, vec3 mist, float px) {
	float top = base + height * ridge_56(p.x, seed);
	vec3 fill = mix(tint, mist, 0.55 * smoothstep(top - 0.02, top - 0.4, p.y));
	fill = mix(fill, mist, 0.25 * smoothstep(top - 0.025, top, p.y));
	return mix(color, fill, smoothstep(top + px, top - px, p.y));
}

void main_56(void) {
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

	// Read and shape the page: each layer_56 moves by its own share of a
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
	vec2 star = (cell + 0.2 + 0.6 * vec2(hash_56(cell + 3.1), hash_56(cell + 7.7))) / 28.0;
	color += smoothstep(0.05, -0.15, sun) * step(0.93, hash_56(cell)) * smoothstep(1.8 * px, 0.0, distance(sky, star)) * above;
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
	color = layer_56(color, vec2(2.2 * (p.x + 0.15 * page), p.y), ground + 0.1, 0.09, 1.0, mix(low, deep, 0.35), low, px);
	color = layer_56(color, vec2(p.x + 0.4 * page, p.y), ground - 0.04, 0.1, 4.0, mix(low, deep, 0.65), low, px);
	color = layer_56(color, vec2(0.8 * (p.x + 0.9 * page), p.y), ground - 0.2, 0.12, 9.0, deep, 0.5 * deep, px);

	// The dark theme asks for a quieter picture: dimmer, cooler and less
	// saturated, so white icons and text stay readable over it.
	if (nightMode == 1) {
		float grey = dot(color, vec3(0.3, 0.55, 0.15));
		color = mix(color, grey * vec3(0.7, 0.75, 1.0), 0.4) * 0.75;
	}

	// A little noise against banding in the smooth gradients.
	color += (hash_56(gl_FragCoord.xy) - 0.5) / 255.0;
	gl_FragColor = vec4(color, 1.0);
}

// copy 57

// 0..1 from a cell position.
float hash_57(vec2 c) {
	return fract(sin(dot(c, vec2(27.17, 91.31))) * 41231.7);
}

// A ridge_57 line, -1..1: three sines whose wavelengths do not line up.
float ridge_57(float x, float seed) {
	return 0.5 * sin(1.7 * x + seed) + 0.3 * sin(4.3 * x + 2.1 * seed) + 0.2 * sin(9.7 * x - seed);
}

// Fills everything below a ridge_57 with `tint`, paler towards its foot
// where mist lies in the valley, with a faint rim of sky light on top.
vec3 layer_57(vec3 color, vec2 p, float base, float height, float seed,
		vec3 tint, vec3 mist, float px) {
	float top = base + height * ridge_57(p.x, seed);
	vec3 fill = mix(tint, mist, 0.55 * smoothstep(top - 0.02, top - 0.4, p.y));
	fill = mix(fill, mist, 0.25 * smoothstep(top - 0.025, top, p.y));
	return mix(color, fill, smoothstep(top + px, top - px, p.y));
}

void main_57(void) {
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

	// Read and shape the page: each layer_57 moves by its own share of a
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
	vec2 star = (cell + 0.2 + 0.6 * vec2(hash_57(cell + 3.1), hash_57(cell + 7.7))) / 28.0;
	color += smoothstep(0.05, -0.15, sun) * step(0.93, hash_57(cell)) * smoothstep(1.8 * px, 0.0, distance(sky, star)) * above;
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
	color = layer_57(color, vec2(2.2 * (p.x + 0.15 * page), p.y), ground + 0.1, 0.09, 1.0, mix(low, deep, 0.35), low, px);
	color = layer_57(color, vec2(p.x + 0.4 * page, p.y), ground - 0.04, 0.1, 4.0, mix(low, deep, 0.65), low, px);
	color = layer_57(color, vec2(0.8 * (p.x + 0.9 * page), p.y), ground - 0.2, 0.12, 9.0, deep, 0.5 * deep, px);

	// The dark theme asks for a quieter picture: dimmer, cooler and less
	// saturated, so white icons and text stay readable over it.
	if (nightMode == 1) {
		float grey = dot(color, vec3(0.3, 0.55, 0.15));
		color = mix(color, grey * vec3(0.7, 0.75, 1.0), 0.4) * 0.75;
	}

	// A little noise against banding in the smooth gradients.
	color += (hash_57(gl_FragCoord.xy) - 0.5) / 255.0;
	gl_FragColor = vec4(color, 1.0);
}

// original

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

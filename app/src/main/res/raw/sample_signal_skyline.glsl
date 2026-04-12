#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif

uniform vec2 resolution;
uniform float time;
uniform int second;
uniform float subsecond;
uniform vec4 date;
uniform vec3 daytime;
uniform float battery;
uniform int powerConnected;
uniform int nightMode;
uniform int notificationCount;
uniform float lastNotificationTime;
uniform vec2 offset;
uniform float mediaVolume;
uniform float micAmplitude;

float hash21(vec2 p) {
	p = fract(p * vec2(234.34, 435.345));
	p += dot(p, p + 34.23);
	return fract(p.x * p.y);
}

float recentPing() {
	return lastNotificationTime == lastNotificationTime
		? exp(-0.35 * min(lastNotificationTime, 24.0))
		: 0.0;
}

void main(void) {
	vec2 uv = gl_FragCoord.xy / resolution.xy;
	float wrapX = fract(uv.x + (offset.x - 0.5) * 0.45 + 1.0);
	float horizon = 0.14 + 0.04 * battery;
	float dayFrac = clamp(date.w / 86400.0, 0.0, 1.0);
	float clock = (daytime.x + daytime.y / 60.0 + daytime.z / 3600.0) / 24.0;
	float season = mod(date.y, 12.0) / 12.0;
	float yearTint = fract(date.x * 0.013);
	float dayWave = sin((dayFrac - 0.25) * 6.2831853) * 0.5 + 0.5;
	float dayAmt = smoothstep(0.18, 0.82, dayWave);
	dayAmt *= 1.0 - 0.35 * float(nightMode);
	float ping = recentPing();
	float audio = clamp(max(micAmplitude * 1.8, mediaVolume * 0.9), 0.0, 1.0);
	float beat = fract(float(second) * 0.6180339 + subsecond + mediaVolume * 0.2);

	vec3 skyNight = mix(
		vec3(0.02, 0.03, 0.08),
		vec3(0.06, 0.01, 0.14),
		season);
	vec3 skyDay = mix(
		vec3(0.38, 0.58, 0.95),
		vec3(0.7, 0.82, 0.96),
		season);
	float skyMix = smoothstep(-0.2, 0.95, uv.y + dayAmt * 0.15);
	vec3 sky = mix(skyNight, skyDay, skyMix);
	sky = mix(sky, sky.bgr, 0.08 * yearTint);

	vec2 sunPos = vec2(
		0.18 + dayFrac * 0.64,
		0.28 + 0.48 * dayWave + (offset.y - 0.5) * 0.1);
	float sun = 0.045 / (0.02 + distance(uv, sunPos));
	sky += mix(
		vec3(0.1, 0.18, 0.35),
		vec3(1.0, 0.65, 0.25),
		dayAmt) * sun;

	vec2 starCell = floor(vec2(wrapX * 80.0, uv.y * 140.0));
	float stars = step(0.995, hash21(starCell + vec2(floor(date.w), date.z)));
	stars *= smoothstep(0.22, 0.8, uv.y) * (1.0 - dayAmt);
	sky += vec3(1.2, 1.15, 1.3) * stars;

	float cloud = smoothstep(
		0.08,
		0.0,
		abs(uv.y - 0.72 - 0.05 * sin((wrapX + clock * 0.6) * 8.0)));
	sky = mix(sky, sky * 0.82 + vec3(0.08, 0.08, 0.1), 0.18 * cloud);

	vec3 color = sky;

	float colsFar = 42.0;
	float gxFar = wrapX * colsFar;
	float idFar = floor(gxFar);
	float localFar = fract(gxFar) - 0.5;
	float seedFar = hash21(vec2(idFar + 7.0, floor(date.z) + 3.0));
	float farHeight = horizon + 0.06 + 0.22 * pow(seedFar, 1.8);
	float farTower = step(abs(localFar), 0.46) * step(uv.y, farHeight);
	color = mix(color, vec3(0.05, 0.06, 0.08), farTower * 0.75);

	float cols = 24.0;
	float gx = wrapX * cols;
	float id = floor(gx);
	float lx = fract(gx) - 0.5;
	float seed = hash21(vec2(id + 13.0, floor(date.z) + floor(date.x)));
	float pulse = 0.5 + 0.5 * sin(time * 5.0 + id * 1.7);

	float height = horizon + 0.15 + 0.45 * pow(seed, 1.5);
	height += audio * (0.04 + 0.18 * pulse);
	height += min(float(notificationCount), 10.0) * 0.012 * smoothstep(0.55, 0.95, seed);
	float tower = step(abs(lx), 0.43) * step(uv.y, height);

	vec3 towerBase = mix(
		vec3(0.06, 0.08, 0.12),
		vec3(0.12, 0.16, 0.2),
		dayAmt);
	vec3 towerNeon = mix(
		vec3(0.0, 0.95, 1.4),
		vec3(1.3, 0.6, 1.1),
		float(powerConnected));
	towerBase += towerNeon * (0.08 + 0.22 * audio) * smoothstep(0.7, 0.98, seed);

	vec2 winUV = vec2(gx * 4.0, (uv.y - horizon) * 85.0);
	vec2 winCell = floor(winUV);
	float winShape =
		step(0.15, fract(winUV.x)) * step(fract(winUV.x), 0.85) *
		step(0.2, fract(winUV.y)) * step(fract(winUV.y), 0.88);
	float windowOn = step(0.78, hash21(winCell + vec2(float(second), id * 0.37)));
	float windows = tower * winShape * windowOn * step(horizon + 0.02, uv.y);

	vec3 windowColor = mix(
		vec3(0.24, 0.28, 0.32),
		vec3(1.0, 0.85, 0.52),
		0.4 + 0.6 * beat);
	color = mix(color, towerBase + windowColor * windows, tower);

	float audioLine = exp(-180.0 *
		abs(uv.y - (horizon + 0.12 + audio * 0.18 + 0.03 * sin(time * 3.0))));
	color += towerNeon * audioLine * (0.18 + 0.82 * mediaVolume);

	float chargeBand = exp(-280.0 * abs(uv.y - (0.04 + battery * 0.05)));
	color += mix(
		vec3(0.25, 0.9, 1.2),
		vec3(0.2, 1.25, 0.45),
		float(powerConnected)) * chargeBand;

	float pingWave = exp(-18.0 *
		abs(distance(uv, vec2(0.5, horizon + 0.18)) - (0.08 + ping * 0.35)));
	color += vec3(1.2, 0.5, 1.0) * pingWave * ping;

	float beacon = smoothstep(0.0, 0.12, 1.0 - abs(lx)) * tower;
	beacon *= smoothstep(height - 0.12, height, uv.y);
	beacon *= 0.15 + 0.15 * min(float(notificationCount), 6.0);
	color += towerNeon * beacon;

	gl_FragColor = vec4(clamp(color, 0.0, 1.0), 1.0);
}

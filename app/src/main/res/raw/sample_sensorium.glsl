#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif

#define FTIME_PERIOD 12.0

uniform vec2 resolution;
uniform float time;
uniform float ftime;
uniform float battery;
uniform int powerConnected;
uniform int nightMode;
uniform int notificationCount;
uniform float lastNotificationTime;
uniform vec4 date;
uniform vec3 daytime;
uniform vec2 offset;
uniform vec3 gravity;
uniform vec3 gyroscope;
uniform vec3 magnetic;
uniform vec3 orientation;
uniform float inclination;
uniform mat3 inclinationMatrix;
uniform mat3 rotationMatrix;
uniform vec3 rotationVector;
uniform float light;
uniform float pressure;
uniform float proximity;

mat2 rot(float a) {
	float s = sin(a);
	float c = cos(a);
	return mat2(c, -s, s, c);
}

float sdSphere(vec3 p, float r) {
	return length(p) - r;
}

float sdBox(vec3 p, vec3 b) {
	vec3 d = abs(p) - b;
	return min(max(d.x, max(d.y, d.z)), 0.0) + length(max(d, 0.0));
}

float sdTorus(vec3 p, vec2 t) {
	vec2 q = vec2(length(p.xz) - t.x, p.y);
	return length(q) - t.y;
}

vec2 opU(vec2 a, vec2 b) {
	return a.x < b.x ? a : b;
}

mat3 safeMat(mat3 m) {
	float s = dot(m[0], m[0]) + dot(m[1], m[1]) + dot(m[2], m[2]);
	float a = smoothstep(0.1, 3.0, s);
	return mat3(
		mix(vec3(1.0, 0.0, 0.0), m[0], a),
		mix(vec3(0.0, 1.0, 0.0), m[1], a),
		mix(vec3(0.0, 0.0, 1.0), m[2], a));
}

float recentPing() {
	return lastNotificationTime == lastNotificationTime
		? exp(-0.12 * min(lastNotificationTime, 60.0))
		: 0.0;
}

vec2 scene(vec3 p) {
	float ping = recentPing();
	mat3 rm = safeMat(rotationMatrix);
	mat3 im = safeMat(inclinationMatrix);

	vec3 q = p - vec3(offset.x * 0.7, offset.y * 0.25, 0.0);
	q.xz *= rot(orientation.x * 0.4 + rotationVector.y * 0.5 + offset.x * 0.6);
	q.yz *= rot(inclination * 0.25 + gravity.x * 0.03);
	q.xy *= rot(gyroscope.z * 0.14 + magnetic.z * 0.012);
	q = mix(q, rm * q, 0.18);
	q += 0.08 * (im * q).yzx;

	vec2 res = vec2(1e5, -1.0);
	float floorD = p.y + 1.0 +
		0.05 * sin(p.x * 1.7 + inclination * 3.0) +
		0.04 * cos(p.z * 2.1 + time * 0.25);
	res = opU(res, vec2(floorD, 0.0));

	float tower = sdBox(
		q,
		vec3(0.42, 0.85 + 0.35 * battery, 0.28));
	res = opU(res, vec2(tower, 1.0));

	float ring = sdTorus(
		q - vec3(0.0, 0.22, 0.0),
		vec2(
			0.85 + 0.1 * sin(time * 0.35 + float(notificationCount)),
			0.08 + 0.03 * ping));
	res = opU(res, vec2(ring, 2.0));

	float core = sdSphere(
		q - vec3(0.0, 0.08, 0.0),
		0.22 + 0.08 / (1.0 + proximity));
	res = opU(res, vec2(core, 3.0));

	vec3 orb = q - vec3(0.0, 0.95 + 0.1 * sin(time + orientation.z), 0.0);
	orb.xz *= rot(time * 0.8 + rotationVector.x * 0.7);
	res = opU(res, vec2(
		sdSphere(orb, 0.14 + 0.025 * sin(time * 1.2 + gyroscope.y)),
		4.0));

	return res;
}

vec3 getNormal(vec3 p) {
	vec2 e = vec2(0.0015, 0.0);
	return normalize(vec3(
		scene(p + e.xyy).x - scene(p - e.xyy).x,
		scene(p + e.yxy).x - scene(p - e.yxy).x,
		scene(p + e.yyx).x - scene(p - e.yyx).x));
}

void main(void) {
	vec2 uv = (gl_FragCoord.xy - 0.5 * resolution.xy) / resolution.y;

	float dayFrac = clamp(date.w / 86400.0, 0.0, 1.0);
	float clock = (daytime.x + daytime.y / 60.0 + daytime.z / 3600.0) / 24.0;
	float season = mod(date.y, 12.0) / 12.0;
	float yearTint = fract(date.x * 0.017);
	float dayWave = sin((dayFrac - 0.25) * 6.2831853) * 0.5 + 0.5;
	float dayAmt = smoothstep(0.18, 0.82, dayWave);
	dayAmt *= 1.0 - 0.35 * float(nightMode);
	float ping = recentPing();
	float lux = light > 0.0
		? clamp(log(1.0 + light) / log(1024.0), 0.0, 1.0)
		: 0.3;
	float baro = pressure > 0.0
		? clamp((pressure - 960.0) / 90.0, 0.0, 1.0)
		: 0.5;

	vec3 magDir = normalize(magnetic + vec3(0.001, 0.002, 0.003));
	vec3 gyroDir = normalize(gyroscope + vec3(0.002, 0.001, 0.003));

	vec3 skyNight = mix(
		vec3(0.03, 0.04, 0.08),
		vec3(0.07, 0.02, 0.16),
		season);
	vec3 skyDay = mix(
		vec3(0.46, 0.63, 0.96),
		vec3(0.78, 0.85, 0.97),
		season);
	float skyMix = clamp(uv.y * 0.85 + 0.45, 0.0, 1.0);
	vec3 sky = mix(skyNight, skyDay, skyMix * 0.75 + dayAmt * 0.25);
	sky = mix(sky, sky.bgr, 0.08 * yearTint);

	vec2 sunPos = vec2(cos(dayFrac * 6.2831853) * 0.55, 0.18 + 0.45 * dayAmt);
	float sun = 0.05 / (0.02 + distance(uv, sunPos));
	sky += mix(
		vec3(0.2, 0.22, 0.35),
		vec3(1.0, 0.7, 0.3),
		dayAmt) * sun * (0.4 + 0.4 * lux);

	float aurora = sin(
		(uv.x * magDir.x + uv.y * magDir.y) * 18.0 +
		time +
		gyroDir.y * 4.0 +
		clock * 6.2831853);
	sky += (1.0 - dayAmt * 0.6) *
		pow(max(aurora, 0.0), 3.0) *
		(0.08 + 0.12 * lux) *
		vec3(0.12, 0.8, 1.25);

	vec3 ro = vec3(
		0.0,
		0.18 + gravity.z * 0.01,
		4.6);
	vec3 rd = normalize(vec3(uv, -1.45));
	rd.xz *= rot(orientation.x * 0.12 + rotationVector.z * 0.18);
	rd.yz *= rot(-0.08 + orientation.y * 0.08);

	float travel = 0.0;
	float material = -1.0;
	for (int i = 0; i < 96; ++i) {
		vec2 h = scene(ro + rd * travel);
		if (h.x < 0.001 || travel > 20.0) {
			material = h.y;
			break;
		}
		travel += h.x * 0.82;
	}

	vec3 color = sky + 0.08 * exp(-3.5 * dot(uv, uv)) * vec3(1.0, 0.4, 0.18);
	if (travel <= 20.0 && material > -0.5) {
		vec3 p = ro + rd * travel;
		vec3 n = getNormal(p);
		vec3 sunDir = normalize(vec3(0.45, 0.75, 0.35));
		float diff = 0.18 + 0.82 * max(dot(n, sunDir), 0.0);
		float rim = pow(1.0 - max(dot(n, -rd), 0.0), 3.0);
		vec3 accent = mix(
			vec3(0.2, 0.72, 1.2),
			vec3(1.2, 0.72, 0.25),
			float(powerConnected));

		vec3 base;
		if (material < 0.5) {
			float floorBands = 0.5 + 0.5 * sin(
				p.x * 2.2 +
				p.z * 1.4 +
				inclination * 5.0 +
				time * 0.35);
			base = mix(
				vec3(0.04, 0.05, 0.07),
				vec3(0.08, 0.12, 0.16),
				floorBands);
		} else if (material < 1.5) {
			float stripes = 0.5 + 0.5 * sin(
				p.y * (12.0 + float(notificationCount)) +
				time * 2.0 +
				gyroDir.x * 6.0);
			base = mix(
				vec3(0.14, 0.12, 0.18),
				accent,
				0.32 + 0.38 * stripes + 0.2 * battery);
		} else if (material < 2.5) {
			base = accent * (0.8 + 0.35 * abs(ftime) + 0.5 * ping);
		} else if (material < 3.5) {
			base = mix(
				vec3(1.2, 0.28, 0.15),
				vec3(0.22, 0.95, 1.6),
				dayAmt) * (1.0 + 0.9 * abs(ftime) + 1.4 * ping);
		} else {
			base = mix(
				vec3(0.4, 0.45, 1.2),
				accent,
				0.5 + 0.5 * sin(time + orientation.z));
		}

		vec3 lit = base * diff;
		lit += base * rim * (0.25 + 0.55 * ping);
		float fog = exp(-travel * mix(0.04, 0.12, 1.0 - baro));
		color = mix(color, lit, fog);
	}

	gl_FragColor = vec4(clamp(color, 0.0, 1.0), 1.0);
}

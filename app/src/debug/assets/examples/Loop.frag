#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif

// ftime repeats every FTIME_PERIOD seconds.
#define FTIME_PERIOD 4.0

uniform vec2 resolution; // size of the surface in pixels
uniform float ftime; // -1..1, once every FTIME_PERIOD seconds
uniform int second; // whole seconds since load
uniform float subsecond; // 0..1, the fraction of the current second
uniform int frame; // frames drawn since load, starting at 0
uniform float startRandom; // 0..1, new on every load

// A ring with one hand and a fading sweep behind it;
// turns 0..1 is a revolution clockwise from 12.
float dial(vec2 p, float turns, float px) {
	float a = 6.2831853 * turns;
	vec2 dir = vec2(sin(a), cos(a));
	float hand = length(p - dir * clamp(dot(p, dir), 0.0, 0.125));
	float d = min(abs(length(p) - 0.15), hand) - 0.006;
	float behind = fract(turns - atan(p.x, p.y) / 6.2831853);
	float sweep = 0.3 * pow(1.0 - behind, 6.0) * step(length(p), 0.15);
	return max(smoothstep(1.5 * px, 0.0, d), sweep);
}

void main(void) {
	float unit = min(resolution.x, resolution.y);
	vec2 p = (gl_FragCoord.xy - 0.5 * resolution) / unit;
	float px = 1.0 / unit;

	// `time` grows forever and loses precision, so motion based on it
	// starts to stutter after a while. These values wrap and stay exact.
	float loop = ftime * 0.5 + 0.5;
	int s = second - second / 60 * 60;
	float minute = (float(s) + subsecond) / 60.0;
	float tick = float(frame - frame / 60 * 60) / 60.0;

	// A new background hue on every load, including after rotation.
	vec3 color = 0.09 + 0.04 * cos(6.2831853 *
		(startRandom + vec3(0.0, 0.33, 0.67) + 0.15 * p.y));

	// Top left: ftime, one turn per FTIME_PERIOD.
	// Top right: second + subsecond, one turn per minute.
	// Bottom left: frame, one turn per 60 frames.
	// Bottom right: subsecond, one turn per second.
	// The bottom two only agree while the display draws 60 frames a second.
	color = mix(color, vec3(1.0, 0.65, 0.3), dial(p - vec2(-0.21, 0.21), loop, px));
	color = mix(color, vec3(0.45, 0.8, 1.0), dial(p - vec2(0.21, 0.21), minute, px));
	color = mix(color, vec3(0.65, 1.0, 0.5), dial(p - vec2(-0.21, -0.21), tick, px));
	color = mix(color, vec3(1.0, 0.55, 0.8), dial(p - vec2(0.21, -0.21), subsecond, px));

	gl_FragColor = vec4(color, 1.0);
}

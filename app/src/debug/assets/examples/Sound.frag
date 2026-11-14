#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif

uniform vec2 resolution; // size of the surface in pixels
uniform float micAmplitude; // mean level of the last ~40 ms, 0..1; 0 without permission
uniform float mediaVolume; // media volume 0..1 as set by the volume keys; read once a second
uniform sampler2D backbuffer; // the previous frame

// The ring's radius for a level 0..1.
float radius(float level) {
	return 0.08 + 0.26 * level;
}

void main(void) {
	float unit = min(resolution.x, resolution.y);
	vec2 p = (gl_FragCoord.xy - 0.5 * resolution) / unit;
	float px = 1.0 / unit;

	// Read and shape. Ears hear ratios, not differences, so the amplitude
	// becomes decibels: 0.001 (-60 dB, a quiet room) .. 1 (0 dB) to 0..1.
	float now = clamp(1.0 + log(max(micAmplitude, 1e-4)) / log(1000.0), 0.0, 1.0);

	// Smooth. The amplitude jumps from one 40 ms window to the next, and
	// a curve or a glow can soften a jump but not slow it down. That needs
	// memory, and a shader has none except backbuffer: the level is kept in
	// the bottom left pixel. It rises at once and falls by 1/90 a frame,
	// 1.5 s from full at 60 Hz, like the needle of a level meter.
	float held = texture2D(backbuffer, 0.5 / resolution).r;
	float level = max(now, held - 1.0 / 90.0);

	// Draw faint rings every 10 dB, then the level ring with a glow that
	// widens as it grows. Without permission it rests on the inner ring.
	float r = length(p);
	vec3 color = mix(vec3(0.08, 0.07, 0.12), vec3(0.02, 0.02, 0.04), min(1.0, 1.8 * r));
	for (int i = 0; i <= 6; i++) {
		color += 0.05 * smoothstep(px, 0.0, abs(r - radius(float(i) / 6.0)));
	}
	float d = abs(r - radius(level));
	vec3 hot = mix(vec3(1.0, 0.4, 0.25), vec3(1.0, 0.85, 0.55), level);
	color += hot * (smoothstep(2.0 * px, 0.0, d - 0.005) +
		(0.15 + 0.6 * level) * exp(-d / (0.01 + 0.05 * level)));

	// The media volume as an arc, clockwise from 12: a key press moves it a step.
	float turn = fract(atan(p.x, p.y) / 6.2831853);
	float band = smoothstep(px, 0.0, abs(r - 0.42) - 0.01);
	color = mix(color, vec3(0.13, 0.15, 0.21), band);
	color = mix(color, vec3(0.35, 0.8, 0.9), band * step(turn, mediaVolume));

	// Store the level for the next frame.
	if (gl_FragCoord.x < 1.0 && gl_FragCoord.y < 1.0) {
		color = vec3(level);
	}
	gl_FragColor = vec4(color, 1.0);
}

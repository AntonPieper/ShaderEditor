#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif

uniform vec2 resolution; // size of the surface in pixels
uniform float time; // seconds since load
uniform vec2 touch; // last touch in pixels; 0,0 before any; kept after the finger lifts
uniform int pointerCount; // fingers on the screen now, 0..10
// The previous frame, 8 bits per channel, read with linear filtering.
uniform sampler2D backbuffer;///min:l;mag:l;

void main(void) {
	float unit = min(resolution.x, resolution.y);
	vec2 p = (gl_FragCoord.xy - 0.5 * resolution) / unit;

	// The brush. touch keeps its value after the finger lifts, so
	// pointerCount says whether to paint. Until the first touch, a ghost
	// brush draws a figure eight so the screen is never empty.
	vec2 brush = (touch - 0.5 * resolution) / unit;
	float down = pointerCount > 0 ? 1.0 : 0.0;
	if (touch == vec2(0.0)) {
		float t = 1.3 * time;
		brush = 0.3 * vec2(sin(t), 0.6 * sin(2.0 * t));
		down = 1.0;
	}
	vec3 ink = 0.55 + 0.45 * cos(6.2831853 * (0.07 * time + vec3(0.0, 0.33, 0.67)));
	float mark = down * exp(-pow(distance(p, brush) / 0.035, 2.0));

	// The feedback idiom: the previous frame times a decay, plus the new mark.
	// 1. Read the previous frame half a pixel lower, so the ink drifts up;
	//    linear filtering blends neighbours, which softens it as it rises.
	vec3 previous = texture2D(backbuffer, (gl_FragCoord.xy - vec2(0.0, 0.5)) / resolution).rgb;
	// 2. Fade it. 0.98 a frame halves the ink every 34 frames: 0.6 s at
	//    60 Hz but 1.1 s at 30 Hz, because the decay counts frames, not
	//    seconds. In 8 bits, 0.98 of a dim value rounds back to itself, so
	//    1/255 more is taken off to let it reach black.
	vec3 faded = previous * 0.98 - 1.0 / 255.0;
	// 3. Add the new mark.
	vec3 color = faded + 0.35 * ink * mark;

	// A dark backdrop under the ink. Adding it would build up frame after
	// frame; max() only fills in where the ink has faded below it.
	vec3 backdrop = mix(vec3(0.02, 0.02, 0.05), vec3(0.07, 0.05, 0.11), gl_FragCoord.y / resolution.y);
	gl_FragColor = vec4(max(color, backdrop), 1.0);
}

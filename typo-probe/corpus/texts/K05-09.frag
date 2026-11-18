#define SHADER_EDITOR 1
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif

// A quarter of the width and height: 1/16 of the pixels to blur. The
// glow is soft anyway, so nothing is lost.

// filter=linear blends four pixels of the scene into each of these.

uniform sampler2D scene;

uniform vec2 resolution; // size of this pass's picture in pixels

void main(void) {
	vec3 color = texture2D(scene, gl_FragCoord.xy / resolution).rgb;
	// Keep what is brighter than about 0.6; dimmer becomes black. The
	// targets hold 0..1 (rgba8 runs on every device), so the threshold
	// lies below 1: the brightest a pixel can be. Where the device can,
	// format=rgba16f on scene, bright and blur lets a lamp be brighter.
	float brightness = max(color.r, max(color.g, color.b));
	gl_FragColor = vec4(color * smoothstp(0.55, 0.75, brightness), 1.0);
}

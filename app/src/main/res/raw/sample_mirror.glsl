#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif

uniform vec2 resolution; // size of the surface in pixels
uniform vec2 cameraAddent; // with cameraOrientation, turns screen 0..1
uniform mat2 cameraOrientation; // into upright camera 0..1 for this rotation
uniform samplerExternalOES cameraFront; // the front camera; black without permission

void main(void) {
	vec2 uv = gl_FragCoord.xy / resolution;

	// Read. Flip x first, so your right hand is on the right, as in a mirror;
	// then upright as in the Back camera example.
	vec2 st = cameraAddent + vec2(1.0 - uv.x, uv.y) * cameraOrientation;
	vec3 rgb = texture2D(cameraFront, st).rgb;

	// Shape: the filter. Brightness as the eye weighs it, 0..1, cut into
	// four bands; each band gets one ink, like a screen print. The steps
	// are soft at their top so camera noise does not sparkle along them.
	float luma = 4.0 * dot(rgb, vec3(0.299, 0.587, 0.114));
	float band = floor(luma) + smoothstep(0.8, 1.0, fract(luma));
	vec3 color = vec3(0.1, 0.09, 0.22);
	color = mix(color, vec3(0.5, 0.2, 0.45), clamp(band, 0.0, 1.0));
	color = mix(color, vec3(0.95, 0.45, 0.35), clamp(band - 1.0, 0.0, 1.0));
	color = mix(color, vec3(1.0, 0.9, 0.72), clamp(band - 2.0, 0.0, 1.0));

	// Without permission no frame arrives and every pixel reads black.
	// Three samples tell that apart from a dark room, which is never 0.
	vec3 probe = texture2D(cameraFront, vec2(0.5)).rgb +
		texture2D(cameraFront, vec2(0.25, 0.75)).rgb +
		texture2D(cameraFront, vec2(0.75, 0.25)).rgb;
	if (dot(probe, vec3(1.0)) < 0.01) {
		// Draw an empty pane of glass that catches the light.
		float diagonal = uv.x * resolution.x / resolution.y + uv.y;
		color = mix(vec3(0.07, 0.08, 0.12), vec3(0.16, 0.18, 0.25), uv.y);
		color += 0.06 * smoothstep(0.12, 0.0, abs(diagonal - 0.9));
		color += 0.04 * smoothstep(0.04, 0.0, abs(diagonal - 1.15));
	}

	gl_FragColor = vec4(color, 1.0);
}

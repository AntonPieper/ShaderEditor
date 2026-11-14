#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif

#pragma se input "blur.frag"
uniform sampler2D blurred;
uniform vec2 resolution;
uniform int frame;

// Left half: the disc as blur.frag drew it this frame. Right half: the
// same disc with a hard edge. Top band: green when blur.frag drew in
// this frame (its frame stamp equals frame), red when it is a frame old.
void main(void) {
	vec2 uv = gl_FragCoord.xy / resolution;
	vec4 c = texture2D(blurred, uv);
	if (uv.y > 0.9) {
		float stamp = floor(c.r * 255.0 + 0.5);
		bool same = stamp == mod(float(frame), 256.0);
		gl_FragColor = same
				? vec4(0.0, 0.8, 0.0, 1.0)
				: vec4(0.9, 0.0, 0.0, 1.0);
		return;
	}
	gl_FragColor = uv.x < 0.5
			? vec4(c.rgb, 1.0)
			: vec4(vec3(step(0.5, c.r)), 1.0);
}

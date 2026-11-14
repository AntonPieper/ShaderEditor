#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif

#pragma se input "main.frag" frame=previous
uniform sampler2D previous;
#pragma se target initial="seed.frag"
uniform vec2 resolution;

// Every frame the picture moves one pixel to the right, wrapping around.
// The top band counts frames in red, from the 0 the seed leaves there.
void main(void) {
	vec2 p = gl_FragCoord.xy;
	if (p.y > resolution.y * 0.9) {
		float count = floor(texture2D(previous, p / resolution).r * 255.0 +
				0.5);
		gl_FragColor = vec4(mod(count + 1.0, 256.0) / 255.0, 0.0, 0.0, 1.0);
		return;
	}
	float x = mod(p.x - 1.0, resolution.x);
	gl_FragColor = texture2D(previous, vec2(x, p.y) / resolution);
}

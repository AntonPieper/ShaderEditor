#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif

#pragma se input "main.frag" frame=previous
uniform sampler2D previous;
#pragma se target size=256x256
uniform vec2 resolution;
uniform int frame;

// A fixed-size target. Frame 0 drops one white square, which then moves
// one texel to the right per frame, wrapping around. The top band
// counts frames in red and is green while that count matches frame.
void main(void) {
	vec2 p = gl_FragCoord.xy;
	vec2 uv = p / resolution;
	if (uv.y > 0.9) {
		float count = floor(texture2D(previous, uv).r * 255.0 + 0.5);
		float next = mod(count + 1.0, 256.0);
		bool ok = next == mod(float(frame) + 1.0, 256.0);
		gl_FragColor = vec4(next / 255.0, ok ? 1.0 : 0.0, 0.0, 1.0);
		return;
	}
	vec4 c = texture2D(previous,
			vec2(mod(p.x - 1.0, resolution.x), p.y) / resolution);
	if (frame == 0 && p.x > 20.0 && p.x < 36.0 && p.y > 100.0 &&
			p.y < 116.0) {
		c = vec4(1.0);
	}
	gl_FragColor = c;
}

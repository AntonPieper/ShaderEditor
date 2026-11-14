#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif

#pragma se input "pattern.frag" filter=nearest
uniform sampler2D crisp;
#pragma se input "pattern.frag" filter=linear
uniform sampler2D soft;
uniform vec2 resolution;

// The same 4x4 pass output in one program: left half nearest, right
// half linear, each stretched over its half.
void main(void) {
	vec2 uv = gl_FragCoord.xy / resolution;
	gl_FragColor = uv.x < 0.5
			? texture2D(crisp, vec2(uv.x * 2.0, uv.y))
			: texture2D(soft, vec2(uv.x * 2.0 - 1.0, uv.y));
}

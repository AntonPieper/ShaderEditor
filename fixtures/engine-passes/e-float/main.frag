#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif

#pragma se input "hdr16.frag"
uniform sampler2D hdr16;
#pragma se input "hdr8.frag"
uniform sampler2D hdr8;
uniform vec2 resolution;

// Left half from the rgba16f pass, right half from the same pass in
// rgba8, each stretched over its half. Top: red divided by 4. Bottom:
// green minus 2. Values above 1 survive only in rgba16f.
void main(void) {
	vec2 uv = gl_FragCoord.xy / resolution;
	bool left = uv.x < 0.5;
	vec2 at = vec2(left ? uv.x * 2.0 : uv.x * 2.0 - 1.0, uv.y);
	vec4 c = left ? texture2D(hdr16, at) : texture2D(hdr8, at);
	gl_FragColor = uv.y > 0.5
			? vec4(c.r / 4.0, 0.0, 0.0, 1.0)
			: vec4(0.0, c.g - 2.0, 0.0, 1.0);
}

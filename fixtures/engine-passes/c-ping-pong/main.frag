#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif

#pragma se input "a.frag"
uniform sampler2D a;
#pragma se input "b.frag"
uniform sampler2D b;
uniform vec2 resolution;
uniform int frame;

// a(n) = b(n-1) + 1 and b(n) = a(n-1) + 2 from 0 give a + b = 3 (n + 1)
// at frame n. Top half: green while that holds, red when it does not.
// Bottom half: a as grey on the left, b on the right.
void main(void) {
	vec2 uv = gl_FragCoord.xy / resolution;
	float va = floor(texture2D(a, uv).r * 255.0 + 0.5);
	float vb = floor(texture2D(b, uv).r * 255.0 + 0.5);
	if (uv.y > 0.5) {
		bool ok = mod(va + vb, 256.0) ==
				mod(3.0 * (float(frame) + 1.0), 256.0);
		gl_FragColor = ok
				? vec4(0.0, 0.8, 0.0, 1.0)
				: vec4(0.9, 0.0, 0.0, 1.0);
		return;
	}
	gl_FragColor = vec4(vec3((uv.x < 0.5 ? va : vb) / 255.0), 1.0);
}

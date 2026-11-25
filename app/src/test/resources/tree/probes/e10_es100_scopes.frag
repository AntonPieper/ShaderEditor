precision mediump float;
varying vec2 uv;
invariant uv;
uniform float time;
float i = 0.25;
const int N = 4;
float a[N];
struct S { float w[N]; };
float sum(S s) {
	float t = 0.0;
	for (int i = 0; i < N; ++i) { t += s.w[i]; }
	for (int i = 0; i < 2; ++i) { float t2 = t; t = t2 + float(i); }
	if (t > 0.0) { float i = 2.0; t += i; } else t -= i;
	return t;
}
void main() {
	precision highp float;
	S s;
	for (int i = 0; i < N; ++i) { s.w[i] = float(i) * time; a[i] = s.w[i]; }
	gl_FragColor = vec4(sum(s) + i + a[1], uv, 1.0);
}

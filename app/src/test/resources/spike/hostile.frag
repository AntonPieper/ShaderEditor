#version 300 es
precision highp float;
// SPIKE, throwaway: code in the style people write for ShaderToy,
// written for this study: macros for everything, several statements a
// line, comma operators, loops without bodies. Valid GLSL ES 3.00.
uniform vec3 iResolution;
uniform float iTime;
uniform sampler2D iChannel0;
out vec4 fragColor;

#define R iResolution.xy
#define T iTime
#define PI 3.14159
#define rot(a) mat2(cos(a), sin(a), -sin(a), cos(a))
#define sat(x) clamp(x, 0., 1.)
#define tex(p) texture(iChannel0, p)
#define V3 vec3
#define FOR(i, n) for (int i = 0; i < n; i++)
#define SWAP(a, b) { float tmp_ = a; a = b; b = tmp_; }
#define MAX_STEPS 80
#define hash(p) fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453)
#if 1
#define FAR 40.
#else
#define FAR 20.
#endif

struct M { float d; int id; V3 col; };
const vec2 e = vec2(.001, 0);
float g = 0., glow;
mat2 r2;

float box(V3 p, V3 b) { V3 q = abs(p) - b; return length(max(q, 0.)) + min(max(q.x, max(q.y, q.z)), 0.); }
float smin(float a, float b, float k) { float h = sat(.5 + .5 * (b - a) / k); return mix(b, a, h) - k * h * (1. - h); }

M map(V3 p) {
	M m = M(FAR, 0, V3(0));
	p.xz *= rot(T * .3), p.xy *= r2;
	float d = length(p) - 1., d2 = box(p - V3(0, sin(T), 0), V3(.5));
	if (d2 < d) SWAP(d, d2)
	FOR(i, 3) {
		p = abs(p) - .3 * float(i + 1);
		d = smin(d, box(p, V3(.1, .2, .3)), .2);
	}
	glow += .01 / (.01 + d * d);
	m.d = d; m.id = d2 > 1. ? 1 : 2; m.col = .5 + .5 * cos(V3(0, 2, 4) + p.zxy);
	return m;
}

V3 normal(V3 p) {
	float d = map(p).d;
	return normalize(d - V3(map(p - e.xyy).d, map(p - e.yxy).d, map(p - e.yyx).d));
}

void mainImage(out vec4 O, in vec2 U) {
	vec2 uv = (U - .5 * R) / R.y, m2 = tex(uv).xy;
	V3 ro = V3(0, 0, -4), rd = normalize(V3(uv, 1)), p, n, c = V3(0);
	r2 = rot(T);
	float t = 0., d; int i;
	M h;
	for (i = 0; i < MAX_STEPS && t < FAR; i++) p = ro + rd * t, h = map(p), d = h.d, t += d * .8;
	if (t < FAR) n = normal(p), c = h.col * sat(dot(n, normalize(V3(1, 2, -3)))) + pow(sat(1. + dot(n, rd)), 4.);
	c += glow * V3(.2, .5, 1) * .05 + hash(uv) * .02;
	c = mix(c, V3(.1), 1. - exp(-.02 * t * t));
	O = vec4(pow(c, V3(.4545)), 1);
}

void main() { mainImage(fragColor, gl_FragCoord.xy); }

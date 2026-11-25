#version 300 es
precision highp float;
out vec4 color;
struct P { float P2; vec2 v; };
struct Q { P p; float v; };
float v = 1.0;
void main() {
	Q q = Q(P(1.0, vec2(2.0)), 3.0);
	float P2 = q.p.P2 + q.v + q.p.v.y + v;
	struct { float v; } anon, anon2;
	anon.v = P2; anon2 = anon;
	color = vec4((true ? anon : anon2).v, (q, q).p.v, P2);
}

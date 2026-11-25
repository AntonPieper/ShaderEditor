#version 300 es
precision highp float;
out vec4 color;
struct S { float a; };
struct T { S s; vec3 v; float a[2]; };
S g;
float f(S S0) {
	S l = S(1.0);
	{
		struct S { vec2 b; float a; };
		S m = S(vec2(2.0), 3.0);
		l.a += m.b.y + m.a;
	}
	S after = S(4.0);
	return l.a + after.a + S0.a;
}
void main() {
	T t = T(S(1.0), vec3(2.0), float[2](3.0, 4.0));
	T ts[2] = T[2](t, t);
	float S1 = t.s.a + ts[1].v.y + t.a[1] + float(t.a.length()) + ts[0].s.a;
	color = vec4(f(g) + S1, t.v.xy, ts.length());
}

#version 300 es
precision highp float;
out vec4 color;
struct S { float a; };
float f(float S) { return S * 2.0; }
void main() {
	float r = 0.0;
	{
		float S = 3.0;
		r += S + f(S);
	}
	S s = S(r);
	color = vec4(s.a);
}

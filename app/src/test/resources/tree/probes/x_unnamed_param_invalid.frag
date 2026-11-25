#version 300 es
precision highp float;
out vec4 color;
struct S { float a; };
float f(in S) {
	S t = S(2.0);
	return t.a;
}
void main() {
	color = vec4(f(S(1.0)));
}

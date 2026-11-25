#version 300 es
precision highp float;
out vec4 color;
struct S { float a; } k = S(1.0);
float f() {
	float k = 2.0;
	return k;
}
void main() {
	color = vec4(k.a + f());
}

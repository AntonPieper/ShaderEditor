#version 300 es
precision mediump float;
out vec4 color;
struct S { float a; } x, k;
float f() {
	float k = 2.0;
	return k;
}
void main() {
	k.a = 1.0;
	color = vec4(k.a + f());
}

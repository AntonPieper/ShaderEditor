#version 300 es
precision mediump float;
out vec4 color;
float f() {
	float k = 2.0;
	return k;
}
void main() {
	struct S { float a; } x = S(1.0), k = S(3.0);
	color = vec4(x.a + k.a + f());
}

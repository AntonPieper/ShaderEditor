#version 300 es
precision mediump float;
uniform bool b;
out vec4 color;
float f() {
	float k = 1.0;
	return k;
}
void main() {
	float a = b ? 1.0 : 2.0, k = 3.0;
	color = vec4(a, k, f(), 1.0);
}

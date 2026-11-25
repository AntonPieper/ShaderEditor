#version 300 es
precision highp float;
out vec4 color;
#define DECL(n) float n = 2.0;
#define D2(n) DECL(n)
D2(k)
float f() {
	float k = 1.0;
	return k;
}
void main() {
	color = vec4(k + f());
}

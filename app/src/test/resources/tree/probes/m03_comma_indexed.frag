#version 300 es
precision highp float;
out vec4 color;
void main() {
	float a[2];
	float k = 0.0;
	a[0] = 1.0, k = 2.0;
	color = vec4(k, a[0], 0.0, 1.0);
}

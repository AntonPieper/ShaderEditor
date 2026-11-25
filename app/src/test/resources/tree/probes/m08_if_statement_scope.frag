#version 300 es
precision highp float;
out vec4 color;
uniform bool a, b;
void main() {
	float k = 1.0;
	if (a) if (b) int k = 2;
	color = vec4(k);
}

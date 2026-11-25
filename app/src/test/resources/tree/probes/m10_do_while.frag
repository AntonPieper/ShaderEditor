#version 300 es
precision highp float;
out vec4 color;
void main() {
	int n = 0;
	do {
		float n = 1.0;
		n += 1.0;
	} while (n < 3);
	color = vec4(n);
}

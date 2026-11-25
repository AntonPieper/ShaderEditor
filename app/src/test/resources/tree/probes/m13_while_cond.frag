#version 300 es
precision highp float;
out vec4 color;
uniform int n;
void main() {
	float b = 0.5;
	int i = 0;
	while (bool b = i < n) {
		i++;
		if (b) break;
	}
	color = vec4(b);
}

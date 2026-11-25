#version 300 es
precision highp float;
out vec4 color;
void main() {
	int x = 1;
	{
		float x = float(x) + 1.0;
		color = vec4(x);
	}
}

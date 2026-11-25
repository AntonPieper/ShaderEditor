#version 300 es
precision highp float;
out vec4 color;
void main() {
	int a = 1;
	{
		float a = 1.0, b = a;
		color = vec4(b);
	}
}

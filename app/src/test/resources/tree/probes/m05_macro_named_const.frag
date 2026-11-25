#version 300 es
precision highp float;
out vec4 color;
#define const float k = 2.0; float
void main() {
	float k = 1.0;
	{
		const m = 1.0;
		color = vec4(k);
	}
}

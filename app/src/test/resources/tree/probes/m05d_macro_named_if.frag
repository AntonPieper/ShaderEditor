#version 300 es
precision highp float;
out vec4 color;
#define if(c) float k = 2.0;
void main() {
	float k = 1.0;
	{
		if (true)
		color = vec4(k);
	}
}

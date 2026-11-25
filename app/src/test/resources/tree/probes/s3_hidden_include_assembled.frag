#version 300 es
precision highp float;
out vec4 color;
#define LOCAL_K float k = 2.0;
void main() {
	float k = 1.0;
	{
		LOCAL_K
		color = vec4(k);
	}
}

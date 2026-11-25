#version 300 es
precision highp float;
out vec4 color;
#define vec3 float k = 2.0; vec3
void main() {
	float k = 1.0;
	{
		vec3 m;
		color = vec4(k);
	}
}

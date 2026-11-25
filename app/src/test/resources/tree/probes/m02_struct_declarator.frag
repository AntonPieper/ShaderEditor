#version 300 es
precision highp float;
out vec4 color;
void main() {
	float k = 1.0;
	{
		struct S { float a; } k;
		k.a = 2.0;
		color = vec4(k.a);
	}
}

#version 300 es
precision highp float;
out vec4 color;
uniform vec2 v;
void main() {
	int x = 1;
	{
		float x = v[0, x];
		color = vec4(x);
	}
}

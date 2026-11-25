#version 300 es
precision mediump float;
float f(float x) { return x * 2.0; }
out vec4 color;
void main() {
	float f(float);
	color = vec4(f(0.25));
}

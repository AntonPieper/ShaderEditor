#version 300 es
precision highp float;
out vec4 color;
float step = 0.25;
float f(float min, float max) { return clamp(step, min, max); }
void main() {
	float texture = 1.0;
	{ float length = 2.0; color = vec4(length * texture); }
	color += vec4(f(0.0, 1.0), length(vec2(1.0)), step, 1.0);
}

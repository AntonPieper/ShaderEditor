#version 300 es
precision mediump float;
layout(location = 0) out vec4 color, glow;
vec4 dim(vec4 glow) {
	return glow * 0.5;
}
void main() {
	color = vec4(1.0);
	glow = dim(color);
}

#version 300 es
precision mediump float;
out vec4 color;
float max(vec3 v) { return max(v.x, max(v.y, v.z)); }
void main() {
	float m = max(vec3(0.1, 0.5, 0.2));
	color = vec4(m, max(m, 0.3), 0.0, 1.0);
}

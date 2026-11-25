#version 100
precision mediump float;
float max(vec3 v) { return max(v.x, max(v.y, v.z)); }
void main() {
	float m = max(vec3(0.1, 0.5, 0.2));
	gl_FragColor = vec4(m, max(m, 0.3), 0.0, 1.0);
}

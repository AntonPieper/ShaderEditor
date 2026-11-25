precision mediump float;
float min(vec3 v);
void main() {
	gl_FragColor = vec4(min(vec3(0.1, 0.2, 0.3)), min(0.5, 0.7), 0.0, 1.0);
}
float min(vec3 v) { return min(min(v.x, v.y), v.z); }

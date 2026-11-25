precision mediump float;
float f() {
	float k = 1.0;
	return k;
}
void main() {
	highp float a = 1.0, k = 2.0;
	gl_FragColor = vec4(a, k, f(), 1.0);
}

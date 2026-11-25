precision highp float;
float f(float x) {
	float y = x;
	float x = 2.0;
	return x * y;
}
void main() {
	gl_FragColor = vec4(f(1.0));
}

precision highp float;
void main() {
	float s = 0.0;
	for (int i = 0; i < 3; ++i) {
		float i = 2.0;
		s += i;
	}
	gl_FragColor = vec4(s);
}

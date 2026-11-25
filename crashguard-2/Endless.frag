precision mediump float;
void main() {
	float v = 0.0;
	for (int i = 0; i < 100000000; ++i) { v += sin(float(i)); }
	gl_FragColor = vec4(v);
}

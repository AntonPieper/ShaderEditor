precision mediump float;
float round(float x) { return floor(x + 0.5); }
float tanh(float x) { float e = exp(2.0 * x); return (e - 1.0) / (e + 1.0); }
mat2 transpose(mat2 m) { return mat2(m[0][0], m[1][0], m[0][1], m[1][1]); }
float texture = 0.5;
void main() {
	float layout = round(1.4) + tanh(0.5) + transpose(mat2(1.0))[0][1];
	float smooth = layout * texture;
	gl_FragColor = vec4(smooth, layout, 0.0, 1.0);
}

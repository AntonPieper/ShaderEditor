#version 100
precision mediump float;
float fract(float x) { return x - floor(x); }
void main() {
	gl_FragColor = vec4(fract(1.5), fract(vec2(2.5)), 1.0);
}

precision mediump float;
#define NAME(n) n
float NAME(k) = 2.0;
float f() {
	float k = 1.0;
	return k;
}
void main() {
	gl_FragColor = vec4(k + f());
}

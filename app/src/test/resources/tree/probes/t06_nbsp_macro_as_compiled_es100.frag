precision mediump float;
#define DECL(n) float n = 2.0;
DECL(k)
float f() {
	float k = 1.0;
	return k;
}
void main() {
	gl_FragColor = vec4(k + f());
}

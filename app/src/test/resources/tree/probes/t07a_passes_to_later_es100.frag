precision mediump float;
#define D2(n) DECL(n)
#define DECL(n) float n = 2.0;
D2(k)
float f() {
	float k = 1.0;
	return k;
}
void main() {
	gl_FragColor = vec4(k + f());
}

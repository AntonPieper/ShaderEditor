precision mediump float;
#define VEC(n) vec3 n
uniform VEC(k);
float f() {
	float k = 1.0;
	return k;
}
void main() {
	gl_FragColor = vec4(k + f(), 1.0);
}

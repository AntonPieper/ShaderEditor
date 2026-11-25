precision mediump float;
#define VEC vec3
#define MAKE(name, v) struct name { VEC v; }
MAKE(A, a);
MAKE(B, b);
#define GET(s, f) s.f
void main() {
	A x = A(VEC(1.0));
	B y = B(VEC(2.0));
	gl_FragColor = vec4(GET(x, a) + GET(y, b), 1.0);
}

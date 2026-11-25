#version 300 es
precision highp float;
out vec4 color;
struct L { vec3 pos; float power; };
uniform L light;
#define POS light.pos
#define DECL(T, n) T n = T(0.5)
#define SQ(x) ((x) * (x))
#define k kk
float kk = 2.0;
float f(float x) {
	DECL(float, y);
	DECL(vec3, p);
	return SQ(x + y) + p.x + POS.x + k;
}
#if 0
float hidden = 1.0;
#else
float shown = 3.0;
#endif
#undef k
float k = 4.0;
void main() { color = vec4(f(1.0) + shown + k + kk); }

#version 300 es
precision highp float;
#define AA -2
#define N 1 + 1
#define M N
out vec4 c;
#if AA > 1
#error never
#endif
#if 3 - M == 3
void main() { c = vec4(1.0); }
#else
void main() { c = vec4(0.0); }
#endif

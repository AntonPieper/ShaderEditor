#version 300 es
precision highp float;
int f(int a){return a;}int f(float a){return 2;}
#if __VERSION__ == 100
#define T int
#else
#define T float
#endif
void main(){T v;f(v);}
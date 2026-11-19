#version 300 es
precision mediump float;
#define N 3
float f(float a[N]); float f(float a[N]){return a[0];} out vec4 color; void main(){float a[N];color=vec4(f(a));}
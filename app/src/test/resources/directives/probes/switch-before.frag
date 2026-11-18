#version 300 es
precision highp float;
#define OLD 1
#ifdef NEW
const int a=2;
#else
const int a=1;
#endif
void main(){int b=a;}
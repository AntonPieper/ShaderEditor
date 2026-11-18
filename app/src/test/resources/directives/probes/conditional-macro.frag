#version 300 es
precision highp float;
#if __VERSION__ == 300
#define V 1
#else
#define V 2
#endif
void main(){int x=V;}
#version 300 es
precision highp float;
#if __VERSION__ == 300
uniform B{int x;}b;
#else
uniform C{float x;}b;
#endif
void main(){float a=float(b.x);}
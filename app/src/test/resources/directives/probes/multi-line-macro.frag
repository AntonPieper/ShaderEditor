#version 300 es
precision highp float;
int x;
#define ADD(a,b) ((a)+\
(b)+x)
void main(){int y=ADD(1,2);}
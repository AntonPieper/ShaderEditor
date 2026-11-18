#version 300 es
precision highp float;
int f(int a){return a;}int f(float a){return 1;}int f(vec2 a){return 2;}void main(){f(1);f(1.);f(vec2(1));}
#version 300 es
precision highp float;
int f(float a){return 1;}int f(int a){return 2;}int sin(int a){return a;}void main(){f(sin(1.));f(sin(1));}
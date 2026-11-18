#version 300 es
precision highp float;
int f(float[2] a);int f(float a[2]){return 1;}void main(){float a[2];f(a);}
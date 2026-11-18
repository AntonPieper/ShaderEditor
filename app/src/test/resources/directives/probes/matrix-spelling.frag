#version 300 es
precision highp float;
int f(mat2 m);int f(mat2x2 m){return 1;}void main(){f(mat2(1));}
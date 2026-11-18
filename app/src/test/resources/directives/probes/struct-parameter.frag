#version 300 es
precision highp float;
struct S{int x;}; int f(S a){return a.x;}void main(){f(S(1));}
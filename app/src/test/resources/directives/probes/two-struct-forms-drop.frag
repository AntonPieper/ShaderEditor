#version 300 es
precision highp float;
struct A{int x;};struct B{int y;};int f(A a){return 0;}int f(B b){return 1;}void main(){f(B(1));}
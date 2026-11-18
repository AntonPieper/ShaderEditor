#version 300 es
precision highp float;
struct S{int x;}; S f(){return S(1);}void main(){f();}
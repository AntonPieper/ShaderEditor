#version 300 es
precision highp float;
struct A{int x;};struct B{A sub;};void main(){B b;b.sub.x=1;}
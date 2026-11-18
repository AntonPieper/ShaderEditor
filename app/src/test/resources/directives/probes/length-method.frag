#version 300 es
precision highp float;
struct S{int length;};void main(){int a[3];S s;s.length=a.length();}
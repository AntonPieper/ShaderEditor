#version 300 es
precision highp float;
#define CAT(a,b) a ## b
int hello;void main(){CAT(hel,lo)++;}
#version 300 es
precision highp float;
uniform B{int x;} b[2];void main(){int y=b[0].x;}
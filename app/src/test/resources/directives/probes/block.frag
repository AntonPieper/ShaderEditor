#version 300 es
precision mediump float;
uniform B{float x;}b; out vec4 color; void main(){color=vec4(b.x);}
#version 300 es
precision mediump float;
out vec4 color;
void main() { float step = 0.5; color = vec4(step(step, 0.7)); }

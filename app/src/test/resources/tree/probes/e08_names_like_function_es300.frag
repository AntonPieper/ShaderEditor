#version 300 es
precision highp float;
out vec4 color;
float g(float g) { return g * 2.0; }
float h() { float h = 3.0; return h; }
float k = 1.0;
float use() { { float k = 2.0; return k + g(k); } }
void main() { color = vec4(g(1.0) + h() + use() + k); }

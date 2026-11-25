#version 300 es
precision highp float;
out vec4 color;
float y = 2.0;
float y = 3.0;
struct S { float a; };
struct S { vec2 b; };
void main() { S s; color = vec4(y + s.a); }

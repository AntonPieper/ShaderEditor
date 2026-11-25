#version 300 es
precision highp float;
out vec4 color;
struct R { float d; int id; };
R scene(vec3 p);
float f(float a);
float f(vec2 b) { return b.x + f(b.y); }
float f(float c) { return c * 2.0; }
R scene(vec3 q) { return R(length(q) - 1.0, 1); }
R scene(vec2 q) { return scene(vec3(q, 0.0)); }
void main() {
	R r = scene(vec3(1.0));
	color = vec4(f(vec2(1.0)), f(0.5), scene(vec3(0.0)).d, float(r.id + scene(vec2(1.0)).id));
}

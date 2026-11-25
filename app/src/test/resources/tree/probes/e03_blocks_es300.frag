#version 300 es
precision highp float;
out vec4 color;
struct L { vec3 pos; float power; };
layout(std140) uniform Lights { L light; vec4 tint; };
uniform Camera { mat4 view; vec3 eye; } cam;
uniform Material { vec4 albedo; float rough; } mats[2];
float power(float x) { return x * light.power; }
void main() {
	vec3 pos = cam.eye - light.pos;
	color = tint * mats[1].albedo + vec4(pos, power(mats[0].rough)) + cam.view[0];
}

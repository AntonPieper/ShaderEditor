precision mediump float;
float smin(float a, float b) { return min(a, b); }
vec2 smin(vec2 a, vec2 b) { return vec2(smin(a.x, b.x), smin(a.y, b.y)); }
void main() { gl_FragColor = vec4(smin(vec2(0.2), vec2(0.3)), smin(0.1, 0.4), 1.0); }

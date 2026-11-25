#version 300 es
precision highp float;
out vec4 color;
float i = 0.5;
int k = 2;
float f(float k) {
	float s = k;
	for (int i = 0; i < 3; ++i) { float k = float(i); s += k; }
	for (int i = 0; i < 2; ++i) s += float(i);
	{ float i = 3.0; s += i; { int s2 = int(i); s += float(s2); } }
	while (s < 10.0) { float i = s; s += i + 1.0; }
	do { float k = 1.0; s += k; } while (s < k);
	return s + i;
}
void main() { color = vec4(f(float(k)) + i); }

#version 300 es
precision highp float;
out vec4 color;
float b = 1.0;
void main() {
	float s = 0.0;
	int n = 3;
	while (bool b = n > 0) { s += b ? 1.0 : 0.0; --n; }
	for (int i = 0; bool c = i < 2; ++i) { s += float(c); }
	switch (n) {
	case 0:
		float y = 1.0;
		s += y;
		break;
	default:
		y = 2.0;
		s += y;
	}
	color = vec4(s, b, 0.0, 1.0);
}

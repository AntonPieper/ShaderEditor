#version 300 es
precision highp float;
out vec4 color;
uniform int i;
void main() {
	int k = 0;
	switch (i) {
	case 0:
		float k = 1.0;
		break;
	case 1:
		k = 2.0;
		color = vec4(k);
	}
	color = vec4(k);
}

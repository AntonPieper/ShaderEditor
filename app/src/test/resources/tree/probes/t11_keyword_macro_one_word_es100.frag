precision mediump float;
#define return float
void main() {
	float k = 1.0;
	{
		return k = 2.0;
		gl_FragColor = vec4(k);
	}
}

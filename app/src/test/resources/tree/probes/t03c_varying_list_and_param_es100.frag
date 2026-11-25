precision mediump float;
varying highp vec2 uv, pos;
float ring(vec2 pos) {
	return length(pos);
}
void main() {
	gl_FragColor = vec4(uv, ring(pos), 1.0);
}

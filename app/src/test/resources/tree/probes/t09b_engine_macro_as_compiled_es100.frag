#define SHADER_EDITOR 1
precision mediump float;
#ifndef SHADER_EDITOR
#define SHADER_EDITOR 0
#endif
void main() {
	gl_FragColor = vec4(float(SHADER_EDITOR));
}

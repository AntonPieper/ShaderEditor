#ifndef GL_FRAGMENT_PRECISION_HIGH
#error not defined
#endif
#ifndef GL_ES
#error GL_ES not defined
#endif
#if __VERSION__ != 100
#error version
#endif
precision mediump float;
void main() {
	gl_FragColor = vec4(1.0);
}

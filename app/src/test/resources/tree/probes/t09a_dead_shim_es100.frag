precision mediump float;
#if __VERSION__ >= 300
#define texture2D texture
#endif
uniform sampler2D tex;
varying vec2 uv;
void main() {
	gl_FragColor = texture2D(tex, uv);
}

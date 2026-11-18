precision mediump float;
#define RET return
float x; float f(){ RET x; }
void main(){gl_FragColor=vec4(f());}
precision mediump float;
#define RET return
float x=1.; float y=2.; float f(){ RET x; }
void main(){gl_FragColor=vec4(f());}
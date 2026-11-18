precision mediump float;
float f(float a); void g(){f(1.);} float f(float a){return a;}
void main(){g();gl_FragColor=vec4(f(1.));}
#version 300 es
precision highp float;
int x;void main(){
#if __VERSION__ == 300
int x;
#endif
#if __VERSION__ == 300
int y;
#endif
x++;}
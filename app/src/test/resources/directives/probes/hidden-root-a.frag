#version 300 es
precision highp float;
#define MODE 1
#ifdef MODE
int x;
#else
int x;
#endif
void shared(){x++;}
void main(){shared();}
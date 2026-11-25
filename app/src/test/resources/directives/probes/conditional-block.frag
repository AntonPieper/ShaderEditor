#version 300 es
precision highp float;
uniform B{
#if __VERSION__ == 300
int x;
#else
int y;
#endif
} b;void main(){int z=b.x;}
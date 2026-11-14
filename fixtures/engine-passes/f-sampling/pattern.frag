#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif

#pragma se target size=4x4

// A 4x4 checkerboard of red and blue texels.
void main(void) {
	vec2 cell = floor(gl_FragCoord.xy);
	float odd = mod(cell.x + cell.y, 2.0);
	gl_FragColor = vec4(odd, 0.0, 1.0 - odd, 1.0);
}

#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif

#pragma se input "tile.frag" wrap=repeat
uniform sampler2D tiles;
uniform vec2 resolution;

// tile.frag twice across and twice up, which needs repeat on a target
// the size of the screen.
void main(void) {
	gl_FragColor = texture2D(tiles, gl_FragCoord.xy / resolution * 2.0);
}

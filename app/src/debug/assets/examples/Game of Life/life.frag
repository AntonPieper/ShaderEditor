#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif

// The board: one pixel per cell, an eighth of the screen each way. It
// starts as what seed.frag draws.
#pragma se target scale=0.125 initial="seed.frag"
// This pass's own picture of the frame before: the board it advances.
#pragma se input "life.frag" frame=previous
uniform sampler2D board;

uniform vec2 resolution; // size of the board in cells
uniform int pointerCount; // fingers on the screen now, 0..10
uniform vec3 pointers[10]; // x, y in cells of this board, and pressure

// 1 when the cell at offset from this one is alive. Past the edge the
// edge cell is read again (repeating needs a power-of-two size on
// GLES 2).
float alive(float x, float y) {
	return step(0.5, texture2D(board, (gl_FragCoord.xy + vec2(x, y)) / resolution).r);
}

void main(void) {
	float neighbours =
		alive(-1.0, -1.0) + alive(0.0, -1.0) + alive(1.0, -1.0) +
		alive(-1.0, 0.0) + alive(1.0, 0.0) +
		alive(-1.0, 1.0) + alive(0.0, 1.0) + alive(1.0, 1.0);
	float was = alive(0.0, 0.0);

	// The rule: born with 3 neighbours, survives with 2 or 3.
	float is = max(step(abs(neighbours - 3.0), 0.5),
		was * step(abs(neighbours - 2.0), 0.5));

	// A finger brings every cell near it to life.
	for (int i = 0; i < 10; ++i) {
		if (i >= pointerCount) {
			break;
		}
		is = max(is, step(distance(pointers[i].xy, gl_FragCoord.xy), 3.0));
	}

	// Red: alive. Green: a trail that fades after a cell dies.
	float trail = max(is, 0.92 * texture2D(board, gl_FragCoord.xy / resolution).g);
	gl_FragColor = vec4(is, trail, 0.0, 1.0);
}

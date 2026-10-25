precision mediump float;

#pragma se input "passes/state.glsl"
uniform sampler2D state;

#include "include/palette.glsl"

uniform vec2 resolution;

void main() {
	vec2 uv = gl_FragCoord.xy / resolution;
	float heat = texture2D(state, uv).r;
	gl_FragColor = vec4(palette(heat), 1.0);
}

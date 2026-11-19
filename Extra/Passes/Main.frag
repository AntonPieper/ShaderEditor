#version 300 es
precision highp float;

#pragma se input "Pass.frag"
uniform sampler2D Pass;
uniform vec2 resolution;
out vec4 fragColor;

// Set TINT to 0 to see the pass as it is.
#define TINT 1

void main() {
	vec2 st = gl_FragCoord.xy / resolution;
	vec3 col = texture(Pass, st).rgb;
#if TINT
	col *= vec3(1.0, 0.6, 0.3);
#else
	col *= 1.0;
#endif
	fragColor = vec4(col, 1.0);
}

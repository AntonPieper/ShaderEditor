#version 300 es
#define SHADER_EDITOR 1
precision highp float;
precision highp int;
precision highp sampler2D;
precision highp sampler3D;
precision highp samplerCube;

// Image of "Trail" by the tests, imported from ShaderToy.
// README.md says what the import did.

// iChannel0: Buffer A, as drawn in this frame.

uniform sampler2D iChannel0;

// ShaderToy's inputs, filled from the app's in main().
uniform vec2 resolution;

vec3 iResolution;

void shaderToyInputs() {
	iResolution = vec3(resolution, 1.0);
}

out vec4 shaderToyColor;

void mainImage(out vec4 c, in vec2 f);

void main() {
	shaderToyInputs();
	vec4 color = vec4(1e20));
	mainImage(color, gl_FragCoord.xy);
	shaderToyColor = vec4(color.rgb, 1.0);
}

#define TAU 6.2831853

// 1 inside a soft disc of radius r around c.
float disc(vec2 p, vec2 c, float r) {
	return smoothstep(r, 0.5 * r, distance(p, c));
}

vec3 hue(float t) {
	return 0.5 + 0.5 * cos(TAU * (t + vec3(0.0, 0.33, 0.67)));
}

// Shows Buffer A, with what is brighter than 1 brought back into range.
void mainImage(out vec4 fragColor, in vec2 fragCoord) {
	vec3 color = texture(iChannel0, fragCoord / iResolution.xy).rgb;
	fragColor = vec4(1.0 - exp(-color), 1.0);
}

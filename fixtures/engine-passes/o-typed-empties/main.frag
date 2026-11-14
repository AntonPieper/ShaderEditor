#version 300 es
precision highp float;
precision highp int;

uniform highp usampler2D unsignedFlat;
uniform highp isampler3D signedVolume;
uniform highp sampler2DArray floatArray;
uniform highp sampler2DShadow shadow;
uniform vec2 resolution;
out vec4 color;

// Four samplers nothing provides, one per quadrant: green where it reads
// zero (opaque black for the float array), red where it does not.
// Bottom left usampler2D, bottom right isampler3D, top left
// sampler2DArray, top right sampler2DShadow.
void main(void) {
	vec2 uv = gl_FragCoord.xy / resolution;
	bool zero;
	if (uv.y < 0.5) {
		zero = uv.x < 0.5
				? texture(unsignedFlat, vec2(0.5)) == uvec4(0u)
				: texture(signedVolume, vec3(0.5)) == ivec4(0);
	} else {
		zero = uv.x < 0.5
				? texture(floatArray, vec3(0.5, 0.5, 0.0)).rgb == vec3(0.0)
				: texture(shadow, vec3(0.5, 0.5, 0.5)) == 0.0;
	}
	color = zero ? vec4(0.0, 0.8, 0.0, 1.0) : vec4(0.9, 0.0, 0.0, 1.0);
}

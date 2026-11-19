#version 300 es
precision highp float;
precision highp int;
precision highp sampler2D;
precision highp sampler3D;
precision highp samplerCube;

// In the form the ShaderToy import writes, made for this folder.

// ShaderToy's inputs, filled from the app's in main().
uniform vec2 resolution;
uniform float time;

vec3 iResolution;
float iTime;

void shaderToyInputs() {
	iResolution = vec3(resolution, 1.0);
	iTime = time;
}

out vec4 shaderToyColor;

void mainImage(out vec4 c, in vec2 f);

void main() {
	shaderToyInputs();
	vec4 color = vec4(1e20);
	mainImage(color, gl_FragCoord.xy);
	shaderToyColor = vec4(color.rgb, 1.0);
}

// The usual shorthands of ShaderToy code.
#define R iResolution.xy
#define S(a, b, t) smoothstep(a, b, t)
#define rot(a) mat2(cos(a), -sin(a), sin(a), cos(a))

void mainImage(out vec4 fragColor, in vec2 fragCoord) {
	vec2 uv = (fragCoord - 0.5 * R) / R.y;
	uv *= rot(iTime * 0.3);
	float d = length(max(abs(uv) - 0.2, 0.0)) - 0.02;
	vec3 col = mix(vec3(0.95, 0.85, 0.7), vec3(0.2, 0.3, 0.5),
		S(0.0, 0.01, d));
	col *= 1.0 - 0.3 * S(0.3, 0.9, length(uv));
	fragColor = vec4(col, 1.0);
}

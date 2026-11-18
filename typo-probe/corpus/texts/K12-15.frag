#version 300 es
#define SHADER_EDITOR 1
precision highp float;
precision highp int;
precision highp sampler2D;
precision highp sampler3D;
precision highp samplerCube;

// Buffer C of "Media: all of it?" by the tests, imported from ShaderToy.
// README.md says what the import did.


// iChannel0 read Cube A,
// a pass that draws a cubemap, which the app cannot do. It reads black.
uniform samplerCube iChannel0;

// iChannel1 read a texture of ShaderToy (/media/a/wood.jpg),
// which is not part of the shader. It reads black until you attach a picture; the file says how.
// ShaderToy read it upside down; the app reads a picture upright.
// Put a picture into this folder and take the "//" off the next line:
// #pragma se input "channel1.png" filter=nearest
uniform sampler2D iChannel1;

// iChannel2 read something of the kind "hologram",
// which the import does not know. It reads black.
uniform sampler2D iChannel2;

// iChannel3 read a buffer,
// which this shader does not have. It reads black.
uniform sampler2D iChannel3;

// ShaderToy's inputs, filled from the app's in main().
uniform vec2 resolution;

vec3 iResolution;


out vec4 shaderToyColor;

void mainImage(out vec4 c, in vec2 f);

void main() {
	shaderToyInputs();
	vec4 color = vec4(1e20);
	mainImage(color, gl_FragCoord.xy);
	shaderToyColor = color;
}

void shaderToyInputs() {
	iResolution = vec3(resolution, 1.0);
}

// Reads Cube A, a texture upside down, something unknown and a buffer
// that the shader does not have.
void mainImage(out vec4 fragColor, in vec2 fragCoord) {
	vec2 uv = fragCoord / iResolution.xy;
	vec3 color = texture(iChannel0, vec3(uv, 1.0)).rgb;
	color += texture(iChannel1, uv).rgb + texture(iChannel2, uv).rgb;
	color += texture(iChannel3, uv).rgb;
	fragColor = vec4(color, 1.0);
}

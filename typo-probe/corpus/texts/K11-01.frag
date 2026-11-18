#version 300 es
#define SHADER_EDITOR 1
precision highp float;
precision highp int;
precision highp sampler2D;
precision highp sampler3D;
precision highp samplerCube;

// Image of "Media: all of it?" by the tests, imported from ShaderToy.
// README.md says what the import did.

// iChannel0 read a texture of ShaderToy (/media/a/stone.jpg),
// which is not part of the shader. It reads black until you attach a picture; the file says how.
// Put a picture into this folder and take the "//" off the next line:
// #pragma se input "channel0.png" mipmap=linear wrap=repeat
uniform sampler2D iChannel0;

// iChannel1 read a cubemap of ShaderToy (/media/a/sky.jpg),
// which is not part of the shader. It reads black until you attach a picture; the file says how.
// A cubemap is one picture of six squares side by side: +X, -X, +Y, -Y, +Z, -Z.
// Put a picture into this folder and take the "//" off the next line:
// #pragma se input "channel1.png"
uniform samplerCube iChannel1;

// iChannel2 read a volume texture of ShaderToy (/media/a/noise.bin),
// which the app cannot load. It reads black.
uniform sampler3D iChannel2;

// iChannel3: Buffer A, as drawn in this frame.
#pragma se input "bufferA.frag" filter=linear
uniform sampler2D iChannel3;

// ShaderToy's inputs, filled from the app's in main().
uniform vec2 resolution;
uniform float time;
uniform float timeDelta;
uniform float frameRate;

vec3 iResolution;
float iTime;
float iTimeDelta;
float iFrameRate;

void shaderToyInputs() {
	iResolution = vec3(resolution, 1.0);
	iTime = time;
	iTimeDelta = timeDelta;
	iFrameRate = frameRate;
}

out vec4 shaderToyColor;

void mainImage(out vec4 c, in vec2 f);

void main() {
	shaderToyInputs();
	vec4 color = vec4(1e20);
	mainImage(color, gl_FragCoord.xy);
	shaderToyColor = vec4(color.rgb, 1.0);
}

// ShaderToy's mark of a slow device.
#define HW_PERFORMANCE 0

// Reads a texture, a cubemap and a volume of ShaderToy, and Buffer A.
void mainImage(out vec4 fragColor, in vec2 fragCoord) {
	vec2 uv = fragCoord / iResolution.xy;
	vec3 color = texture(iChannel0, uv).rgb;
	color += texture(iChannel1, vec3(uv, 1.0)).rgb;
	color += texture(iChannel2, vec3(uv, fract(0.1 * iTime))).rgb;
	color += texture(iChannel3, uv).rgb;
#if HW_PERFORMANCE == 0
	color *= 0.9;

	fragColor = vec4(color * iTimeDelta * iFrameRate, 1.0);
}

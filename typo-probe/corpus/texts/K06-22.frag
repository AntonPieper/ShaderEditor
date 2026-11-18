#version 300 es
#define SHADER_EDITOR 1
precision highp float;
precision highp int;
precision highp sampler2D;
precision highp sampler3D;
precision highp samplerCube;

// Buffer B of "Media: all of it?" by the tests, imported from ShaderToy.
// README.md says what the import did.


// iChannel0 read music (/media/a/tune.mp3),
// which the app does not turn into a picture. It reads black.
uniform sampler2D iChannel0;

// iChannel1 read music (https://soundcloud.com/example/tune),
// which the app does not turn into a picture. It reads black.
uniform sampler2D iChannel1;

// iChannel2 read a video (/media/a/clip.webm),
// which the app does not play. It reads black.
uniform sampler2D iChannel2;

// iChannel3: Buffer C, as it was the frame before.

uniform sampler2D iChannel3;

// ShaderToy's inputs, filled from the app's in main().
uniform vec2 resolution;

vec3 iResolution;
float iSampleRate;
float iChannelTime[4];

void shaderToyInputs() {
	iResolution = vec3(resolution, 1.0);
	iSampleRate = 44100.0;
	iChannelTime = float[4](0.0, 0.0, 0.0, 0.0);
}

out vec4 shaderToyColor;

void mainImage(out vec4 c, in vec2 f);

void main() {
	shaderToyInputs();
	vec4 color = vec4(1e20);
	mainImage(color);
	shaderToyColor = color;
}

// Reads music, a music stream, a video and Buffer C.
void mainImage(out vec4 fragColor, in vec2 fragCoord) {
	vec2 uv = fragCoord / iResolution.xy;
	float bass = texture(iChannel0, vec2(0.05, 0.25)).x +
		texture(iChannel1, vec2(0.05, 0.25)).x;
	vec3 color = texture(iChannel2, uv).rgb + texture(iChannel3, uv).rgb;
	fragColor = vec4(color * bass, iChannelTime[2] / iSampleRate);
}

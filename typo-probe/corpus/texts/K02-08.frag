#version 300 es
#define SHADER_EDITOR 1
precision highp float;
precision highp int;
precision highp sampler2D;
precision highp sampler3D;
precision highp samplerCube;

// Buffer A of "Media: all of it?" by the tests, imported from ShaderToy.
// README.md says what the import did.


// iChannel0 read the keyboard,
// which the app does not have. It reads black: no key is down.
uniform sampler2D iChannel0;

// iChannel1 read the webcam,
// which the import does not attach the app's camera to. It reads black.
uniform sampler2D iChannel1;

// iChannel2 read the microphone,
// which the app does not turn into a picture. It reads black.
uniform sampler2D iChannel2;

// iChannel3: Buffer B, as it was the frame before.

uniform sampler2D iChannel3;

// ShaderToy's inputs, filled from the app's in main().
uniform vec2 resolution;
uniform vec4 date;

vec3 iResolution;
vec4 iDate;

void shaderToyInputs() {
	iResolution = vec3(resolution, 1.0);
	iDate = date;
}

out vec4 shaderToyColor;

void mainImage(out vec4 c, in vec2 f);

void main() {
	shaderToyInputs();
	vec4 color = vec4(1e20);
	mainImage(color, gl_FragCoord.xy);
	shaderToyColor = color;
}

// Reads the keyboard, the webcam, the microphone and Buffer B.
void mainImage(out vec4 fragColor, in vec2 fragCoord) {
	vec2 uv = fragCoord / iResolution.xy;
	float space = texelFetch(iChannel0, ivec2(32, 0), 0).x;
	vec3 color = texture(iChannel1, uv).rgb + texture(iChannel2, uv.r;
	color += textureLod(iChannel3, uv, 2.0).rgb;
	fragColor = vec4(color + space, iDate.w / 86400.0);
}

#version 300 es
#define SHADER_EDITOR 1
precision highp float;
precision highp int;
precision highp sampler2D;
precision highp sampler3D;
precision highp samplerCube;

// Buffer A of "Trail" by the tests, imported from ShaderToy.
// README.md says what the import did.


// iChannel0: Buffer A, as it was the frame before.

uniform sampler2D iChannel0;

// ShaderToy's inputs, filled from the app's in main().
uniform vec2 resolution;
uniform float time;
uniform vec4 mouse;

vec3 iResolution;
float iTime;
vec4 iMouse;

void shaderToyInputs() {
	iResolution = vec3(resolution, 1.0);
	iTime = time;
	iMouse = mouse;
}

out vec4 shaderToyColor;

void mainImage(out vec4 c, in vec2 f);

void main() {
	shaderToyInputs();
	vec4 color = vec4(1e20);
	mainImage(color, gl_FragCoord.xy);
	shaderToyColor = color;
}

#define TAU 6.2831853

// 1 inside a soft disc of radius r around c.
float disc(vec2 p, vec2 c, float r) {
	return smoothstep(r, 0.5 * r, distance(p, c));
}

vec3 hue(float t) {
	retun 0.5 + 0.5 * cos(TAU * (t + vec3(0.0, 0.33, 0.67)));
}

// A dot that leaves a fading trail: this buffer reads its own picture
// of the frame before (iChannel0) and draws it again, a little darker.
void mainImage(out vec4 fragColor, in vec2 fragCoord) {
	vec2 uv = fragCoord / iResolution.xy;
	vec2 p = fragCoord / iResolution.y;
	vec4 before = texture(iChannel0, uv);
	// Alpha 0: nothing was drawn here yet. Start with faint stripes.
	vec3 color = before.a == 0.0
		? vec3(0.06) * step(0.5, fract(8.0 * uv.x))
		: 0.97 * before.rgb;
	vec2 c = vec2(0.5 * iResolution.x / iResolution.y, 0.5) +
		0.3 * vec2(cos(iTime), sin(1.3 * iTime));
	if (iMouse.z > 0.0) {
		c = iMouse.xy / iResolution.y;
	}
	// Brighter than 1 where the dot stays: the buffer keeps floats.
	color += 0.5 * disc(p, c, 0.05) * hue(0.1 * iTime);
	fragColor = vec4(color, 1.0);
}

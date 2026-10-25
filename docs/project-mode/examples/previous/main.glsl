precision mediump float;

#pragma se input "passes/state.glsl"
uniform sampler2D nowState;

#pragma se input "passes/state.glsl" frame=previous
uniform sampler2D oldState;

uniform vec2 resolution;

void main() {
	vec2 uv = gl_FragCoord.xy / resolution;
	float nowValue = texture2D(nowState, uv).r;
	float oldValue = texture2D(oldState, uv).r;
	gl_FragColor = vec4(nowValue, abs(nowValue - oldValue) * 8.0, oldValue, 1.0);
}

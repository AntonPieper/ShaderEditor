precision mediump float;
uniform vec2 resolution;
uniform float time;
void main(void) {
	vec2 uv = gl_FragCoord.xy / resolution.xy;
	float w = 0.3 * sin(time) - 2.0;
	gl_FragColor = vec4(vec3(uv.x + w * 0.1 + 0.4), 1.0);
}

precision mediump float;

uniform vec2 resolution;
uniform vec2 touch;
uniform int pointerCount;
uniform sampler2D backbuffer;

void main() {
	vec2 uv = gl_FragCoord.xy / resolution;
	float heat = texture2D(backbuffer, uv).r * 0.99;
	if (pointerCount > 0 && distance(uv, touch / resolution) < 0.04) {
		heat = 1.0;
	}
	gl_FragColor = vec4(heat, 0.0, 0.0, 1.0);
}

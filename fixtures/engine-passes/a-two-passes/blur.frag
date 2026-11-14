#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif

uniform vec2 resolution;
uniform float time;
uniform int frame;

// A soft-edged disc gliding left and right, and in the top tenth the
// frame number in red, modulo 256.
void main(void) {
	vec2 uv = gl_FragCoord.xy / resolution;
	if (uv.y > 0.9) {
		gl_FragColor = vec4(mod(float(frame), 256.0) / 255.0, 0.0, 0.0, 1.0);
		return;
	}
	vec2 center = vec2(0.5 + 0.35 * sin(time), 0.45) * resolution;
	float radius = 0.15 * min(resolution.x, resolution.y);
	float d = distance(gl_FragCoord.xy, center);
	float soft = 1.0 - smoothstep(radius * 0.4, radius * 1.4, d);
	gl_FragColor = vec4(vec3(soft), 1.0);
}

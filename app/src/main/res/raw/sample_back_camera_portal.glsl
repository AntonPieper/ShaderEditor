#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif

uniform vec2 resolution;
uniform float time;
uniform int pointerCount;
uniform vec2 touch;
uniform vec2 cameraAddent;
uniform mat2 cameraOrientation;
uniform samplerExternalOES cameraBack;

vec3 cam(vec2 uv) {
	return texture2D(
		cameraBack,
		cameraAddent + clamp(uv, 0.0, 1.0) * cameraOrientation).rgb;
}

void main(void) {
	vec2 uv = gl_FragCoord.xy / resolution.xy;
	vec2 center = mix(
		vec2(0.5),
		touch / resolution,
		step(0.5, float(pointerCount)));
	vec2 p = uv - center;
	p.x *= resolution.x / resolution.y;

	float r = length(p);
	float a = atan(p.y, p.x);
	float twist = (0.25 + 0.15 * sin(time * 0.7)) * exp(-3.5 * r);
	float ring = sin(28.0 * r - time * 5.0);
	float bend = twist * ring;

	vec2 warped = center + vec2(cos(a + bend), sin(a + bend)) * pow(r, 0.82);
	vec2 edge = 2.0 / resolution.xy;

	vec3 col;
	col.r = cam(warped + edge * 1.5).r;
	col.g = cam(warped).g;
	col.b = cam(warped - edge * 1.5).b;

	float halo = exp(-60.0 * abs(r - 0.28 - 0.03 * sin(time * 2.0)));
	float core = exp(-18.0 * r * r);
	col += vec3(0.2, 0.8, 1.4) * halo;
	col = mix(
		cam(uv),
		col,
		clamp(1.0 - smoothstep(0.05, 0.6, r) + core * 0.25, 0.0, 1.0));
	col += vec3(1.2, 0.6, 1.8) * core * 0.08;

	gl_FragColor = vec4(clamp(col, 0.0, 1.0), 1.0);
}

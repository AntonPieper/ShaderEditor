#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif

uniform vec2 resolution; // size of the surface in pixels
uniform mat3 rotationMatrix; // screen to world (x east, y north, z up); 0 without sensors

float hash(vec2 p) {
	return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

// Stars on a grid over the faces of a cube around the viewer:
// a share `density` of the cells holds one star, about 3 pixels wide.
float stars(vec3 dir, float cells, float density, float px) {
	vec3 a = abs(dir);
	float m = max(a.x, max(a.y, a.z));
	vec2 uv = (a.x == m ? dir.yz : a.y == m ? dir.zx : dir.xy) / m * cells;
	float face = dot(step(m, a) * sign(dir), vec3(1.0, 2.0, 3.0));
	vec2 cell = floor(uv) + 97.0 * face;
	vec2 at = floor(uv) + 0.25 + 0.5 * vec2(hash(cell + 0.3), hash(cell + 0.7));
	float d = length(uv - at) / (cells * px);
	return smoothstep(2.5, 0.0, d) * step(1.0 - density, hash(cell)) * (0.3 + 0.7 * hash(cell + 0.9));
}

void main(void) {
	float unit = min(resolution.x, resolution.y);
	vec2 p = (gl_FragCoord.xy - 0.5 * resolution) / unit;
	float px = 1.0 / unit;

	// Read. Without sensors, look north with the phone held upright.
	mat3 view = dot(rotationMatrix[2], rotationMatrix[2]) > 0.5 ? rotationMatrix
		: mat3(1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, -1.0, 0.0);

	// Shape. On screen, x is right, y up and z out of the glass, so a
	// look through the phone is along -z. The matrix turns that ray into
	// the world, so the sky stays put as you turn.
	vec3 dir = view * normalize(vec3(p, -1.0));
	float up = dir.z;
	float bearing = atan(dir.x, dir.y) / 6.2831853;

	// Draw. Sky above the horizon, ground below, a glow along the horizon.
	vec3 color = mix(vec3(0.05, 0.12, 0.25), vec3(0.01, 0.02, 0.07), sqrt(max(up, 0.0)));
	color = mix(vec3(0.04, 0.04, 0.06), color, smoothstep(-2.0 * px, 0.0, up));
	color += vec3(0.25, 0.2, 0.3) * exp(-abs(up) * (up > 0.0 ? 10.0 : 40.0));
	float glints = stars(dir, 25.0, 0.15, px) + 0.5 * stars(dir, 60.0, 0.06, px);
	color += vec3(0.9, 0.95, 1.0) * glints * smoothstep(0.0, 0.08, up);

	// Landmarks: a tick every 15 degrees on the horizon, red at north,
	// and a moon high in the south-east.
	float tick = abs(fract(bearing * 24.0 + 0.5) - 0.5) / 24.0 * 6.2831853;
	float north = step(abs(fract(bearing + 0.5) - 0.5), 0.01);
	float mark = smoothstep(1.5 * px, 0.0, tick) * step(abs(up), 0.02 + 0.03 * north);
	color = mix(color, mix(vec3(0.6), vec3(1.0, 0.3, 0.25), north), mark);
	float moon = distance(dir, normalize(vec3(0.6, -0.6, 0.55)));
	color += vec3(1.0, 0.95, 0.8) * (smoothstep(0.045, 0.04, moon) + 0.15 * exp(-8.0 * moon));

	gl_FragColor = vec4(color, 1.0);
}

#version 300 es

#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
// 3.00: integers are real 32 bit values at highp, and there is uint.
precision highp int;
#else
precision mediump float;
#endif

uniform vec2 resolution; // size of the surface in pixels
uniform float time; // seconds since load
uniform sampler2D noise; // the bundled grey noise image, 256 x 256

// 3.00: the output is a variable you declare; gl_FragColor is gone.
out vec4 fragColor;

// 3.00: unsigned multiply wraps around exactly, and >>, ^ and & work on
// integers. Multiplying spreads low bits up, shifting brings high bits
// down; three rounds mix every input bit into every output bit.
uint hash(uvec3 v) {
	uint h = v.x * 0x2c1b3c6du + v.y * 0x297a2d39u + v.z * 0x9e3779b1u;
	h ^= h >> 15u;
	h *= 0x68e31da5u;
	h ^= h >> 13u;
	h *= 0xb5297a4du;
	return h ^ (h >> 16u);
}

// Distance to the two quarter circles of a tile, around opposite corners.
float arcs(vec2 q) {
	return min(abs(length(q - 0.5) - 0.5), abs(length(q + 0.5) - 0.5));
}

void main() {
	float size = 0.09 * min(resolution.x, resolution.y);
	vec2 f = gl_FragCoord.xy / size;
	uvec2 cell = uvec2(f);
	vec2 q = fract(f) - 0.5;
	float px = 1.0 / size;

	// Every 4 seconds each tile draws its turn anew from the hash of its
	// cell and the period; half of them change. A tile that changes turns
	// by a quarter, starting at a moment its hash also picks.
	float period = time / 4.0;
	uint now = uint(period);
	uint h = hash(uvec3(cell, now));
	uint before = hash(uvec3(cell, now - 1u)) & 1u;
	float start = float((h >> 1u) & 255u) / 255.0 * 0.6;
	float turn = (h & 1u) == before ? 0.0 : smoothstep(start, start + 0.3, fract(period));
	float a = 1.5707963 * (float(before) + turn);
	q = mat2(cos(a), sin(a), -sin(a), cos(a)) * q;

	// 3.00: textureSize() reads the image's size in texels, and texelFetch()
	// reads one texel by integer index, without filtering. Each tile gets
	// one texel, with the cell index wrapped to the image's size.
	ivec2 texels = textureSize(noise, 0);
	float shade = texelFetch(noise, ivec2(cell) % texels, 0).r;

	// Draw: a tile shaded by its texel, then the paths, coloured across
	// the screen so that joined arcs keep the same colour.
	vec3 color = mix(vec3(0.05, 0.06, 0.1), vec3(0.11, 0.12, 0.2), shade);
	vec2 uv = gl_FragCoord.xy / resolution;
	vec3 path = mix(vec3(1.0, 0.55, 0.3), vec3(0.4, 0.75, 1.0), uv.x * 0.6 + uv.y * 0.4);
	float d = arcs(q);
	color += 0.25 * path * exp(-d / 0.06);
	color = mix(color, path, smoothstep(px, 0.0, d - 0.06));

	fragColor = vec4(color, 1.0);
}

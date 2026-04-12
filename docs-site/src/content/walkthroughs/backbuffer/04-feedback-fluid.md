---
group: "backbuffer"
order: 4
name: "Feedback Fluid"
desc: "A touch-driven fluid illusion steered by the backbuffer, touch history, and motion sensors."
code: |-
  #ifdef GL_FRAGMENT_PRECISION_HIGH
  precision highp float;
  #else
  precision mediump float;
  #endif
  
  #define FTIME_PERIOD 10.0
  
  uniform vec2 resolution;
  uniform float time;
  uniform float ftime;
  uniform float startRandom;
  uniform int frame;
  uniform int pointerCount;
  uniform vec3 pointers[10];
  uniform vec2 touch;
  uniform vec2 touchStart;
  uniform vec2 mouse;
  uniform vec3 gravity;
  uniform vec3 linear;
  uniform sampler2D backbuffer;///min:l;mag:l;
  
  float hash(vec2 p) {
  	return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453123);
  }
  
  vec3 palette(float t) {
  	return 0.45 + 0.55 * cos(6.2831853 * (vec3(0.0, 0.18, 0.36) + t));
  }
  
  vec3 seedField(vec2 uv) {
  	vec2 p = uv * 6.0 + startRandom * 4.0;
  	float n = hash(floor(p * 32.0) + vec2(11.0, 17.0));
  	float m = hash(floor(p.yx * 28.0) + vec2(23.0, 29.0));
  	return palette(n * 0.6 + m * 0.3 + uv.x * 0.15 + uv.y * 0.08) *
  		(0.3 + 0.7 * n);
  }
  
  vec3 sampleBack(vec2 uv) {
  	return texture2D(backbuffer, fract(uv)).rgb;
  }
  
  void main(void) {
  	vec2 uv = gl_FragCoord.xy / resolution.xy;
  	vec2 px = 1.0 / resolution.xy;
  	float touching = step(0.5, float(pointerCount));
  
  	float l = dot(sampleBack(uv - vec2(px.x, 0.0)), vec3(0.3333));
  	float r = dot(sampleBack(uv + vec2(px.x, 0.0)), vec3(0.3333));
  	float b = dot(sampleBack(uv - vec2(0.0, px.y)), vec3(0.3333));
  	float t = dot(sampleBack(uv + vec2(0.0, px.y)), vec3(0.3333));
  
  	vec2 sensorDrift = vec2(
  		gravity.x + linear.x * 1.8,
  		-gravity.y + linear.y * 1.8) * 0.0012;
  	vec2 timeDrift = 0.0025 * vec2(
  		sin(time * 0.6 + uv.y * 9.0 + startRandom * 6.2831),
  		cos(time * 0.5 + uv.x * 8.0 - startRandom * 6.2831));
  	vec2 curl = vec2(t - b, l - r);
  
  	vec2 anchor = mix(
  		vec2(0.5),
  		mix(touchStart / resolution, mouse, 0.5),
  		touching);
  	vec2 rel = uv - anchor;
  	vec2 swirl = vec2(-rel.y, rel.x) *
  		(0.003 + 0.007 * abs(ftime)) /
  		(0.1 + dot(rel, rel) * 22.0);
  
  	vec2 flow = sensorDrift + timeDrift + curl * 0.45 + swirl;
  	vec3 advected = sampleBack(uv - flow);
  	vec3 diffused = (
  		sampleBack(uv + vec2(px.x, 0.0)) +
  		sampleBack(uv - vec2(px.x, 0.0)) +
  		sampleBack(uv + vec2(0.0, px.y)) +
  		sampleBack(uv - vec2(0.0, px.y))) * 0.25;
  
  	vec3 ambient = 0.16 * palette(
  		uv.x * 0.35 + uv.y * 0.12 + startRandom + time * 0.03);
  	vec3 color = mix(advected, diffused, 0.06);
  	color *= 0.996;
  	color += ambient * 0.022;
  	color += 0.006 * palette(
  		hash(floor((uv + time * 0.015) * resolution * 0.16) +
  			vec2(31.0, 47.0)) +
  		uv.y * 0.12);
  
  	if (frame < 18) {
  		vec3 start = seedField(uv);
  		color = mix(
  			start,
  			color,
  			smoothstep(0.0, 18.0, float(frame)));
  	}
  
  	for (int i = 0; i < pointerCount; ++i) {
  		vec2 p = pointers[i].xy / resolution;
  		float radius = 0.03 +
  			pointers[i].z / max(resolution.x, resolution.y) * 10.0;
  		vec2 d = uv - p;
  		float splash = exp(-dot(d, d) / (radius * radius + 0.0006));
  		float ribbon = exp(-75.0 *
  			abs(length(d) - (0.06 + 0.035 * abs(ftime))));
  
  		vec3 ink = palette(
  			time * 0.12 +
  			float(i) * 0.17 +
  			mouse.x * 0.3 +
  			mouse.y * 0.2 +
  			startRandom * 0.4);
  
  		color += ink * splash * (0.16 + 0.38 * abs(ftime));
  		color += ink.bgr * ribbon * 0.08;
  	}
  
  	vec2 drag = (touch - touchStart) / max(resolution.x, resolution.y);
  	color += palette(drag.x * 0.5 + drag.y * 0.4 + time * 0.08) *
  		exp(-26.0 * length(uv - touch / resolution)) *
  		(0.22 + 0.45 * length(drag)) *
  		touching;
  
  	float vignette = exp(-5.0 * dot(uv - 0.5, uv - 0.5));
  	color += ambient * vignette * 0.05;
  	color = max(color, ambient * 0.14);
  
  	gl_FragColor = vec4(clamp(color, 0.0, 1.0), 1.0);
  }
---
`backbuffer` turns this into a persistent simulation. Each frame samples the previous frame, nudges it through a velocity field, blurs it slightly, then injects brighter dye where touches occur. That feedback loop is what makes it feel fluid instead of like a one-frame particle effect.

There are four motion sources layered together: backbuffer curl, time-based drift, `gravity` + `linear` device motion, and a vortex around an anchor derived from `touchStart` and `mouse`. `mouse` is the first touch normalized to 0–1, while `touch` and `touchStart` stay in pixels. `pointers[i].z` is the touch major size, so a wider fingertip paints a wider plume.

`frame` and `startRandom` only matter at startup. They seed the simulation for the first few frames so the shader begins with visible structure instead of fading in from black. `ftime` stays bounded between `-1.0` and `+1.0`, which makes it good for oscillating ring and vortex timing without long-run float precision drift.

**Uniforms used:** `backbuffer`, `time`, `ftime`, `startRandom`, `frame`, `pointerCount`, `pointers[10]`, `touch`, `touchStart`, `mouse`, `gravity`, `linear`, `resolution`.

**Try this:** Increase `curl * 0.45` for more turbulence, or lower `color *= 0.996` toward `0.99` for faster dissipation and shorter-lived trails.

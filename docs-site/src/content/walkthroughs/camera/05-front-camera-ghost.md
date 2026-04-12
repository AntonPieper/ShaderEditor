---
group: "camera"
order: 5
name: "Front Camera Ghost"
desc: "A spectral front-camera mirror that reacts to ambient light, proximity, and system dark mode."
code: |-
  #ifdef GL_FRAGMENT_PRECISION_HIGH
  precision highp float;
  #else
  precision mediump float;
  #endif
  
  uniform vec2 resolution;
  uniform float time;
  uniform float light;
  uniform float proximity;
  uniform int nightMode;
  uniform vec2 cameraAddent;
  uniform mat2 cameraOrientation;
  uniform samplerExternalOES cameraFront;
  
  vec3 cam(vec2 uv) {
  	return texture2D(
  		cameraFront,
  		cameraAddent + clamp(uv, 0.0, 1.0) * cameraOrientation).rgb;
  }
  
  void main(void) {
  	vec2 uv = gl_FragCoord.xy / resolution.xy;
  	vec2 p = uv - 0.5;
  	p.x = abs(p.x);
  
  	float lux = light > 0.0
  		? clamp(log(1.0 + light) / log(1024.0), 0.0, 1.0)
  		: 0.3;
  	float prox = 1.0 / (2.0 + max(proximity, 0.0));
  	float wobble = (0.008 + 0.03 * prox) * sin(p.y * 18.0 - time * 2.0);
  	vec2 lens = vec2(p.x + wobble, p.y) * (1.0 - 0.18 * prox) + 0.5;
  	vec2 px = 2.0 / resolution.xy;
  
  	vec3 base = cam(lens);
  	vec3 blur = (
  		cam(lens + vec2(px.x, 0.0)) +
  		cam(lens - vec2(px.x, 0.0)) +
  		cam(lens + vec2(0.0, px.y)) +
  		cam(lens - vec2(0.0, px.y))) * 0.25;
  	vec3 ghost = abs(base - blur);
  	vec3 tint = mix(
  		vec3(0.15, 0.95, 1.25),
  		vec3(1.35, 0.45, 1.05),
  		float(nightMode));
  
  	vec3 col = mix(blur, base, 0.55 + 0.25 * lux);
  	col += ghost * tint * (0.35 + 1.2 * prox);
  
  	float vignette = smoothstep(
  		1.1,
  		0.15,
  		length((uv - 0.5) * vec2(resolution.x / resolution.y, 1.0)));
  	float scan = 0.96 + 0.04 * sin(gl_FragCoord.y * 1.3 + time * 8.0);
  	col *= vignette * scan;
  
  	gl_FragColor = vec4(clamp(col, 0.0, 1.0), 1.0);
  }
---
This is still a mirror shader, but it uses system data to feel more alive. `light` is ambient lux, remapped through `log()` to produce a stable exposure control even when the raw sensor spans orders of magnitude. `nightMode` swaps the spectral tint so the mirror can automatically fit dark-theme setups.

`proximity` bends the lens harder as something gets closer to the sensor. That makes this feel less like a plain camera preview and more like a reactive piece of UI art. If a device has no proximity sensor, the shader still works because the warp remains bounded.

The front camera uses exactly the same coordinate fixup as the back camera: `cameraAddent + uv * cameraOrientation`. Everything else is pure fragment-shader post-processing — a horizontal mirror, a tiny blur, then `abs(base - blur)` to extract a ghostly high-frequency outline.

**Uniforms used:** `cameraFront`, `cameraAddent`, `cameraOrientation`, `light`, `proximity`, `nightMode`, `time`, `resolution`.

**Try this:** Remove `abs(p.x)` to stop mirroring and turn it into a warped selfie feed, or multiply `ghost` harder if you want a much more aggressive edge-bloom look.

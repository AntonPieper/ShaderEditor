precision mediump float;

#pragma se input "assets/paper.png" wrap=repeat
uniform sampler2D paper;

uniform vec2 resolution;

void main() {
	vec2 uv = gl_FragCoord.xy / resolution;
	gl_FragColor = texture2D(paper, uv * 4.0);
}

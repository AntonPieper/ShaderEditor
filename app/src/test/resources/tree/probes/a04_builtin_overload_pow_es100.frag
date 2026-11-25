precision mediump float;
vec3 pow(vec3 v, float e) { return vec3(pow(v.x, e), pow(v.y, e), pow(v.z, e)); }
void main() {
	vec3 c = pow(vec3(0.5), 2.2);
	gl_FragColor = vec4(c, pow(0.5, 2.0));
}

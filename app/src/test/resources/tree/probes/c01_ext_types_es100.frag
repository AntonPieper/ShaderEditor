#extension GL_EXT_shadow_samplers : enable
#extension GL_EXT_shader_texture_lod : enable
precision mediump float;
uniform sampler2DShadow shadow;
uniform sampler2D tex;
void main() {
	gl_FragColor = vec4(shadow2DEXT(shadow, vec3(0.5)), texture2DLodEXT(tex, vec2(0.5), 0.0).rgb);
}

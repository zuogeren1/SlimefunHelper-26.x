#version 330

#ifdef SLF_SHADER_263
#extension GL_ARB_separate_shader_objects : require
#include <minecraft:dynamictransforms.glsl>
#else
layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
    mat4 TextureMat;
};
#endif

uniform sampler2D Sampler0;

#ifdef SLF_SHADER_263
layout(location = 0) in vec4 vertexColor;
layout(location = 1) in vec2 texCoord0;

layout(location = 0) out vec4 fragColor;
#else
in vec4 vertexColor;
in vec2 texCoord0;

out vec4 fragColor;
#endif

void main() {
    vec4 color = texture(Sampler0, texCoord0);
    if (color.a == 0.0) {
        discard;
    }
    fragColor = vec4(ColorModulator.rgb * vertexColor.rgb, vertexColor.a);
}

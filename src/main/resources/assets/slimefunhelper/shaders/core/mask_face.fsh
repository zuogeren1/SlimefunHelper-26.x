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

uniform sampler2D InSampler;

#ifdef SLF_SHADER_263
layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;
#else
in vec2 texCoord;
out vec4 fragColor;
#endif

void main() {
    float alpha = texture(InSampler, texCoord).a;
    if (alpha <= 0.0) discard;
    fragColor = vec4(ColorModulator.rgb, ColorModulator.a * alpha);
}

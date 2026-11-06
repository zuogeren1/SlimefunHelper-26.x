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
    ivec2 size = textureSize(InSampler, 0);
    ivec2 center = ivec2(texCoord * vec2(size));
    if (texelFetch(InSampler, center, 0).a > 0.0) discard;

    float coverage = 0.0;
    for (int y = -2; y <= 2; y++) {
        for (int x = -2; x <= 2; x++) {
            ivec2 samplePos = center + ivec2(x, y);
            if (any(lessThan(samplePos, ivec2(0))) || any(greaterThanEqual(samplePos, size))) continue;
            float alpha = texelFetch(InSampler, samplePos, 0).a;
            float distance = length(vec2(x, y));
            coverage = max(coverage, alpha * clamp(2.5 - distance, 0.0, 1.0));
        }
    }

    if (coverage <= 0.0) discard;
    fragColor = vec4(ColorModulator.rgb, ColorModulator.a * coverage);
}

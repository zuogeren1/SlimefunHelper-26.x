#version 330 core
// Ported from Carbon (https://github.com/CHUJIAN-afk/Carbon), branch 26.1.2-neoforge @ 22a8a26.
// Copyright (c) 2026 FirstSight. Licensed under the MIT License.
// Modified for SlimeFunHelper+ (namespace and resource path only).

uniform sampler2D Sampler0;
uniform int GlintPass;
uniform float GlintAlpha;
uniform vec4 ColorModulator;
layout(std140) uniform Fog { vec4 FogColor; float FogStart; float FogEnd; float RenderFogStart; float RenderFogEnd; float FogSkyEnd; float FogCloudsEnd; };

in float vertexDistance;
in float horizontalDistance;
in vec4 vertexColor;
in vec4 lightMapColor;
in vec4 overlayColor;
in vec2 texCoord;
out vec4 fragColor;

float fogValue(float distance, float start, float end) {
    return distance <= start ? 0.0 : distance >= end ? 1.0 : (distance - start) / (end - start);
}

void main() {
    vec4 color = texture(Sampler0, texCoord);
    if (color.a < 0.1) {
        discard;
    }
    if (GlintPass != 0) {
        float fade = 1.0 - max(fogValue(vertexDistance, FogStart, FogEnd), fogValue(horizontalDistance, RenderFogStart, RenderFogEnd));
        fragColor = vec4(color.rgb * fade * GlintAlpha, color.a);
        return;
    }
    color *= vertexColor * ColorModulator;
    color.rgb = mix(overlayColor.rgb, color.rgb, overlayColor.a);
    color *= lightMapColor;
    float fog = max(fogValue(vertexDistance, FogStart, FogEnd), fogValue(horizontalDistance, RenderFogStart, RenderFogEnd));
    color.rgb = mix(color.rgb, FogColor.rgb, fog * FogColor.a);
    fragColor = color;
}

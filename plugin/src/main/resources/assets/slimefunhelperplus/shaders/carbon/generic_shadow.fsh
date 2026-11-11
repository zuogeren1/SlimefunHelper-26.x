#version 330 core
// Ported from Carbon (https://github.com/CHUJIAN-afk/Carbon), branch 26.1.2-neoforge @ 22a8a26.
// Copyright (c) 2026 FirstSight. Licensed under the MIT License.
// Modified for SlimeFunHelper+ (namespace and resource path only).
layout(std140) uniform Fog { vec4 FogColor; float FogStart; float FogEnd; float RenderFogStart; float RenderFogEnd; float FogSkyEnd; float FogCloudsEnd; };
uniform sampler2D Sampler0;
in float vertexDistance;
in float horizontalDistance;
in float alpha;
in vec2 texCoord;
out vec4 fragColor;
float fogValue(float distance, float start, float end) {
    return distance <= start ? 0.0 : distance >= end ? 1.0 : (distance - start) / (end - start);
}
void main() {
    vec4 color = texture(Sampler0, clamp(texCoord, 0.0, 1.0)) * vec4(1.0, 1.0, 1.0, alpha);
    float fog = max(fogValue(vertexDistance, FogStart, FogEnd), fogValue(horizontalDistance, RenderFogStart, RenderFogEnd));
    color.rgb = mix(color.rgb, FogColor.rgb, fog * FogColor.a);
    fragColor = color;
}

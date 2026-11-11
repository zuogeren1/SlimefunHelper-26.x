#version 330 core
// Ported from Carbon (https://github.com/CHUJIAN-afk/Carbon), branch 26.1.2-neoforge @ 22a8a26.
// Copyright (c) 2026 FirstSight. Licensed under the MIT License.
// Modified for SlimeFunHelper+ (namespace and resource path only).
uniform sampler2D Sampler0;
uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;
uniform float RenderFogStart;
uniform float RenderFogEnd;
uniform vec4 FogColor;
in vec2 texCoord;
in float alpha;
in float vertexDistance;
in float horizontalDistance;
out vec4 fragColor;
float fogValue(float distance, float start, float end) {
    return distance <= start ? 0.0 : distance >= end ? 1.0 : (distance - start) / (end - start);
}

void main() {
    vec4 color = texture(Sampler0, clamp(texCoord, 0.0, 1.0)) * vec4(1.0, 1.0, 1.0, alpha) * ColorModulator;
    float fog = max(fogValue(vertexDistance, FogStart, FogEnd), fogValue(horizontalDistance, RenderFogStart, RenderFogEnd));
    color.rgb = mix(color.rgb, FogColor.rgb, fog * FogColor.a);
    fragColor = color;
}

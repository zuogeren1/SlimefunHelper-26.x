#version 330 core
// Ported from Carbon (https://github.com/CHUJIAN-afk/Carbon), branch 26.1.2-neoforge @ 22a8a26.
// Copyright (c) 2026 FirstSight. Licensed under the MIT License.
// Modified for SlimeFunHelper+ (namespace and resource path only).
layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in vec2 UV0;
layout(location = 3) in ivec2 UV1;
layout(location = 4) in ivec2 UV2;
layout(location = 5) in vec3 Normal;
layout(location = 6) in vec3 Translation;
layout(location = 7) in vec2 Rotation;
layout(location = 9) in ivec2 Light;
uniform int GlintPass;
uniform mat4 TextureMat;
uniform mat4 View;
uniform mat4 Projection;
uniform vec3 Camera;
uniform int FogShape;
layout(std140) uniform Lighting { vec3 Light0; vec3 Light1; };
uniform sampler2D Sampler1;
uniform sampler2D Sampler2;
out float vertexDistance;
out float horizontalDistance;
out vec4 vertexColor;
out vec4 lightMapColor;
out vec4 overlayColor;
out vec2 texCoord;
void main() {
    float s = Rotation.x, c = Rotation.y;
    mat3 rotation = mat3(c, 0.0, -s, 0.0, 1.0, 0.0, s, 0.0, c);
    vec3 position = rotation * Position + Translation - Camera;
    vec3 normal = normalize(rotation * Normal);
    gl_Position = Projection * View * vec4(position, 1.0);
    vertexDistance = length(position);
    horizontalDistance = max(length(position.xz), abs(position.y));
    float light = UV1.x == 0 ? 1.0 : min(1.0, (max(0.0, dot(Light0, normal)) + max(0.0, dot(Light1, normal))) * 0.6 + 0.4);
    vertexColor = vec4(Color.rgb * light, Color.a);
    lightMapColor = UV1.y == 0 ? vec4(1.0) : texture(Sampler2, clamp(vec2(max(UV2, Light)) / 256.0 + 0.5 / 16.0, vec2(0.5 / 16.0), vec2(15.5 / 16.0)));
    overlayColor = vec4(1.0);
    texCoord = GlintPass == 0 ? UV0 : (TextureMat * vec4(UV0, 0.0, 1.0)).xy;
}

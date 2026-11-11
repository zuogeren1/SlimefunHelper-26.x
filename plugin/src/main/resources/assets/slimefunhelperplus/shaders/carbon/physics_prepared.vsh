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
layout(location = 7) in vec4 Rotation;
layout(location = 9) in ivec2 Light;
uniform mat4 View;
uniform mat4 Projection;
uniform mat4 TextureMat;
uniform int GlintPass;
uniform vec3 Camera;
uniform int FogShape;
uniform int Flat;
layout(std140) uniform Lighting { vec3 Light0; vec3 Light1; };
uniform sampler2D Sampler1;
uniform sampler2D Sampler2;
out float vertexDistance;
out float horizontalDistance;
out vec4 vertexColor;
out vec4 lightMapColor;
out vec4 overlayColor;
out vec2 texCoord;
vec3 rotatePoint(vec4 q, vec3 p) {
    return p + 2.0 * cross(q.xyz, cross(q.xyz, p) + q.w * p);
}
void main() {
    vec3 position;
    vec3 normal;
    if ((Light.y & 32768) == 0) {
        vec3 restingPosition = Flat != 0 ? vec3(Position.x, -Position.z, Position.y) : Position;
        vec3 restingNormal = Flat != 0 ? vec3(Normal.x, -Normal.z, Normal.y) : Normal;
        float s = Rotation.x, c = Rotation.y;
        mat3 rotation = mat3(c, 0.0, -s, 0.0, 1.0, 0.0, s, 0.0, c);
        position = rotation * restingPosition + Translation - Camera;
        normal = normalize(rotation * restingNormal);
    } else {
        position = rotatePoint(Rotation, Position) + Translation - Camera;
        normal = normalize(rotatePoint(Rotation, Normal));
    }
    gl_Position = Projection * View * vec4(position, 1.0);
    vertexDistance = FogShape == 0 ? length(position) : max(length(position.xz), abs(position.y));
    horizontalDistance = max(length(position.xz), abs(position.y));
    float light = UV1.x == 0 ? 1.0 : min(1.0, (max(0.0, dot(Light0, normal)) + max(0.0, dot(Light1, normal))) * 0.6 + 0.4);
    vertexColor = vec4(Color.rgb * light, Color.a);
    lightMapColor = UV1.y == 0 ? vec4(1.0) : texture(Sampler2, clamp(vec2(max(UV2, ivec2(Light.x, Light.y & 32767))) / 256.0 + 0.5 / 16.0, vec2(0.5 / 16.0), vec2(15.5 / 16.0)));
    overlayColor = vec4(1.0);
    texCoord = GlintPass == 0 ? UV0 : (TextureMat * vec4(UV0, 0.0, 1.0)).xy;
}

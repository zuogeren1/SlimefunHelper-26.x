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
layout(location = 7) in vec2 Animation;
layout(location = 8) in vec4 Copy;
layout(location = 9) in ivec2 Light;

uniform mat4 View;
uniform mat4 Projection;
uniform mat4 TextureMat;
uniform int GlintPass;
uniform vec3 Camera;
uniform float Clock;
uniform float GroundScaleY;
uniform vec3 Center;
uniform vec3 HalfSize;
uniform int Flat;
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

vec4 multiplyRotation(vec4 a, vec4 b) {
    return vec4(a.w * b.xyz + b.w * a.xyz + cross(a.xyz, b.xyz), a.w * b.w - dot(a.xyz, b.xyz));
}
vec3 rotatePoint(vec4 q, vec3 p) {
    return p + 2.0 * cross(q.xyz, cross(q.xyz, p) + q.w * p);
}
vec4 physicalRotation(float phase, float roll, float rate) {
    vec4 yaw = vec4(0.0, sin(phase * 0.5), 0.0, cos(phase * 0.5));
    vec4 resting = Flat != 0 ? multiplyRotation(yaw, vec4(0.70710678118, 0.0, 0.0, 0.70710678118)) : yaw;
    float angle = roll + rate * fract(Clock);
    resting = multiplyRotation(resting, vec4(0.0, sin(angle * 0.5), 0.0, cos(angle * 0.5)));
    return normalize(resting);
}
vec3 physicalOffset(vec4 q, vec3 copy) {
    vec3 right = rotatePoint(q, vec3(1.0, 0.0, 0.0));
    vec3 up = rotatePoint(q, vec3(0.0, 1.0, 0.0));
    vec3 forward = rotatePoint(q, vec3(0.0, 0.0, 1.0));
    float height = dot(abs(vec3(right.y, up.y, forward.y)), HalfSize);
    bool pile = copy.z < 0.0;
    vec3 spread = rotatePoint(q, vec3(copy.xy, pile ? copy.z + 1.0 : 0.0));
    spread.y = height + (pile ? abs(spread.y) : copy.z * (height * 2.0 + 0.002)) + 0.002;
    return spread - rotatePoint(q, Center);
}

void main() {
    vec4 rotation = physicalRotation(Animation.y, Animation.x, Copy.w);
    vec3 position = rotatePoint(rotation, Position) + Translation + physicalOffset(rotation, Copy.xyz) - Camera;
    vec3 normal = normalize(rotatePoint(rotation, Normal));
    gl_Position = Projection * View * vec4(position, 1.0);
    vertexDistance = FogShape == 0 ? length(position) : max(length(position.xz), abs(position.y));
    horizontalDistance = max(length(position.xz), abs(position.y));
    float light = UV1.x == 0 ? 1.0 : min(1.0, (max(0.0, dot(Light0, normal)) + max(0.0, dot(Light1, normal))) * 0.6 + 0.4);
    vertexColor = vec4(Color.rgb * light, Color.a);
    lightMapColor = UV1.y == 0 ? vec4(1.0) : texture(Sampler2, clamp(vec2(max(UV2, Light)) / 256.0 + 0.5 / 16.0, vec2(0.5 / 16.0), vec2(15.5 / 16.0)));
    overlayColor = vec4(1.0);
    texCoord = GlintPass == 0 ? UV0 : (TextureMat * vec4(UV0, 0.0, 1.0)).xy;
}

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
layout(location = 6) in mat4 Instance;
layout(location = 10) in vec4 Animation;
layout(location = 11) in vec2 Data;
layout(location = 12) in mat3 InstanceNormal;
layout(location = 15) in vec3 CopyOffset;

uniform int GlintPass;
uniform mat4 TextureMat;
uniform mat4 View;
layout(std140) uniform Projection { mat4 ProjMat; };
#define Projection ProjMat
uniform vec3 Camera;
uniform float Clock;
uniform float GroundScaleY;
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
    vec3 position = Position + CopyOffset;
    vec3 normal = Normal;
    if (Animation.z == 1.0) {
        float age = Clock + Animation.x;
        float angle = age / 20.0 + Animation.y;
        float s = sin(angle);
        float c = cos(angle);
        mat3 rotation = mat3(c, 0.0, -s, 0.0, 1.0, 0.0, s, 0.0, c);
        position = rotation * position;
        normal = rotation * normal;
        position.y += 0.25 * GroundScaleY;
        if (Animation.w != 0.0) {
            position.y += sin(age / 10.0 + Animation.y) * 0.1 + 0.1;
        }
    }
    vec3 relativePosition = (Instance * vec4(position, 1.0)).xyz - Camera;
    normal = normalize(InstanceNormal * normal);
    gl_Position = Projection * View * vec4(relativePosition, 1.0);
    vertexDistance = length(relativePosition);
    horizontalDistance = max(length(relativePosition.xz), abs(relativePosition.y));
    float light = UV1.x == 0 ? 1.0 : min(1.0, (max(0.0, dot(Light0, normal)) + max(0.0, dot(Light1, normal))) * 0.6 + 0.4);
    vertexColor = vec4(Color.rgb * light, Color.a);
    lightMapColor = UV1.y == 0 ? vec4(1.0) : texture(Sampler2, clamp(vec2(max(UV2, ivec2(Data.xy))) / 256.0 + 0.5 / 16.0, vec2(0.5 / 16.0), vec2(15.5 / 16.0)));
    overlayColor = vec4(1.0);
    texCoord = GlintPass == 0 ? UV0 : (TextureMat * vec4(UV0, 0.0, 1.0)).xy;
}

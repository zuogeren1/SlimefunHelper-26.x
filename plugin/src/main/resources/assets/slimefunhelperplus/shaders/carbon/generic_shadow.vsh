#version 330 core
// Ported from Carbon (https://github.com/CHUJIAN-afk/Carbon), branch 26.1.2-neoforge @ 22a8a26.
// Copyright (c) 2026 FirstSight. Licensed under the MIT License.
// Modified for SlimeFunHelper+ (namespace and resource path only).
layout(location = 0) in mat4 Instance;
layout(location = 4) in vec4 Bounds;
layout(location = 5) in vec3 Data;
layout(std140) uniform Projection { mat4 ProjMat; };
uniform mat4 View;
out float vertexDistance;
out float horizontalDistance;
out float alpha;
out vec2 texCoord;
void main() {
    float x = gl_VertexID >= 2 ? Bounds.z : Bounds.x;
    float z = gl_VertexID == 1 || gl_VertexID == 2 ? Bounds.w : Bounds.y;
    vec3 position = (Instance * vec4(x, Data.x, z, 1.0)).xyz;
    gl_Position = ProjMat * View * vec4(position, 1.0);
    vertexDistance = length(position);
    horizontalDistance = max(length(position.xz), abs(position.y));
    alpha = Data.z;
    texCoord = -vec2(x, z) / (2.0 * Data.y) + 0.5;
}

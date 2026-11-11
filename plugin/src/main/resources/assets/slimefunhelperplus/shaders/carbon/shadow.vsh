#version 330 core
// Ported from Carbon (https://github.com/CHUJIAN-afk/Carbon), branch 26.1.2-neoforge @ 22a8a26.
// Copyright (c) 2026 FirstSight. Licensed under the MIT License.
// Modified for SlimeFunHelper+ (namespace and resource path only).
layout(location = 0) in vec4 Bounds;
layout(location = 1) in vec4 Shadow;
layout(location = 2) in vec4 Parameters;
uniform mat4 View;
uniform mat4 Projection;
uniform vec3 Camera;
uniform int FogShape;
out vec2 texCoord;
out float alpha;
out float vertexDistance;
out float horizontalDistance;
void main() {
    float x = (gl_VertexID & 2) == 0 ? Bounds.x : Bounds.w;
    float z = (gl_VertexID & 1) == 0 ? Bounds.z : Shadow.x;
    vec3 relative = vec3(x, Bounds.y, z) - Camera;
    vertexDistance = length(relative);
    horizontalDistance = max(length(relative.xz), abs(relative.y));
    vec4 position = View * vec4(relative, 1.0);
    position.xyz *= 0.99975586;
    gl_Position = Projection * position;
    texCoord = (Shadow.yz - vec2(x, z)) / (2.0 * Parameters.x) + 0.5;
    vec3 itemPosition = vec3(Shadow.y, Parameters.z, Shadow.z) - Camera;
    float distanceSquared = dot(itemPosition, itemPosition);
    float weight = (1.0 - distanceSquared / 256.0) * 0.75;
    alpha = floor(clamp((weight - Parameters.y * 0.5) * 0.5 * Shadow.w, 0.0, 1.0) * 255.0) / 255.0;
    if (distanceSquared >= 256.0 || alpha <= 0.0) gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
}

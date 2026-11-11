#version 330 core
// Ported from Carbon (https://github.com/CHUJIAN-afk/Carbon), branch 26.1.2-neoforge @ 22a8a26.
// Copyright (c) 2026 FirstSight. Licensed under the MIT License.
// Modified for SlimeFunHelper+ (namespace and resource path only).
out vec4 vColor;
void main() {
    vec2 p = vec2((gl_VertexID == 1) ? 0.6 : -0.6, (gl_VertexID == 2) ? 0.6 : -0.6);
    gl_Position = vec4(p, 0.0, 1.0);
    vColor = vec4(0.0, 1.0, 0.0, 1.0);
}

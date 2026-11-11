#version 330 core
// Ported from Carbon (https://github.com/CHUJIAN-afk/Carbon), branch 26.1.2-neoforge @ 22a8a26.
// Copyright (c) 2026 FirstSight. Licensed under the MIT License.
// Modified for SlimeFunHelper+ (namespace and resource path only).
layout(location = 0) in vec3 Position;
out vec3 vTint;
void main() {
    gl_Position = vec4(Position * 0.25 + vec3(0.0, -0.6, 0.0), 1.0);
    vTint = vec3(0.2, 0.4, 1.0);
}

#version 330 core
// Ported from Carbon (https://github.com/CHUJIAN-afk/Carbon), branch 26.1.2-neoforge @ 22a8a26.
// Copyright (c) 2026 FirstSight. Licensed under the MIT License.
// Modified for SlimeFunHelper+ (namespace and resource path only).
layout(location = 0) in vec3 Position;
layout(location = 6) in mat4 Instance;
uniform mat4 View;
uniform mat4 Projection;
void main() {
    gl_Position = Projection * View * Instance * vec4(Position, 1.0);
}

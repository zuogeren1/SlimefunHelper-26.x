#version 330 core
// Ported from Carbon (https://github.com/CHUJIAN-afk/Carbon), branch 26.1.2-neoforge @ 22a8a26.
// Copyright (c) 2026 FirstSight. Licensed under the MIT License.
// Modified for SlimeFunHelper+ (namespace and resource path only).
in vec3 vTint;
out vec4 fragColor;
void main() {
    fragColor = vec4(vTint, 1.0);
}

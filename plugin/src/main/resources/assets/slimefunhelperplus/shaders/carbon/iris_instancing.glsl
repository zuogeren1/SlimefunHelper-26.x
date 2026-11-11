// Ported from Carbon (https://github.com/CHUJIAN-afk/Carbon), branch 26.1.2-neoforge @ 22a8a26.
// Copyright (c) 2026 FirstSight. Licensed under the MIT License.
// Modified for SlimeFunHelper+ (namespace and resource path only).
uniform vec3 carbonCamera;
uniform float carbonClock;
uniform float carbonScale;
uniform vec3 carbonCenter;
uniform vec3 carbonHalfSize;
uniform int carbonFlat;
uint carbon_base;
float carbon_float(uint at) { return uintBitsToFloat(carbon_data[carbon_base + at]); }
vec3 carbon_vector(uint at) { return vec3(carbon_float(at), carbon_float(at + 1u), carbon_float(at + 2u)); }
vec4 carbon_quatMultiply(vec4 a, vec4 b) {
    return vec4(a.w * b.xyz + b.w * a.xyz + cross(a.xyz, b.xyz), a.w * b.w - dot(a.xyz, b.xyz));
}
vec3 carbon_rotate(vec4 q, vec3 p) { return p + 2.0 * cross(q.xyz, cross(q.xyz, p) + q.w * p); }
mat3 carbon_matrix(vec4 q) {
    return mat3(carbon_rotate(q, vec3(1,0,0)), carbon_rotate(q, vec3(0,1,0)), carbon_rotate(q, vec3(0,0,1)));
}
void main() {
    vec3 position;
    mat3 rotation;
    mat3 tangentRotation;
    uint light;
#if CARBON_MODE == 0
    carbon_base = uint(gl_InstanceID) * 36u;
    mat4 instance = mat4(vec4(carbon_vector(0u), carbon_float(3u)), vec4(carbon_vector(4u), carbon_float(7u)),
            vec4(carbon_vector(8u), carbon_float(11u)), vec4(carbon_vector(12u), carbon_float(15u)));
    position = (instance * vec4(iris_Position, 1.0)).xyz;
    rotation = mat3(carbon_vector(22u), carbon_vector(25u), carbon_vector(28u));
    tangentRotation = mat3(instance);
    light = uint(carbon_float(20u)) | (uint(carbon_float(21u)) << 16u);
    carbon_iris_UV1 = iris_UV1;
    carbon_iris_Entity = iris_Entity;
#else
    carbon_base = uint(gl_InstanceID) * 10u;
    light = carbon_data[carbon_base + 3u];
#if CARBON_MODE == 3
    float age = carbonClock + carbon_float(4u);
    float phase = carbon_float(5u);
    float angle = age / 20.0 + phase;
    float s = sin(angle), c = cos(angle);
    rotation = mat3(c,0,-s,0,1,0,s,0,c);
    position = rotation * (iris_Position + carbon_vector(6u)) + carbon_vector(0u) - carbonCamera;
    position.y += 0.25 * carbonScale;
    if (carbon_float(9u) != 0.0) position.y += sin(age / 10.0 + phase) * 0.1 + 0.1;
#else
    float phase = carbon_float(5u);
    vec4 yaw = vec4(0, sin(phase * 0.5), 0, cos(phase * 0.5));
    vec4 q = carbonFlat != 0 ? carbon_quatMultiply(yaw, vec4(0.70710678118,0,0,0.70710678118)) : yaw;
    float rate = carbon_float(9u);
    float angle = carbon_float(4u) + rate * fract(carbonClock);
    q = carbon_quatMultiply(q, vec4(0,sin(angle * 0.5),0,cos(angle * 0.5)));
    rotation = carbon_matrix(normalize(q));
    float height = dot(abs(vec3(rotation[0].y, rotation[1].y, rotation[2].y)), carbonHalfSize);
    vec3 copy = carbon_vector(6u);
    bool pile = copy.z < 0.0;
    vec3 spread = rotation * vec3(copy.xy, pile ? copy.z + 1.0 : 0.0);
    spread.y = height + (pile ? abs(spread.y) : copy.z * (height * 2.0 + 0.002)) + 0.002;
    position = rotation * (iris_Position - carbonCenter) + carbon_vector(0u) + spread - carbonCamera;
#endif
    tangentRotation = rotation;
    carbon_iris_UV1 = iris_UV1;
    carbon_iris_Entity = iris_Entity;
#endif
    carbon_iris_Position = position;
    carbon_iris_Normal = normalize(rotation * iris_Normal);
    carbon_at_tangent = vec4(normalize(tangentRotation * at_tangent.xyz), at_tangent.w * sign(determinant(tangentRotation)));
    carbon_iris_UV2 = CARBON_UV2_TYPE(max(vec2(iris_UV2), vec2(light & 65535u, light >> 16u)));
    carbon_original_main();
}

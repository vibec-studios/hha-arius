#version 330

float arius_hash(vec3 p) {
    p = fract(p * vec3(0.1031, 0.1030, 0.0973));
    p += dot(p, p.yxz + 33.33);
    return fract((p.x + p.y) * p.z);
}

float arius_noise(vec3 x) {
    vec3 i = floor(x);
    vec3 f = fract(x);
    vec3 u = f * f * (3.0 - 2.0 * f);
    return mix(
        mix(mix(arius_hash(i), arius_hash(i + vec3(1, 0, 0)), u.x),
            mix(arius_hash(i + vec3(0, 1, 0)), arius_hash(i + vec3(1, 1, 0)), u.x), u.y),
        mix(mix(arius_hash(i + vec3(0, 0, 1)), arius_hash(i + vec3(1, 0, 1)), u.x),
            mix(arius_hash(i + vec3(0, 1, 1)), arius_hash(i + vec3(1, 1, 1)), u.x), u.y),
        u.z);
}

float arius_fbm(vec3 p) {
    float value = 0.0;
    float amplitude = 0.5;
    for (int i = 0; i < 5; i++) {
        value += amplitude * arius_noise(p);
        p = p * 2.03 + vec3(17.1, 9.2, 4.7);
        amplitude *= 0.5;
    }
    return value;
}

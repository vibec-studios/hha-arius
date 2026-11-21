#version 330

// Filmlinse der Jagd (seit 1.10.0): Farbsaeume (chromatische Aberration, radial), Zoom-Unschaerfe (Bewegung, knapp
// verfehlt), Farbentzug, Vignette und eine Toenung (Frostatem, Blende). Alles ueber die Parameter, 0 = aus.

uniform sampler2D SceneSampler;

layout(std140) uniform HuntLens {
    vec4 Fx;    // x = Farbsaeume, y = Zoom-Unschaerfe, z = Farbentzug, w = Vignette
    vec4 Tint;  // rgb = Farbe, a = Staerke
    vec4 Extra; // x = Zeit (s), y = Verzerrung (Hitzeflimmern/Frost), zw = frei
};

in vec2 texCoord;
flat in mat4 viewProj;
flat in mat4 invViewProj;

out vec4 fragColor;

void main() {
    vec2 uv = texCoord;
    vec2 c = uv - 0.5;
    float r = length(c);
    vec2 dir = r > 1e-4 ? c / r : vec2(0.0);
    // leichtes Wabern (Frost, Brüllen)
    uv += vec2(sin(uv.y * 40.0 + Extra.x * 7.0), cos(uv.x * 36.0 + Extra.x * 6.0)) * 0.0025 * Extra.y * r;
    float ca = Fx.x * 0.014 * r;
    vec3 col;
    col.r = texture(SceneSampler, uv + dir * ca).r;
    col.g = texture(SceneSampler, uv).g;
    col.b = texture(SceneSampler, uv - dir * ca).b;
    if (Fx.y > 0.001) {
        vec3 acc = col;
        float wsum = 1.0;
        for (int i = 1; i < 9; i++) {
            float k = float(i) / 9.0;
            vec3 s = texture(SceneSampler, uv - c * k * Fx.y * 0.09).rgb;
            float w = 1.0 - k;
            acc += s * w;
            wsum += w;
        }
        col = mix(col, acc / wsum, smoothstep(0.05, 0.4, r));
    }
    float l = dot(col, vec3(0.299, 0.587, 0.114));
    col = mix(col, vec3(l) * vec3(0.93, 0.98, 1.06), clamp(Fx.z, 0.0, 1.0));
    float v = smoothstep(0.3, 0.85, r);
    col *= 1.0 - v * clamp(Fx.w, 0.0, 1.0);
    col = mix(col, Tint.rgb, clamp(Tint.a, 0.0, 1.0) * (0.55 + 0.45 * smoothstep(0.1, 0.7, r)));
    fragColor = vec4(col, 1.0);
}

#version 330

// Brechung des Klaren Schnitts: Das Bild weicht zu beiden Seiten der Schnittnaht auseinander, als waere die Luft
// zerschnitten. Naht = Strecke A..B (relativ zur Kamera), Normal = Dicke-Richtung der Schnittebene. Der Versatz ist im
// Bild etwa gleich breit, egal wie weit die Naht entfernt ist; verdeckte Stellen (Szene naeher als die Naht) bleiben ruhig.
// Leichte Farbsaeume und ein haarfeiner Lichtriss, kein Pixel-Look.

uniform sampler2D SceneSampler;
uniform sampler2D DepthSampler;

layout(std140) uniform CutLens {
    vec4 FromStrength; // xyz = Nahtanfang, w = Staerke 0..1
    vec4 ToReach;      // xyz = Nahtende, w = wie weit die Naht schon laeuft (0..1)
    vec4 NormalTime;   // xyz = Normale der Ebene, w = Zeit (s)
};

in vec2 texCoord;
flat in mat4 viewProj;
flat in mat4 invViewProj;

out vec4 fragColor;

vec2 projectUv(vec3 p, out bool ok) {
    vec4 clip = viewProj * vec4(p, 1.0);
    ok = clip.w > 0.001;
    return clip.xy / max(clip.w, 0.001) * 0.5 + 0.5;
}

void main() {
    vec3 scene = texture(SceneSampler, texCoord).rgb;
    float strength = FromStrength.w;
    if (strength <= 0.002) {
        fragColor = vec4(scene, 1.0);
        return;
    }
    vec2 centered = texCoord * 2.0 - 1.0;
    vec4 farPoint = invViewProj * vec4(centered, 1.0, 1.0);
    vec3 dir = normalize(farPoint.xyz / farPoint.w);

    // Naechste Punkte zwischen Sehstrahl (Kamera im Ursprung) und Naht.
    vec3 a = FromStrength.xyz;
    vec3 b = mix(a, ToReach.xyz, ToReach.w);
    vec3 seg = b - a;
    float segLen2 = max(dot(seg, seg), 1e-6);
    float d1 = dot(dir, seg);
    float d2 = dot(dir, a);
    float d3 = dot(seg, a);
    float denom = segLen2 - d1 * d1;
    float u = denom > 1e-6 ? clamp((d1 * d2 - d3) / denom, 0.0, 1.0) : 0.0;
    vec3 onSeg = a + seg * u;
    float t = max(dot(onSeg, dir), 0.0);
    vec3 onRay = dir * t;
    vec3 gap = onRay - onSeg;
    float dist = length(gap);

    // Verdeckung: liegt die Szene deutlich vor der Naht, bricht dort nichts.
    float depth = texture(DepthSampler, texCoord).r;
    float sceneDist = 1e6;
    if (depth < 0.99999) {
        vec4 point = invViewProj * vec4(centered, depth * 2.0 - 1.0, 1.0);
        sceneDist = length(point.xyz / point.w);
    }
    float visible = 1.0 - smoothstep(0.0, 1.5, t - sceneDist);

    // Breite im Bild etwa konstant: mit der Entfernung wachsen.
    float width = 0.05 + 0.016 * t;
    float band = exp(-pow(dist / width, 2.0));
    float along = smoothstep(0.0, 0.06, u) * (1.0 - smoothstep(0.9, 1.0, u));
    float w = band * along * visible * strength;
    if (w <= 0.001) {
        fragColor = vec4(scene, 1.0);
        return;
    }

    // Bildrichtung der Normalen und Seite der Naht, auf der der Strahl vorbeigeht.
    bool okA;
    bool okB;
    vec2 p0 = projectUv(onSeg, okA);
    vec2 p1 = projectUv(onSeg + NormalTime.xyz * 0.25, okB);
    vec2 nrm = (okA && okB) ? p1 - p0 : vec2(1.0, 0.0);
    nrm = length(nrm) > 1e-5 ? normalize(nrm) : vec2(1.0, 0.0);
    float side = dot(gap, NormalTime.xyz) >= 0.0 ? 1.0 : -1.0;
    // Leichtes Flimmern laengs der Naht
    float shimmer = 0.85 + 0.15 * sin(u * 40.0 - NormalTime.w * 60.0);
    vec2 offset = nrm * side * w * shimmer * 0.016;

    vec3 bent;
    bent.r = texture(SceneSampler, texCoord + offset * 1.3).r;
    bent.g = texture(SceneSampler, texCoord + offset).g;
    bent.b = texture(SceneSampler, texCoord + offset * 0.7).b;
    // Kalter Hauch in der Naht und ein haarfeiner Lichtriss in der Mitte.
    vec3 color = mix(bent, bent * vec3(0.92, 0.98, 1.06) + vec3(0.03, 0.04, 0.06), w * 0.6);
    float crack = exp(-pow(dist / (width * 0.16), 2.0)) * along * visible * strength;
    color += vec3(0.82, 0.93, 1.0) * crack * 0.55;
    fragColor = vec4(color, 1.0);
}

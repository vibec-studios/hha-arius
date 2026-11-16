#version 330

#moj_import <hha_arius:arius_noise.glsl>

// Das weisse Loch: Gegenstueck zum schwarzen Loch von HHA Void. Statt Licht zu schlucken, stoesst es Licht ab:
// Strahlen werden vom Kern weggebogen (die Welt dahinter woelbt sich wie durch eine Blase nach aussen), der Kern
// selbst gleisst weiss, und aus ihm stroemt eine Scheibe aus Eis und goldenem Licht spiralfoermig NACH AUSSEN.
// Beim Kollaps faellt der Kern zusammen und eine Brechungs-Schockwelle laeuft durch das Bild. Weich, ohne Pixel-Look.

uniform sampler2D SceneSampler;
uniform sampler2D DepthSampler;

layout(std140) uniform WhiteHole {
    vec4 CenterHorizon;   // xyz = Mitte relativ zur Kamera, w = Kernradius
    vec4 DiskNormalOuter; // xyz = Scheiben-Normale, w = Aussenradius der Scheibe
    vec4 Params;          // x = Zeit (s), y = Intensitaet, z = Blitz, w = Zug (0..1, wie stark der Sog sichtbar ist)
    vec4 Shock;           // x = Radius der Schockwelle (Bloecke), y = Breite, z = Staerke, w = 0
};

in vec2 texCoord;
flat in mat4 viewProj;
flat in mat4 invViewProj;

out vec4 fragColor;

const int MAX_STEPS = 96;
const vec3 MIST = vec3(0.93, 0.96, 1.0);
const vec3 ICE = vec3(0.62, 0.86, 1.0);
const vec3 GOLD = vec3(1.0, 0.84, 0.52);

vec2 projectUv(vec3 worldPos, out bool ok) {
    vec4 clip = viewProj * vec4(worldPos, 1.0);
    ok = clip.w > 0.001;
    return clip.xy / max(clip.w, 0.001) * 0.5 + 0.5;
}

vec3 sampleScene(vec3 worldPos) {
    bool ok;
    vec2 uv = projectUv(worldPos, ok);
    if (!ok) return MIST;
    vec3 color = texture(SceneSampler, uv).rgb;
    vec2 edge = smoothstep(vec2(-0.2), vec2(0.05), uv) * smoothstep(vec2(1.2), vec2(0.95), uv);
    return mix(MIST, color, edge.x * edge.y);
}

// Ausstroemende Scheibe: Spiralarme laufen nach aussen, innen weiss, Mitte eisblau, Rand golden.
vec4 shadeDisk(vec3 hit, vec3 rayDir, vec3 normal, float rs, float outer, float time) {
    vec3 axisU = normalize(cross(normal, abs(normal.y) > 0.9 ? vec3(1.0, 0.0, 0.0) : vec3(0.0, 1.0, 0.0)));
    vec3 axisV = cross(normal, axisU);
    vec2 p = vec2(dot(hit, axisU), dot(hit, axisV));
    float r = length(p);
    float inner = rs * 1.6;
    float x = (r - inner) / (outer - inner);
    if (x < 0.0 || x > 1.0) return vec4(0.0);

    float angle = atan(p.y, p.x);
    float logR = log(r / rs);
    // Gegenlaeufig zum schwarzen Loch und mit radialem Ausfluss: die Struktur wandert nach aussen.
    float spin = angle - time * 2.2 * pow(inner / r, 1.2);
    vec3 domain = vec3(cos(spin) * 2.2, sin(spin) * 2.2, logR * 3.0 - time * 1.6);
    float clouds = arius_fbm(domain * 1.4);
    float arms = 0.5 + 0.5 * sin(spin * 4.0 - logR * 6.0 + time * 3.0);
    float density = clouds * 0.75 + arms * 0.35;

    vec3 color = mix(vec3(1.0), ICE, smoothstep(0.0, 0.45, x));
    color = mix(color, GOLD, smoothstep(0.55, 1.0, x) * 0.85);
    // Funkelnde Eiskristalle in den Armen
    float sparkle = step(0.985, arius_hash(floor(vec3(p * 6.0, time * 4.0))));
    color += vec3(0.9, 0.97, 1.0) * sparkle * 1.5;

    float fade = smoothstep(0.0, 0.08, x) * (1.0 - smoothstep(0.7, 1.0, x));
    float alpha = smoothstep(0.42, 0.78, density) * fade;
    return vec4(color * (0.75 + 0.9 * (1.0 - x)), alpha);
}

void main() {
    vec3 scene = texture(SceneSampler, texCoord).rgb;
    float intensity = Params.y;
    float flash = Params.z;
    float pull = Params.w;

    vec2 centered = texCoord * 2.0 - 1.0;
    float vignette = smoothstep(0.3, 1.45, length(centered));
    // Die Welt wird heller und kalt, zum Rand hin etwas mehr (Gegenteil der Void-Abdunklung).
    vec3 cooled = mix(scene, scene * vec3(0.9, 0.97, 1.06) + vec3(0.04, 0.05, 0.07), intensity * (0.18 + 0.22 * vignette));

    vec4 farPoint = invViewProj * vec4(centered, 1.0, 1.0);
    vec3 dir = normalize(farPoint.xyz / farPoint.w);

    vec3 center = CenterHorizon.xyz;
    float rs = CenterHorizon.w;
    float outer = DiskNormalOuter.w;
    vec3 normal = DiskNormalOuter.xyz;
    float influence = max(outer * 1.3, rs * 8.0);

    // Schockwelle: Brechungsring in der Welt um die Mitte (wirkt auch, wenn der Kern schon weg ist).
    float along0 = dot(center, dir);
    float perp = sqrt(max(0.0, dot(center, center) - along0 * along0));
    vec3 shocked = cooled;
    if (Shock.z > 0.001 && along0 > 0.0) {
        float ring = exp(-pow((perp - Shock.x) / max(0.3, Shock.y), 2.0));
        if (ring > 0.002) {
            vec2 c2;
            bool ok;
            c2 = projectUv(center, ok);
            vec2 away = ok ? normalize(texCoord - c2 + vec2(1e-5)) : vec2(0.0);
            vec2 uv = texCoord + away * ring * Shock.z * 0.035;
            shocked = texture(SceneSampler, uv).rgb;
            shocked = mix(shocked, shocked * vec3(0.9, 0.97, 1.06) + 0.05, intensity * 0.3);
            shocked += vec3(0.85, 0.94, 1.0) * ring * Shock.z * 0.35;
        }
    }

    float camDist2 = dot(center, center);
    float closest2 = camDist2 - along0 * along0;
    bool cameraInside = camDist2 < influence * influence;
    if (rs <= 0.01 || (!cameraInside && (along0 < 0.0 || closest2 > influence * influence))) {
        fragColor = vec4(shocked + MIST * flash, 1.0);
        return;
    }

    float depth = texture(DepthSampler, texCoord).r;
    float sceneDist = 1e6;
    if (depth < 0.99999) {
        vec4 point = invViewProj * vec4(centered, depth * 2.0 - 1.0, 1.0);
        sceneDist = length(point.xyz / point.w);
    }
    float entry = cameraInside ? 0.0 : along0 - sqrt(max(0.0, influence * influence - closest2));
    if (sceneDist < entry) {
        fragColor = vec4(shocked + MIST * flash, 1.0);
        return;
    }

    vec3 rel = dir * entry - center;
    vec3 vel = dir;
    float travelled = entry;
    vec3 angular = cross(rel, vel);
    float h2 = dot(angular, angular);

    vec3 accum = vec3(0.0);
    float transmit = 1.0;
    float minR = 1e6;
    bool core = false;
    bool blocked = false;
    vec3 coreHit = vec3(0.0);

    for (int i = 0; i < MAX_STEPS; i++) {
        float r2 = dot(rel, rel);
        float r = sqrt(r2);
        minR = min(minR, r);
        if (r < rs) {
            core = true;
            coreHit = rel;
            break;
        }
        if (travelled > sceneDist) {
            blocked = true;
            break;
        }
        if (r > influence && dot(rel, vel) > 0.0) break;

        float dt = clamp(0.2 * (r - rs * 0.8), rs * 0.04, rs * 1.6);
        float window = 1.0 - smoothstep(influence * 0.5, influence, r);
        // Abstossend: Vorzeichen umgekehrt zum schwarzen Loch, Staerke folgt dem Sog-Fortschritt.
        vec3 acc = (1.1 + 0.6 * pull) * rs * h2 * rel / (r2 * r2 * r) * window;
        vec3 prev = rel;
        vel += acc * dt;
        rel += normalize(vel) * dt;
        travelled += dt;

        float d0 = dot(prev, normal);
        float d1 = dot(rel, normal);
        if (d0 * d1 < 0.0 && transmit > 0.01) {
            vec3 hit = mix(prev, rel, d0 / (d0 - d1));
            vec4 disk = shadeDisk(hit, normalize(vel), normal, rs, outer, Params.x);
            accum += disk.rgb * disk.a * transmit;
            transmit *= 1.0 - disk.a;
        }
    }

    vec3 color;
    if (core) {
        // Gleissender Kern mit kaltem Rand und goldenem Fresnel-Saum.
        float fres = 1.0 - abs(dot(normalize(coreHit), -normalize(vel)));
        float swirl = arius_fbm(normalize(coreHit) * 3.0 + vec3(0.0, Params.x * 0.8, 0.0));
        vec3 white = mix(vec3(1.0), ICE, smoothstep(0.55, 1.0, fres) * 0.6);
        white = mix(white, GOLD, smoothstep(0.82, 1.0, fres) * 0.7);
        color = white * (1.1 + 0.25 * swirl) * transmit + accum;
    } else {
        float remaining = blocked ? 0.0 : clamp(sceneDist - travelled, 2.0, 1000.0);
        vec3 background = sampleScene(rel + center + normalize(vel) * remaining);
        background = mix(background, background * vec3(0.9, 0.97, 1.06) + vec3(0.04, 0.05, 0.07), intensity * (0.18 + 0.22 * vignette));
        // Je naeher am Kern vorbei, desto heller und blasser (Licht wird abgestossen und gestreut).
        float near = smoothstep(influence, rs * 1.2, minR);
        background = mix(background, MIST, intensity * near * 0.55);
        // Weicher Glanzhof statt harter Baender
        float halo = exp(-(minR - rs) / (rs * 0.9)) * 0.9 + exp(-(minR - rs) / (rs * 3.0)) * 0.25;
        vec3 emission = accum + mix(ICE, vec3(1.0), 0.6) * halo;
        color = background * transmit + min(emission, vec3(1.4)) * intensity;
    }
    fragColor = vec4(color + MIST * flash, 1.0);
}

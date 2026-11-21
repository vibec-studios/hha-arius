#version 330

// Die Stille (Gipfel-Finale): hartes Schwarz-Weiss mit Kontrast und schwerer Vignette. Gold (Ansagen, der Blitz der
// Klinge) bleibt als einzige Farbe stehen, damit das Kreuz-Fenster lesbar bleibt. Dazu feines Filmkorn.

uniform sampler2D SceneSampler;

layout(std140) uniform SilenceLens {
    vec4 StrengthTime; // x = Staerke 0..1, y = Zeit (s)
};

in vec2 texCoord;

out vec4 fragColor;

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453);
}

void main() {
    vec3 c = texture(SceneSampler, texCoord).rgb;
    float s = StrengthTime.x;
    float luma = dot(c, vec3(0.299, 0.587, 0.114));
    // Kontrastkurve um die Mitte
    float g = clamp((luma - 0.5) * 1.45 + 0.5, 0.0, 1.0);
    g = g * g * (3.0 - 2.0 * g);
    // Gold & warnendes Glutrot der letzten Attacke erkennen: warm, deutlich gesaettigt
    float warm = c.r - c.b;
    float gold = smoothstep(0.18, 0.4, warm) * smoothstep(0.25, 0.5, c.g) * step(c.b, c.g);
    float danger = smoothstep(0.32, 0.55, c.r - max(c.g, c.b) * 0.85) * smoothstep(0.45, 0.75, c.r);
    vec3 bw = vec3(g);
    vec3 keep = mix(bw, c * vec3(1.08, 1.0, 0.85), max(gold, danger * 0.85));
    // Vignette
    vec2 d = texCoord - 0.5;
    float vig = 1.0 - smoothstep(0.32, 0.78, length(d * vec2(1.2, 1.0)));
    keep *= mix(1.0, 0.35 + 0.65 * vig, s);
    // Filmkorn
    float n = hash(texCoord * 1000.0 + vec2(StrengthTime.y * 37.0, StrengthTime.y * 11.0)) - 0.5;
    keep += n * 0.035 * s;
    fragColor = vec4(mix(c, keep, s), 1.0);
}

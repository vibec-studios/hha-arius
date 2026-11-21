#version 330

#moj_import <minecraft:fog.glsl>

uniform sampler2D Sampler0;

in vec2 texCoord0;
in vec4 vertexColor;
in float viewDist;

out vec4 fragColor;

void main() {
    vec4 tex = texture(Sampler0, texCoord0);
    if (tex.a < 0.1) {
        discard;
    }
    vec3 rgb = tex.rgb * vertexColor.rgb;
    float fogFactor;
    if (FogEnvironmentalEnd < 65.0) {
        // Dense blizzard / whiteout / cave: respect environmental visibility so peaks vanish naturally in storms.
        fogFactor = linear_fog_value(viewDist, FogEnvironmentalStart, FogEnvironmentalEnd);
    } else {
        // Clear or light snow: keep real distant mountains sharp with gentle aerial perspective instead of a fog wall.
        float hazeStart = max(FogEnvironmentalStart, 90.0);
        float hazeEnd = max(FogEnvironmentalEnd * 2.4, 680.0);
        fogFactor = min(linear_fog_value(viewDist, hazeStart, hazeEnd), 0.52);
    }
    vec3 finalRgb = mix(rgb, FogColor.rgb, fogFactor * FogColor.a);
    fragColor = vec4(finalRgb, 1.0);
}

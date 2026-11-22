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
    // Derselbe Dunst wie auf den echten Chunks, sonst sieht man die Naht zwischen beiden.
    float fogFactor = linear_fog_value(viewDist, FogEnvironmentalStart, FogEnvironmentalEnd);
    vec3 finalRgb = mix(rgb, FogColor.rgb, fogFactor * FogColor.a);
    fragColor = vec4(finalRgb, 1.0);
}

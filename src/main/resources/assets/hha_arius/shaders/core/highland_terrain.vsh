#version 330

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

in vec3 Position;
in vec2 UV0;
in vec4 Color;

out vec2 texCoord0;
out vec4 vertexColor;
out float viewDist;

void main() {
    vec3 worldPos = Position + ModelOffset;
    vec4 clip = ProjMat * ModelViewMat * vec4(worldPos, 1.0);
    // Slight depth bias prevents z-fighting on the seam where vanilla chunks meet distant highland meshes.
    clip.z += 0.00018 * clip.w;
    gl_Position = clip;
    texCoord0 = UV0;
    vertexColor = Color;
    viewDist = length(worldPos);
}

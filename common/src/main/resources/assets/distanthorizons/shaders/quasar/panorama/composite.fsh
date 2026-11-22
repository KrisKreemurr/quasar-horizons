#version 330
// needed for "layout(location = 0)" required as as of MC 26.3
#extension GL_ARB_separate_shader_objects : require
// Quasar Horizons: paints the far-LOD panorama into DH's color and depth textures.

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

layout (std140) uniform panoUniformBlock
{
    // face view-projection matrices and their inverses (relative to the panorama center)
    mat4 uFaceVP[6];
    mat4 uInvFaceVP[6];
    // the live camera's inverse and normal view-projection (relative to the camera)
    mat4 uInvCombined;
    mat4 uCombined;
    // panorama center minus camera position
    vec4 uCenterOffset;
    // depth value of an empty (cleared) pixel
    float uFarDepth;
    int uZeroToOne;
};

uniform sampler2D uC0;
uniform sampler2D uC1;
uniform sampler2D uC2;
uniform sampler2D uC3;
uniform sampler2D uC4;
uniform sampler2D uC5;
uniform sampler2D uD0;
uniform sampler2D uD1;
uniform sampler2D uD2;
uniform sampler2D uD3;
uniform sampler2D uD4;
uniform sampler2D uD5;

ivec2 texelFor(sampler2D s, vec2 uv)
{
    ivec2 size = textureSize(s, 0);
    return clamp(ivec2(uv * vec2(size)), ivec2(0), size - 1);
}

vec4 fetchColor(int f, vec2 uv)
{
    if (f == 0) return texelFetch(uC0, texelFor(uC0, uv), 0);
    if (f == 1) return texelFetch(uC1, texelFor(uC1, uv), 0);
    if (f == 2) return texelFetch(uC2, texelFor(uC2, uv), 0);
    if (f == 3) return texelFetch(uC3, texelFor(uC3, uv), 0);
    if (f == 4) return texelFetch(uC4, texelFor(uC4, uv), 0);
    return texelFetch(uC5, texelFor(uC5, uv), 0);
}

float fetchDepth(int f, vec2 uv)
{
    if (f == 0) return texelFetch(uD0, texelFor(uD0, uv), 0).r;
    if (f == 1) return texelFetch(uD1, texelFor(uD1, uv), 0).r;
    if (f == 2) return texelFetch(uD2, texelFor(uD2, uv), 0).r;
    if (f == 3) return texelFetch(uD3, texelFor(uD3, uv), 0).r;
    if (f == 4) return texelFetch(uD4, texelFor(uD4, uv), 0).r;
    return texelFetch(uD5, texelFor(uD5, uv), 0).r;
}

float depthToNdc(float d) { return (uZeroToOne != 0) ? d : d * 2.0 - 1.0; }
float ndcToDepth(float z) { return (uZeroToOne != 0) ? z : z * 0.5 + 0.5; }

void main()
{
    // view direction of this pixel
    vec4 ndc = vec4(texCoord * 2.0 - 1.0, (uZeroToOne != 0) ? 0.5 : 0.0, 1.0);
    vec4 p = uInvCombined * ndc;
    vec3 dir = normalize(p.xyz / p.w);
    
    // pick the cube face that direction falls in
    vec3 a = abs(dir);
    int f;
    if (a.x >= a.y && a.x >= a.z) f = (dir.x > 0.0) ? 0 : 1;
    else if (a.y >= a.z)         f = (dir.y > 0.0) ? 2 : 3;
    else                         f = (dir.z > 0.0) ? 4 : 5;
    
    vec4 faceClip = uFaceVP[f] * vec4(dir, 1.0);
    if (faceClip.w <= 0.0) discard;
    vec2 uv = (faceClip.xy / faceClip.w) * 0.5 + 0.5;
    
    float d = fetchDepth(f, uv);
    if (d == uFarDepth) discard; // nothing far away in this direction
    
    // rebuild the position (relative to the panorama center), then move it to the live camera
    vec4 pc = uInvFaceVP[f] * vec4(uv * 2.0 - 1.0, depthToNdc(d), 1.0);
    vec3 posFromCamera = pc.xyz / pc.w + uCenterOffset.xyz;
    
    vec4 clip = uCombined * vec4(posFromCamera, 1.0);
    if (clip.w <= 0.0) discard;
    
    gl_FragDepth = clamp(ndcToDepth(clip.z / clip.w), 0.0, 1.0);
    fragColor = fetchColor(f, uv);
}

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

int faceFor(vec3 dir)
{
    vec3 a = abs(dir);
    if (a.x >= a.y && a.x >= a.z) return (dir.x > 0.0) ? 0 : 1;
    if (a.y >= a.z)               return (dir.y > 0.0) ? 2 : 3;
    return (dir.z > 0.0) ? 4 : 5;
}

/**
 * Looks up the panorama in a direction (seen from the panorama center).
 * Returns false if nothing is there; otherwise the hit position (relative to the center),
 * the face and the face uv.
 */
bool lookup(vec3 dirFromCenter, out vec3 posFromCenter, out int f, out vec2 uv)
{
    f = faceFor(dirFromCenter);
    vec4 faceClip = uFaceVP[f] * vec4(dirFromCenter, 1.0);
    if (faceClip.w <= 0.0) return false;
    uv = (faceClip.xy / faceClip.w) * 0.5 + 0.5;
    
    float d = fetchDepth(f, uv);
    if (d == uFarDepth) return false;
    
    vec4 pc = uInvFaceVP[f] * vec4(uv * 2.0 - 1.0, depthToNdc(d), 1.0);
    posFromCenter = pc.xyz / pc.w;
    return true;
}

void main()
{
    // view ray of this pixel (from the live camera)
    vec4 ndc = vec4(texCoord * 2.0 - 1.0, (uZeroToOne != 0) ? 0.5 : 0.0, 1.0);
    vec4 p = uInvCombined * ndc;
    vec3 ray = normalize(p.xyz / p.w);
    
    // the camera, seen from the panorama center
    vec3 camFromCenter = -uCenterOffset.xyz;
    
    // Parallax correction: the panorama was taken from its center, not from where the camera is now.
    // Start by looking along the camera ray, then repeatedly move the lookup direction to where that
    // ray actually is at the depth we found, so far terrain stays put in the world while you move.
    vec3 posFromCenter;
    int f;
    vec2 uv;
    if (!lookup(ray, posFromCenter, f, uv)) discard;
    
    for (int i = 0; i < 3; i++)
    {
        // distance along the camera ray to the surface we just found
        float t = max(dot(posFromCenter - camFromCenter, ray), 1.0);
        vec3 guess = camFromCenter + ray * t;
        
        vec3 refinedPos;
        int refinedFace;
        vec2 refinedUv;
        if (!lookup(normalize(guess), refinedPos, refinedFace, refinedUv)) discard;
        posFromCenter = refinedPos;
        f = refinedFace;
        uv = refinedUv;
    }
    
    vec3 posFromCamera = posFromCenter + uCenterOffset.xyz;
    vec4 clip = uCombined * vec4(posFromCamera, 1.0);
    if (clip.w <= 0.0) discard;
    
    gl_FragDepth = clamp(ndcToDepth(clip.z / clip.w), 0.0, 1.0);
    fragColor = fetchColor(f, uv);
}

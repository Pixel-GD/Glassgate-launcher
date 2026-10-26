package com.pixelgd.glassgate.glass

/**
 * AGSL sources (Android 13+ RuntimeShader). Kept in one place so shader tweaks don't
 * get scattered across views. Compile errors only show at runtime, so every user of
 * these must catch exceptions from RuntimeShader() and fall back.
 */
object Shaders {

    /**
     * Liquid Glass lens. Applied as a RenderEffect on a node holding the blurred backdrop.
     * Bends the backdrop near the rim (refraction), splits colour slightly at the edge,
     * adds a tilt-driven specular rim and a faint top sheen.
     */
    const val GLASS = """
uniform shader content;
uniform float2 size;
uniform float radius;
uniform float bezel;
uniform float refraction;
uniform float2 light;
uniform float4 tint;
uniform float sheen;

float sdRoundRect(float2 p, float2 b, float r) {
    float2 q = abs(p) - b + r;
    return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;
}

half4 main(float2 p) {
    float2 half_size = size * 0.5;
    float2 lp = p - half_size;
    float d = sdRoundRect(lp, half_size, radius);
    float e = 1.0;
    float2 g = float2(
        sdRoundRect(lp + float2(e, 0.0), half_size, radius) - sdRoundRect(lp - float2(e, 0.0), half_size, radius),
        sdRoundRect(lp + float2(0.0, e), half_size, radius) - sdRoundRect(lp - float2(0.0, e), half_size, radius));
    float2 n = g / max(length(g), 0.0001);

    float depth = clamp(-d / bezel, 0.0, 1.0);
    float lens = (1.0 - depth) * (1.0 - depth);
    float2 offset = -n * lens * refraction;

    float3 col = float3(
        content.eval(p + offset * 1.08).r,
        content.eval(p + offset).g,
        content.eval(p + offset * 0.92).b);
    col = mix(col, tint.rgb, tint.a);

    float rim = 1.0 - clamp(-d / (bezel * 0.45), 0.0, 1.0);
    float2 l = normalize(light);
    float spec = pow(max(dot(n, l), 0.0), 2.0) * rim;
    float back = pow(max(dot(n, -l), 0.0), 2.0) * rim;
    col += float3(spec * 0.55 + back * 0.18);
    col += float3(sheen * (1.0 - p.y / size.y));

    float a = 1.0 - smoothstep(-0.75, 0.75, d);
    return half4(half3(col * a), half(a));
}
"""

    /**
     * Glassgate's default wallpaper, in the spirit of the iOS 27 "Celosia" art:
     * layered curved petals with soft shadows. Light: sand gold into soft purple.
     * Dark: deep indigo with silvery-blue edges. Original artwork, drawn live.
     */
    const val CELOSIA = """
uniform float2 size;
uniform float dark;
uniform float phase;

float3 paletteLight(float k) {
    float3 a = float3(0.97, 0.87, 0.68);
    float3 b = float3(0.95, 0.73, 0.58);
    float3 c = float3(0.82, 0.64, 0.86);
    float3 d = float3(0.52, 0.40, 0.80);
    if (k < 0.33) return mix(a, b, k / 0.33);
    if (k < 0.66) return mix(b, c, (k - 0.33) / 0.33);
    return mix(c, d, (k - 0.66) / 0.34);
}

float3 paletteDark(float k) {
    float3 a = float3(0.16, 0.17, 0.38);
    float3 b = float3(0.12, 0.12, 0.30);
    float3 c = float3(0.10, 0.08, 0.24);
    float3 d = float3(0.05, 0.04, 0.14);
    if (k < 0.33) return mix(a, b, k / 0.33);
    if (k < 0.66) return mix(b, c, (k - 0.33) / 0.33);
    return mix(c, d, (k - 0.66) / 0.34);
}

float3 palette(float k) {
    return mix(paletteLight(k), paletteDark(k), dark);
}

half4 main(float2 p) {
    float2 uv = p / size;
    float aspect = size.x / size.y;
    float x = uv.x * aspect;
    float3 col = palette(0.0) * 1.03;
    float3 edgeLight = mix(float3(1.0, 0.95, 0.85), float3(0.62, 0.72, 0.95), dark);

    for (int i = 0; i < 6; i++) {
        float fi = float(i);
        float k = fi / 5.0;
        float yc = 0.14 + fi * 0.155
            + 0.075 * sin(x * (2.1 + fi * 0.37) + fi * 1.9 + phase)
            + 0.025 * sin(x * (5.3 - fi * 0.4) - fi * 0.7);
        float edge = uv.y - yc;
        float inside = smoothstep(-0.0015, 0.0015, edge);
        float shadow = exp(-max(-edge, 0.0) * 55.0) * (1.0 - inside);
        col *= 1.0 - 0.22 * shadow;
        float fold = exp(-max(edge, 0.0) * 22.0);
        float3 layer = palette(k) * (1.0 - 0.10 * smoothstep(0.0, 0.25, edge));
        layer = mix(layer, edgeLight, fold * mix(0.22, 0.40, dark));
        col = mix(col, layer, inside);
    }
    float vignette = smoothstep(1.25, 0.35, length((uv - float2(0.5, 0.45)) * float2(aspect, 1.0)));
    col *= mix(0.92, 1.0, vignette);
    return half4(half3(col), 1.0);
}
"""
}

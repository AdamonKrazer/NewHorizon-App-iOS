package com.newhorizon.thinclient.render;

/** A packed texture descriptor shares the existing 16-byte terrain vertex. */
final class TerrainShaders {
    private TerrainShaders() { }
    private static final int WATER_FLOW=com.newhorizon.thinclient.world.VanillaBlockTextures.tileId("water_flow");
    private static final int LAVA_FLOW=com.newhorizon.thinclient.world.VanillaBlockTextures.tileId("lava_flow");
    static final String VERTEX = "#version 150 core\n"
        + "in vec3 aPosition;in vec4 aColor;in vec2 aLight;out vec2 vLight;out vec2 vModelUv;out vec4 vColor;out vec3 vWorld;flat out int vTexture;\n"
        + "uniform vec3 uCamera;uniform vec2 uRotation;uniform float uAspect,uProjection;\n"
        + "void main(){vec3 p=aPosition-uCamera;float y=radians(-uRotation.x);"
        + "p=vec3(cos(y)*p.x-sin(y)*p.z,p.y,sin(y)*p.x+cos(y)*p.z);p.x=-p.x;p.z=-p.z;"
        + "float x=radians(uRotation.y);p=vec3(p.x,cos(x)*p.y-sin(x)*p.z,sin(x)*p.y+cos(x)*p.z);"
        + "float n=.05,f=192.;gl_Position=vec4(p.x*uProjection/uAspect,p.y*uProjection,-(f+n)/(f-n)*p.z-2.*f*n/(f-n),-p.z);"
        + "ivec4 b=ivec4(aColor*255.+.5);vTexture=b.r|(b.g<<8)|(b.b<<16)|(b.a<<24);bool foliage=((vTexture>>20)&7)==7&&(vTexture&268435456)!=0;int mask=foliage?16:31;vModelUv=vec2((vTexture>>12)&mask,(vTexture>>23)&mask)/16.;vColor=aColor;vWorld=aPosition;vLight=aLight;}\n";

    static final String FRAGMENT = "#version 150 core\n"
        + "in vec4 vColor;in vec3 vWorld;in vec2 vLight;in vec2 vModelUv;flat in int vTexture;out vec4 fragColor;\n"
        + "uniform vec3 uCamera;uniform float uMedium,uMaterialPass,uCrumbling;uniform sampler2D uRailAtlas,uBlockAtlas,uBiomeTints;"
        + "uniform vec2 uBlockAtlasSize;uniform int uGrassSide,uGrassOverlay;\n"
        + "vec4 blockTex(int tile,vec2 uv){vec2 cell=vec2(tile%32,tile/32)*32.;"
        + "return texture(uBlockAtlas,(cell+clamp(fract(uv)*32.,vec2(.01),vec2(31.99)))/uBlockAtlasSize);}\n"
        + "vec3 tint(int id){if(id>=1&&id<=3)return texelFetch(uBiomeTints,ivec2((vTexture>>23)&127,id-1),0).rgb;"
        + "if(id==4)return vec3(97.,153.,97.)/255.;"
        + "if(id==5)return vec3(128.,167.,85.)/255.;if(id==6)return vec3(1.,.08,0.);return vec3(1.);}\n"
        + "void main(){bool textured=(vTexture&1073741824)!=0 && vColor.a<.5;"
        + "bool rail=!textured&&vColor.a>.009&&vColor.a<.014;"
        + "bool liquid=textured?(vTexture&262144)!=0:vColor.a<.009;"
        + "if((uMaterialPass<.5&&liquid)||(uMaterialPass>.5&&!liquid))discard;"
        + "vec3 color=vColor.rgb;float alpha=liquid?(vColor.a<.006?.56:.82):1.;"
        + "if(textured){int face=(vTexture>>20)&7;int tile=vTexture&4095;int rot=(vTexture>>12)&3;int t=(vTexture>>14)&7;vec2 uv;"
        + "if(face==0)uv=vec2(vWorld.x,-vWorld.z);else if(face==1)uv=vWorld.xz;"
        + "else if(face==2)uv=vec2(-vWorld.x,-vWorld.y);else if(face==3)uv=vec2(vWorld.x,-vWorld.y);"
        + "else if(face==4)uv=vec2(vWorld.z,-vWorld.y);else if(face==5)uv=vec2(-vWorld.z,-vWorld.y);"
        + "else uv=vec2((fract(vWorld.x)-.15)/.7,-vWorld.y);"
        + "if(face==7){uv=clamp(vModelUv,vec2(.0001),vec2(.9999));t=0;rot=0;}for(int i=0;i<rot;i++)uv=vec2(-uv.y,uv.x);"
        + "if(tile=="+WATER_FLOW+"||tile=="+LAVA_FLOW+"){if(face<2)uv=vec2(.5)+(fract(uv)-vec2(.5))*.5;else uv=fract(uv)*.5;}"
        + "vec4 texel=blockTex(tile,uv);"
        + "if(texel.a<.1)discard;"
        // Vanilla's crumbling blend multiplies the existing lit block by twice the
        // texture color. Lighting the cracks again or writing them opaque replaces
        // the block's material with gray pixels instead of darkening its surface.
        + "if(uCrumbling>.5){vec3 delta=vWorld-uCamera;float d=nhFogRange.z>.5?length(delta):max(length(delta.xz),abs(delta.y));"
        + "fragColor=vec4(mix(texel.rgb,vec3(.5),smoothstep(nhFogRange.x,nhFogRange.y,d)),texel.a);return;}"
        + "color=texel.rgb*tint(t);"
        + "if(face==7&&(vTexture&268435456)!=0){int biome=(((vTexture>>12)&15)<<3)|((vTexture>>23)&7);color*=texelFetch(uBiomeTints,ivec2(biome,1),0).rgb;}"
        + "if(tile==uGrassSide){vec4 overlay=blockTex(uGrassOverlay,uv);color=mix(color,overlay.rgb*tint(1),overlay.a);}"
        + "float shade=face==0?.5:face==1||face==6?1.:face<4?.8:.6;"
        + "if(face==7){vec3 normal=abs(normalize(cross(dFdx(vWorld),dFdy(vWorld))));shade=(vTexture&131072)!=0?1.:.6+normal.y*.4+normal.z*.2;}if((vTexture&524288)!=0)shade=1.;color*=shade;alpha=liquid?texel.a:1.;}"
        + "else if(rail){float tile=floor(vColor.b*255.+.5);vec2 uv=(vec2(mod(tile,4.),floor(tile/4.))*16.+clamp(vColor.rg*16.,vec2(.01),vec2(15.99)))/vec2(64.,32.);"
        + "vec4 texel=texture(uRailAtlas,uv);if(texel.a<.5)discard;color=texel.rgb;}"
        + "color*=nhLight(vLight);fragColor=vec4(nhFog(color,vWorld,uCamera),alpha);}\n";
}

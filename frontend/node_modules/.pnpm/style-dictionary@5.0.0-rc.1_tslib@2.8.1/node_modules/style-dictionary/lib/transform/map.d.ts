/**
 * @typedef {import('../../types/DesignToken.d.ts').DesignToken} DesignToken
 * @typedef {import('../../types/DesignToken.d.ts').TransformedToken} TransformedToken
 * @typedef {import('../../types/Config.d.ts').PlatformConfig} PlatformConfig
 * @typedef {import('../../types/Config.d.ts').Config} Config
 * @typedef {import('../../types/Volume.d.ts').Volume} Volume
 */
/**
 * @param {Map<string, DesignToken>} tokenMap
 * @param {PlatformConfig} config
 * @param {Config} options
 * @param {{ transformedPropRefs?: Set<string>; deferredPropValueTransforms?: Set<string> }} [ctx]
 * @param {Volume} [volume]
 *
 * @return {Promise<Map<string, TransformedToken>>}
 */
export function transformMap(tokenMap: Map<string, DesignToken>, config: PlatformConfig, options: Config, { transformedPropRefs, deferredPropValueTransforms }?: {
    transformedPropRefs?: Set<string>;
    deferredPropValueTransforms?: Set<string>;
}, volume?: Volume): Promise<Map<string, TransformedToken>>;
export type DesignToken = import("../../types/DesignToken.d.ts").DesignToken;
export type TransformedToken = import("../../types/DesignToken.d.ts").TransformedToken;
export type PlatformConfig = import("../../types/Config.d.ts").PlatformConfig;
export type Config = import("../../types/Config.d.ts").Config;
export type Volume = import("../../types/Volume.d.ts").Volume;

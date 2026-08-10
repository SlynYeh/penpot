/**
 * @typedef {import('../../../types/Config.d.ts').ResolveReferencesOptions} RefOpts
 * @typedef {import('../../../types/Config.d.ts').ResolveReferencesOptionsInternal} RefOptsInternal
 * @typedef {import('../../../types/DesignToken.d.ts').TransformedTokens} Tokens
 * @typedef {import('../../../types/DesignToken.d.ts').TransformedToken} Token
 */
/**
 * Public API wrapper around the functon below this one
 * @param {string} value
 * @param {Map<string, Token>} tokenMap
 * @param {RefOpts} [opts]
 * @returns {unknown}
 */
export function resolveReferences(value: string, tokenMap: Map<string, Token>, opts?: RefOpts): unknown;
/**
 * Utility to resolve references inside a string value
 * @param {string} value
 * @param {Map<string, Token>} tokenMap
 * @param {RefOptsInternal} [opts]
 * @returns {unknown}
 */
export function _resolveReferences(value: string, tokenMap: Map<string, Token>, { usesDtcg, warnImmediately, ignorePaths, current_context, stack, foundCirc, firstIteration, objectsOnly, }?: RefOptsInternal): unknown;
export type RefOpts = import("../../../types/Config.d.ts").ResolveReferencesOptions;
export type RefOptsInternal = import("../../../types/Config.d.ts").ResolveReferencesOptionsInternal;
export type Tokens = import("../../../types/DesignToken.d.ts").TransformedTokens;
export type Token = import("../../../types/DesignToken.d.ts").TransformedToken;

/**
 * @typedef {import('../../../types/Config.d.ts').ResolveReferencesOptions} RefOpts
 * @typedef {import('../../../types/Config.d.ts').ResolveReferencesOptionsInternalOld} RefOptsInternal
 * @typedef {import('../../../types/DesignToken.d.ts').PreprocessedTokens} Tokens
 * @typedef {import('../../../types/DesignToken.d.ts').DesignToken} Token
 *  @typedef {import('../../../types/DesignToken.d.ts').TransformedToken} TransformedToken
 */
/**
 * Public API wrapper around the functon below this one
 * @param {string} value
 * @param {Tokens|Map<string, TransformedToken>} tokens
 * @param {RefOpts} [opts]
 * @returns {unknown}
 */
export function resolveReferences(value: string, tokens: Tokens | Map<string, TransformedToken>, opts?: RefOpts): unknown;
/**
 * Utility to resolve references inside a string value
 * @param {string} value
 * @param {Tokens} tokens
 * @param {RefOptsInternal} [opts]
 * @returns {unknown}
 */
export function _resolveReferences(value: string, tokens: Tokens, { usesDtcg, warnImmediately, ignorePaths, current_context, stack, foundCirc, firstIteration, }?: RefOptsInternal): unknown;
export type RefOpts = import("../../../types/Config.d.ts").ResolveReferencesOptions;
export type RefOptsInternal = import("../../../types/Config.d.ts").ResolveReferencesOptionsInternalOld;
export type Tokens = import("../../../types/DesignToken.d.ts").PreprocessedTokens;
export type Token = import("../../../types/DesignToken.d.ts").DesignToken;
export type TransformedToken = import("../../../types/DesignToken.d.ts").TransformedToken;

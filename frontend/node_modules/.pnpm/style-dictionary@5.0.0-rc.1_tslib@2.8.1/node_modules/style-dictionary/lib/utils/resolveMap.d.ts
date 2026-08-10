/**
 *
 * @param {Map<string, TransformedToken>} tokenMap
 * @param {Options} _opts
 * @returns
 */
export function resolveMap(tokenMap: Map<string, TransformedToken>, _opts: Options): void;
export type TransformedToken = import("../../types/DesignToken.d.ts").TransformedToken;
export type Options = {
    ignorePaths?: Set<string>;
    ignoreKeys?: Set<string>;
    usesDtcg?: boolean;
    objectsOnly?: boolean;
};

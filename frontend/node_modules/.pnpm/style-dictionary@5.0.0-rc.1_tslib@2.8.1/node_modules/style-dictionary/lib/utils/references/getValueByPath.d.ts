/**
 * @typedef {import('../../../types/DesignToken.d.ts').TransformedTokens} Tokens
 * @typedef {import('../../../types/DesignToken.d.ts').TransformedToken} Token
 * @param {string[]} path
 * @param {Tokens|Map<string,Token>} tokens
 * @returns {Token|undefined}
 */
export default function getValueByPath(path: string[], tokens: Tokens | Map<string, Token>): Token | undefined;
export type Tokens = import("../../../types/DesignToken.d.ts").TransformedTokens;
export type Token = import("../../../types/DesignToken.d.ts").TransformedToken;

import { mergeMap } from './mergeMap.js';
export function mergeMapTo(innerObservable, concurrent) {
    return mergeMap(() => innerObservable, concurrent);
}
//# sourceMappingURL=mergeMapTo.js.map
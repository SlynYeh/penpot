import { concatMap } from './concatMap.js';
export function concatMapTo(innerObservable) {
    return concatMap(() => innerObservable);
}
//# sourceMappingURL=concatMapTo.js.map
import { concat } from '../observable/concat.js';
import { take } from './take.js';
import { ignoreElements } from './ignoreElements.js';
import { mergeMap } from './mergeMap.js';
import { rx } from '../util/rx.js';
import { map } from './map.js';
export function delayWhen(delayDurationSelector, subscriptionDelay) {
    if (subscriptionDelay) {
        return (source) => concat(rx(subscriptionDelay, take(1), ignoreElements()), rx(source, delayWhen(delayDurationSelector)));
    }
    return mergeMap((value, index) => rx(delayDurationSelector(value, index), take(1), map(() => value)));
}
//# sourceMappingURL=delayWhen.js.map
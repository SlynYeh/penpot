import { bindCallbackInternals } from './bindCallbackInternals.js';
export function bindCallback(callbackFunc, resultSelector, scheduler) {
    return bindCallbackInternals(false, callbackFunc, resultSelector, scheduler);
}
//# sourceMappingURL=bindCallback.js.map
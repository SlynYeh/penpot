import { asyncScheduler } from '../scheduler/async.js';
import { Observable, operate } from '../Observable.js';
import { executeSchedule } from '../util/executeSchedule.js';
export function debounceTime(dueTime, scheduler = asyncScheduler) {
    return (source) => new Observable((destination) => {
        let lastValue;
        let activeTask;
        source.subscribe(operate({
            destination,
            next: (value) => {
                lastValue = value;
                activeTask?.unsubscribe();
                activeTask = executeSchedule(destination, scheduler, () => {
                    activeTask = undefined;
                    const v = lastValue;
                    lastValue = null;
                    destination.next(v);
                }, dueTime);
            },
            complete: () => {
                if (activeTask) {
                    destination.next(lastValue);
                }
                destination.complete();
            },
            finalize: () => {
                lastValue = activeTask = null;
            },
        }));
    });
}
//# sourceMappingURL=debounceTime.js.map
import { Observable } from '../Observable.js';
import { executeSchedule } from '../util/executeSchedule.js';
export function scheduleArray(input, scheduler) {
    return new Observable((subscriber) => {
        let i = 0;
        const emit = () => {
            if (i === input.length) {
                subscriber.complete();
            }
            else {
                subscriber.next(input[i++]);
                executeSchedule(subscriber, scheduler, emit);
            }
        };
        return executeSchedule(subscriber, scheduler, emit);
    });
}
//# sourceMappingURL=scheduleArray.js.map
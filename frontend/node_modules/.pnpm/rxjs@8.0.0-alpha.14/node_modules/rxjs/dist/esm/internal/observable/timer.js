import { Observable } from '../Observable.js';
import { asyncScheduler } from '../scheduler/async.js';
import { isScheduler } from '../util/isScheduler.js';
import { isValidDate } from '../util/isDate.js';
import { executeSchedule } from '../util/executeSchedule.js';
export function timer(dueTime = 0, intervalOrScheduler, scheduler = asyncScheduler) {
    let intervalDuration = -1;
    if (intervalOrScheduler != null) {
        if (isScheduler(intervalOrScheduler)) {
            scheduler = intervalOrScheduler;
        }
        else {
            intervalDuration = intervalOrScheduler;
        }
    }
    return new Observable((subscriber) => {
        let due = isValidDate(dueTime) ? +dueTime - scheduler.now() : dueTime;
        if (due < 0) {
            due = 0;
        }
        let n = 0;
        return executeSchedule(subscriber, scheduler, () => {
            subscriber.next(n++);
            if (0 <= intervalDuration) {
                executeSchedule(subscriber, scheduler, () => {
                    subscriber.next(n++);
                }, intervalDuration, true);
            }
            else {
                subscriber.complete();
            }
        }, due);
    });
}
//# sourceMappingURL=timer.js.map
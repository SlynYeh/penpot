"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.not = void 0;
function not(pred, thisArg) {
    return (value, index) => !pred.call(thisArg, value, index);
}
exports.not = not;
//# sourceMappingURL=not.js.map
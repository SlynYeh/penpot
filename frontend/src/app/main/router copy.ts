// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS INC

import * as dm from '../common/data/macros.js';
import * as u from '../common/uri.js';
import * as cf from '../config.js';
import * as ev from './data/event.js';
import * as bhistory from '../util/browser_history.js';
import * as dom from '../util/dom/index.js';
import * as globals from '../util/globals/index.js';
import * as ts from '../util/timers/index.js';
import { Observable } from '../vendor/beicon/v2/index.js';
import { str as strLib } from '../vendor/cuerdas/index.js';
import { PotokV2 } from '../vendor/potok/v2/index.js';
import * as r from '../vendor/reitit/core/index.js';

// --- Router API

export const mapToMatch = (data: any) => {
  return r.mapToMatch(data);
};

export const resolve = (router: any, id: string, params: any = {}) => {
  if (!router) return undefined;

  const match = r.matchByName(router, id);
  if (match) {
    return r.matchToPath(match, params);
  }
  return undefined;
};

export const createRouter = (routes: any[]) => {
  return r.router(routes);
};

export const initializeRouter = (routes: any[]) => {
  return PotokV2.reify('initialize-router', {
    update(state: any) {
      return {
        ...state,
        router: createRouter(routes)
      };
    }
  });
};

export const encodeUrl = (url: string) => {
  return encodeURIComponent(url);
};

export const match = (router: any, path: string) => {
  const uri = u.uri(path);
  const match = r.matchByPath(router, uri.path);

  if (match) {
    const queryParams = u.queryStringToMap(uri.query);
    const params = {
      path: match.pathParams,
      query: queryParams
    };

    return {
      ...match,
      params,
      queryParams
    };
  }

  return undefined;
};

// --- Navigate (Event)

export const navigated = (match: any, sendEventInfo: boolean) => {
  return PotokV2.reify('navigated', {
    deref() {
      return match;
    },

    watch(_state: any, _prevState: any, _dispatch: any) {
      if (sendEventInfo) {
        const route = dm.getIn(match, ['data', 'name']);
        const params = match.queryParams;

        return Observable.of(ev.event({
          ...params,
          __name: 'navigate',
          route: String(route)
        }));
      }

      return Observable.empty();
    },

    update(state: any) {
      return {
        ...state,
        route: match,
        exception: undefined
      };
    }
  });
};

export const navigate = (id: string, params: any, options: any = {}) => {
  return PotokV2.reify('navigate', {
    deref() {
      return { id, params, options };
    },

    effect(_state: any, _prevState: any, _dispatch: any) {
      const router = state.router;
      const history = state.history;
      const path = resolve(router, id, params);

      if (options.newWindow) {
        const name = options.windowName || '_blank';
        const uri = { ...cf.publicUri, pathname: path };
        dom.openNewWindow(uri, name, null);
      } else {
        ts.asap(() => {
          if (options.replace) {
            bhistory.replaceToken(history, path);
          } else {
            bhistory.setToken(history, path);
          }
        });
      }
    }
  });
};

export const assignException = (error: any) => {
  return PotokV2.reify('assign-exception', {
    update(state: any) {
      if (error == null) {
        return { ...state, exception: undefined };
      }
      return { ...state, exception: error };
    }
  });
};

export const nav = (id: string, params?: any, options?: any) => {
  return navigate(id, params, options);
};

export const lookupName = (state: any) => {
  return dm.getIn(state, ['route', 'data', 'name']);
};

// FIXME: rename to lookup-params
export const getParams = (state: any) => {
  return dm.getIn(state, ['route', 'params', 'query']);
};

export const getQueryParam = (params: any, key: string) => {
  const value = params[key];
  return Array.isArray(value) ? value[value.length - 1] : value;
};

export const navBack = () => {
  return PotokV2.reify('nav-back', {
    effect(_state: any, _prevState: any, _dispatch: any) {
      ts.asap(dom.browserBack);
    }
  });
};

export const navBackLocal = () => {
  const location = document.location;
  const referrer = u.uri(document.referrer);

  if (!referrer.host || location.hostname === referrer.host) {
    navBack();
  }
};

export const navRoot = () => {
  return PotokV2.reify('nav-root', {
    effect(_state: any, _prevState: any, _dispatch: any) {
      location.href = '/';
    }
  });
};

export const reload = (force: boolean) => {
  return PotokV2.reify('reload', {
    effect(_state: any, _prevState: any, _dispatch: any) {
      ts.asap(() => dom.reloadCurrentWindow(force));
    }
  });
};

export const navRaw = (options: { href?: string; uri?: string } = {}) => {
  return PotokV2.reify('nav-raw', {
    effect(_state: any, _prevState: any, _dispatch: any) {
      if (options.uri) {
        location.replace(options.uri);
      } else if (options.href) {
        location.href = options.href;
      }
    }
  });
};

export const getCurrentHref = () => {
  return location.href;
};

export const getCurrentPath = () => {
  return location.pathname + location.search;
};

// --- History API

// Check the urls to see if we need to send the navigated event.
// If two paths are the same we only send the event when there is a
// change in the parameters `file-id`, `page-id` or `team-id`
const sendEventInfo = (oldUrl: string, newUrl: string) => {
  const params = ['file-id', 'page-id', 'team-id'];
  const newUri = u.uri(newUrl);
  const newPath = newUri.path;
  const newParams = Object.fromEntries(
    Object.entries(u.queryStringToMap(newUri.query))
      .filter(([key]) => params.includes(key))
  );
  const oldUri = u.uri(oldUrl);
  const oldPath = oldUri.path;
  const oldParams = Object.fromEntries(
    Object.entries(u.queryStringToMap(oldUri.query))
      .filter(([key]) => params.includes(key))
  );

  return newPath !== oldPath || JSON.stringify(newParams) !== JSON.stringify(oldParams);
};

export const initializeHistory = (onChange: (router: any, url: string, firstLoad: boolean) => void) => {
  return PotokV2.reify('initialize-history', {
    update(state: any) {
      const history = bhistory.create();
      bhistory.enable(history);
      return {
        ...state,
        history
      };
    },

    effect(state: any, _prevState: any, stream: Observable<any>) {
      const stopper = stream.pipe(PotokV2.filter('initialize-history'));
      const history = state.history;
      const router = state.router;

      ts.schedule(() => onChange(router, history.getToken(), true));

      const source = Observable.concat(
        Observable.of(null, null),
        new Observable(subscriber => {
          const key = bhistory.listen(history, 'navigate', (event: any) => {
            subscriber.next(event.token);
          });

          return () => {
            bhistory.disable(history);
            bhistory.unlistenByKey(key);
          };
        })
      )
        .pipe(
          bufferCount(2, 1),
          takeUntil(stopper),
          tap(([oldUrl, newUrl]) => {
            if (newUrl != null) {
              const shouldSend = oldUrl == null || sendEventInfo(oldUrl, newUrl);
              onChange(router, newUrl, shouldSend);
            }
          })
        );
    }
  });
};
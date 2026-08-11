/**
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) KALEIDOS INC Sucursal en España SL
 */

"use strict";

goog.provide("app.util.browser_history");
goog.require("goog.history.Html5History");

goog.scope(function() {
  const self = app.util.browser_history;
  const Html5History = goog.history.Html5History;

  class TokenTransformer {
    retrieveToken(pathPrefix, location) {
      const path = location.pathname + location.search;
      if (pathPrefix !== "/" && path.startsWith(pathPrefix)) {
        return path.slice(pathPrefix.length - 1);
      }
      return path;
    }

    createUrl(token, pathPrefix, location) {
      if (pathPrefix !== "/") {
        return pathPrefix.replace(/\/$/, "") + token;
      }
      return token;
    }
  }

  self.create = function() {
    const basePath = globalThis["penpotBasePath"] || "/";
    const instance = new Html5History(null, new TokenTransformer());
    instance.setUseFragment(false);
    instance.setPathPrefix(basePath);
    return instance;
  };

  self.enable_BANG_ = function(instance) {
    instance.setEnabled(true);
  };

  self.disable_BANG_ = function(instance) {
    instance.setEnabled(false);
  };

  self.set_token_BANG_ = function(instance, token) {
    instance?.setToken(token);
  }

  self.replace_token_BANG_ = function(instance, token) {
    instance?.replaceToken(token);
  }
});

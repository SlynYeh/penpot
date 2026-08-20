// Runtime configuration for Penpot frontend
// This file is served from the static resources and can be overridden per deployment
// Frontend configuration
// var penpotGridHelpURI = "";
//var penpotPluginsListURI = "";
//var penpotHelpCenterURI = "";
//var penpotLearningCenterURI = "";
//var penpotHubURI = "";

(function () {
  // 直接读取 iframe 自身的完整地址（src 属性的值）
  var _iframeSrc = window.location.href;

  // 拦截 fetch
  var _origFetch = window.fetch;
  window.fetch = function (input, init) {
    init = init || {};
    init.headers = init.headers || {};
    if (_iframeSrc) {
      if (init.headers instanceof Headers) {
        init.headers.set("x-iframe-src", _iframeSrc);
      } else if (typeof init.headers === "object") {
        init.headers["x-iframe-src"] = _iframeSrc;
      }
    }
    return _origFetch.call(this, input, init);
  };

  // 拦截 XMLHttpRequest
  var _origOpen = XMLHttpRequest.prototype.open;
  var _origSend = XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.open = function () {
    this._penpotArgs = arguments;
    return _origOpen.apply(this, arguments);
  };
  XMLHttpRequest.prototype.send = function () {
    if (_iframeSrc && this._penpotArgs) {
      try { this.setRequestHeader("x-iframe-src", _iframeSrc); } catch (e) { }
    }
    return _origSend.apply(this, arguments);
  };

  // SPA 路由变化时同步更新（Penpot 使用 history API）
  var _origPushState = history.pushState;
  var _origReplaceState = history.replaceState;
  history.pushState = function () {
    _origPushState.apply(this, arguments);
    _iframeSrc = window.location.href;
  };
  history.replaceState = function () {
    _origReplaceState.apply(this, arguments);
    _iframeSrc = window.location.href;
  };
  window.addEventListener("popstate", function () {
    _iframeSrc = window.location.href;
  });
})();

(function () {
  var blockedPaths = ['/auth/login', '/auth/register',];
  function isBlocked(path) {
    return blockedPaths.some(function (bp) { return path.indexOf(bp) !== -1; });
  }
  function blockAuthPages() {
    var path = window.location.hash ? window.location.hash.replace('#', '') : window.location.pathname;
    if (isBlocked(path)) {
      document.body.innerHTML = '<div style="display:flex;align-items:center;justify-content:center;height:100vh;font-family:sans-serif;color:#333;font-size:1.2rem;">此页面已被禁用</div>';
    }
  }
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', blockAuthPages);
  } else {
    blockAuthPages();
  }
  var origPushState = history.pushState;
  history.pushState = function () {
    origPushState.apply(this, arguments);
    blockAuthPages();
  };
  window.addEventListener('popstate', blockAuthPages);
  var origReplaceState = history.replaceState;
  history.replaceState = function () {
    origReplaceState.apply(this, arguments);
    blockAuthPages();
  };
  document.addEventListener('dblclick', function(event) {
    window.parent.postMessage({
     type: 'iframe-dblclick',
     data: {}
    }, '*')
  })
})();

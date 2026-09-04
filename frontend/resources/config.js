// Runtime configuration for Penpot frontend
// This file is served from the static resources and can be overridden per deployment
// Frontend configuration

// 从指定共享库拖入组件时，自动解绑实例（列表为库文件 file-id）
window.penpotAutoUnbindLibraryIds = ["caf3ed7a-ac34-8165-8008-1fb0a074f9a9"];

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
  // 右键菜单「插入表格行/列」生效的表格主组件 ID 集合(小写 uuid 字符串)。
  // 实例根 shape 的 :component-id 命中任一 ID 即显示菜单。
  // Docker 部署: 可用环境变量 PENPOT_TABLE_COMPONENT_IDS 覆盖此默认值
  // (逗号分隔, 由 nginx-entrypoint.sh 在启动时追加赋值, 优先级更高)。
  globalThis.penpotTableComponentIds = [
    "5140cbc1-cb3a-803f-8008-8977ae7bee03", // 1 基础组件 / Table 表格 (flex table 库)
  ];
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

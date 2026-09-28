#!/usr/bin/env bash

is_truthy() {
  local value="${1,,}"
  [[ "$value" == "true" || "$value" == "t" || "$value" == "1" ]]
}

is_falsy() {
  local value="${1,,}"
  [[ "$value" == "false" || "$value" == "f" || "$value" == "0" ]]
}

js_escape() {
  # Escape $1 so it can be embedded verbatim inside a double quoted JS string
  # literal (the JSON.parse payloads emitted below). Backslash must be escaped
  # first or the escapes added after it would be double-escaped. Raw newlines
  # become the two-char \n, so the emitted assignment always stays on a single
  # line and command substitution cannot strip them.
  local s=$1
  s=${s//\\/\\\\}
  s=${s//\"/\\\"}
  s=${s//$'\n'/\\n}
  s=${s//$'\r'/\\r}
  s=${s//$'\t'/\\t}
  printf '%s' "$s"
}


#########################################
## Air Gapped config
#########################################

if [[ $PENPOT_FLAGS == *"enable-air-gapped-conf"* ]]; then
    rm /etc/nginx/overrides/location.d/external-locations.conf;
    export PENPOT_FLAGS="$PENPOT_FLAGS disable-google-fonts-provider disable-dashboard-templates-section"
fi

#########################################
## App Frontend config
#########################################

update_flags() {
  if [ -n "$PENPOT_FLAGS" ]; then
    echo "$(sed \
      -e "s|^//var penpotFlags = .*;|var penpotFlags = \"$PENPOT_FLAGS\";|g" \
      "$1")" > "$1"
  fi

  if [ -n "$PENPOT_PUBLIC_URI" ]; then
      echo "var penpotPublicURI = \"$PENPOT_PUBLIC_URI\";" >> "$1";
  fi
}

update_oidc_name() {
  if [ -n "$PENPOT_OIDC_NAME" ]; then
    echo "$(sed \
      -e "s|^//var penpotOIDCName = .*;|var penpotOIDCName = \"$PENPOT_OIDC_NAME\";|g" \
      "$1")" > "$1"
  fi
}

update_help_uris() {
  local pair key envvar value
  for pair in "penpotGridHelpURI:PENPOT_GRID_HELP_URI" \
              "penpotPluginsListURI:PENPOT_PLUGINS_LIST_URI" \
              "penpotHelpCenterURI:PENPOT_HELP_CENTER_URI" \
              "penpotLearningCenterURI:PENPOT_LEARNING_CENTER_URI" \
              "penpotFaqURI:PENPOT_FAQ_URI" \
              "penpotHubURI:PENPOT_HUB_URI"; do
    key="${pair%%:*}"
    envvar="${pair##*:}"
    value="${!envvar}"
    if [ -n "$value" ]; then
      value="${value//&/\\&}"
      echo "$(sed \
        -e "s|^//var $key = .*;|var $key = \"$value\";|g" \
        "$1")" > "$1"
    fi
  done
}

update_table_component_ids() {
  # Overrides the fork default table component ids from config.js with the
  # comma separated UUID list in $PENPOT_TABLE_COMPONENT_IDS (highest
  # priority: appended assignments run last, after the IIFE defaults).
  # The special value "none" (case insensitive) disables the feature.
  if [ -n "$PENPOT_TABLE_COMPONENT_IDS" ]; then
    local raw="${PENPOT_TABLE_COMPONENT_IDS,,}"

    if [ "$raw" == "none" ]; then
      echo "globalThis.penpotTableComponentIds = [];" >> "$1";
      return;
    fi

    local item quoted="";
    local IFS=',';
    read -ra _tcids <<< "$raw";
    for item in "${_tcids[@]}"; do
      # strip all whitespace (tolerate "id1, id2"), then keep only the uuid
      # charset [0-9a-f-]; this guarantees the emitted line is always a
      # valid JS string literal regardless of what ends up in the env var.
      item="${item//[[:space:]]/}";
      item="${item//[^0-9a-f-]/}";
      if [ -n "$item" ]; then
        quoted="${quoted:+$quoted, }\"$item\"";
      fi
    done

    echo "globalThis.penpotTableComponentIds = [$quoted];" >> "$1";
  fi
}

update_auto_unbind_library_ids() {
  # Overrides the fork default auto-unbind library ids from config.js with the
  # comma separated UUID list in $PENPOT_AUTO_UNBIND_LIBRARY_IDS (highest
  # priority: appended assignments run last, after the defaults in the file).
  # The special value "none" (case insensitive) disables the feature.
  # Note: the new handlers below use printf instead of echo so that a payload
  # starting with "-" or containing backslashes is emitted verbatim.
  if [ -n "${PENPOT_AUTO_UNBIND_LIBRARY_IDS:-}" ]; then
    local raw="${PENPOT_AUTO_UNBIND_LIBRARY_IDS,,}"
    # strip whitespace BEFORE the sentinel check so that " none " works too
    raw="${raw//[[:space:]]/}";

    if [ "$raw" == "none" ]; then
      printf 'globalThis.penpotAutoUnbindLibraryIds = [];\n' >> "$1";
      return;
    fi

    local item quoted="";
    local -a ids;
    local IFS=',';
    read -ra ids <<< "$raw";
    for item in "${ids[@]}"; do
      # strip all whitespace (tolerate "id1, id2"), then keep only the uuid
      # charset [0-9a-f-]; this guarantees the emitted line is always a
      # valid JS string literal regardless of what ends up in the env var.
      item="${item//[^0-9a-f-]/}";
      if [ -n "$item" ]; then
        quoted="${quoted:+$quoted, }\"$item\"";
      fi
    done

    printf 'globalThis.penpotAutoUnbindLibraryIds = [%s];\n' "$quoted" >> "$1";
  fi
}

update_default_expanded_asset_groups() {
  # Overrides the fork default expanded asset groups from config.js with the
  # JSON array in $PENPOT_DEFAULT_EXPANDED_ASSET_GROUPS (highest priority:
  # appended assignments run last, after the defaults in the file).
  #
  # The value is NOT validated here (the image no longer ships jq): js_escape
  # guarantees it can never break the js/config.js syntax, and it is parsed in
  # the browser via JSON.parse. On a parse error the wrapper keeps the default
  # already assigned by the IIFE above in config.js and warns in the browser
  # console (not in docker logs); the try/catch also keeps a bad value from
  # aborting the override lines appended after this one. Parseable but
  # malformed entries (wrong key types, missing libraryId/groups) are
  # silently dropped by app.config (config.cljs).
  if [ -n "${PENPOT_DEFAULT_EXPANDED_ASSET_GROUPS:-}" ]; then
    local payload;
    payload=$(js_escape "${PENPOT_DEFAULT_EXPANDED_ASSET_GROUPS}");
    printf 'globalThis.penpotDefaultExpandedAssetGroups = (function (v) { try { return JSON.parse(v); } catch (e) { console.warn("penpot: PENPOT_DEFAULT_EXPANDED_ASSET_GROUPS is not valid JSON; keeping the js/config.js default"); return globalThis.penpotDefaultExpandedAssetGroups; } })("%s");\n' "$payload" >> "$1";
  fi
}

update_default_palette_library() {
  # Overrides the fork default palette library from config.js with the single
  # uuid in $PENPOT_DEFAULT_PALETTE_LIBRARY (highest priority: appended
  # assignments run last, after the defaults in the file).
  # The special value "none" (case insensitive) sets it to "" so that the
  # palette falls back to 最近颜色 (uuid/coerce "" -> nil).
  if [ -n "${PENPOT_DEFAULT_PALETTE_LIBRARY:-}" ]; then
    local raw="${PENPOT_DEFAULT_PALETTE_LIBRARY,,}"
    # strip whitespace BEFORE the sentinel check so that " none " works too
    raw="${raw//[[:space:]]/}";

    if [ "$raw" == "none" ]; then
      printf 'globalThis.penpotDefaultPaletteLibrary = "";\n' >> "$1";
      return;
    fi

    raw="${raw//[^0-9a-f-]/}";
    if [[ "$raw" =~ ^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$ ]]; then
      printf 'globalThis.penpotDefaultPaletteLibrary = "%s";\n' "$raw" >> "$1";
    else
      echo "nginx-entrypoint: PENPOT_DEFAULT_PALETTE_LIBRARY is not a uuid; keeping the js/config.js default" >&2;
    fi
  fi
}

update_hide_tokens() {
  # 是否隐藏变量(Tokens) UI (左侧面板 Tab / 取色器变量区块 / 右侧变量列表按钮)。
  # 未设置时保留 js/config.js 的默认值(隐藏)。
  # 注意 true 与 false 都必须写入: 若只在 truthy 时赋值, PENPOT_HIDE_TOKENS=false
  # 会变成空操作, 部署里就永远无法把变量 UI 恢复出来。
  if [ -z "${PENPOT_HIDE_TOKENS:-}" ]; then
    return;
  fi

  local raw="${PENPOT_HIDE_TOKENS}";
  # strip whitespace so that " true " works too (is_truthy/is_falsy also lowercase)
  raw="${raw//[[:space:]]/}";

  if is_truthy "$raw"; then
    printf 'globalThis.penpotHideTokens = true;\n' >> "$1";
  elif is_falsy "$raw"; then
    printf 'globalThis.penpotHideTokens = false;\n' >> "$1";
  else
    echo "nginx-entrypoint: PENPOT_HIDE_TOKENS must be a boolean (true/false/1/0/t/f); keeping the js/config.js default" >&2;
  fi
}

update_embed_parent_origin() {
  # Origin of the third party system embedding the app in an iframe, used
  # both as the postMessage target origin and to validate the origin of the
  # credentials the parent sends back. Overrides the js/config.js default.
  # The special value "none" (case insensitive) clears it, falling back to
  # a wildcard target origin that accepts credentials from any window.
  if [ -z "${PENPOT_EMBED_PARENT_ORIGIN:-}" ]; then
    return;
  fi

  local raw="${PENPOT_EMBED_PARENT_ORIGIN}";
  # strip whitespace so that " none " works too
  raw="${raw//[[:space:]]/}";

  if [ "${raw,,}" == "none" ]; then
    printf 'globalThis.penpotEmbedParentOrigin = "";\n' >> "$1";
    return;
  fi

  # The value is interpolated into a double quoted JS string, so a quote, a
  # backslash or a newline would break the whole js/config.js file (every
  # other global in it stops being assigned). Only accept a bare origin,
  # optionally with a trailing slash.
  if [[ ! "$raw" =~ ^https?://[A-Za-z0-9._:-]+/?$ ]]; then
    echo "nginx-entrypoint: PENPOT_EMBED_PARENT_ORIGIN must be an origin like https://portal.example.com; keeping the js/config.js default" >&2;
    return;
  fi

  printf 'globalThis.penpotEmbedParentOrigin = "%s";\n' "$raw" >> "$1";
}

update_frame_ancestors() {
  # Let the third party system that embeds the app actually frame it. The
  # bundled `add_header X-Frame-Options SAMEORIGIN` makes the browser refuse
  # cross origin framing, which no amount of frontend handshake can work
  # around, so when a parent origin is configured the X-Frame-Options line is
  # swapped for the equivalent CSP directive naming that origin.
  #
  # Only an explicit origin opens this up: unset, empty and "none" keep the
  # SAMEORIGIN default, and a wildcard is not expressible on purpose (it would
  # let any site frame the app). `'self'` stays in the list because the app
  # frames itself (rasterizer, render, viewer).
  local raw="${PENPOT_EMBED_PARENT_ORIGIN:-}";
  raw="${raw//[[:space:]]/}";

  if [ -z "$raw" ] || [ "${raw,,}" == "none" ]; then
    return;
  fi

  # Same validation as update_embed_parent_origin: only a bare origin, since
  # the value is interpolated into the nginx config and anything able to break
  # out of the quoted string would be a config injection.
  if [[ ! "$raw" =~ ^https?://[A-Za-z0-9._:-]+/?$ ]]; then
    echo "nginx-entrypoint: PENPOT_EMBED_PARENT_ORIGIN must be an origin like https://portal.example.com; keeping X-Frame-Options: SAMEORIGIN" >&2;
    return;
  fi

  local origin="${raw%/}";
  local policy="add_header Content-Security-Policy \"frame-ancestors 'self' ${origin}\" always;";

  # Rewritten with grep + mv rather than `sed -i`: the in-place flag is not
  # portable across the busybox/BSD/GNU seds this script may run under.
  grep -v '^add_header X-Frame-Options' "$1" > "$1.tmp" || true;
  printf '%s\n' "$policy" >> "$1.tmp";
  mv "$1.tmp" "$1";
}

update_show_beginner_guide() {
  # 是否在首次进入 workspace 时弹出「新手基础操作」, 以及帮助中心是否展示
  # 「新手引导视频」。未设置时保留 js/config.js 的默认值(开启)。
  # 注意 true 与 false 都必须写入: 若只在 truthy 时赋值, PENPOT_SHOW_BEGINNER_GUIDE=false
  # 会变成空操作, 部署里就永远无法关掉弹窗。
  if [ -z "${PENPOT_SHOW_BEGINNER_GUIDE:-}" ]; then
    return;
  fi

  local raw="${PENPOT_SHOW_BEGINNER_GUIDE}";
  raw="${raw//[[:space:]]/}";

  if is_truthy "$raw"; then
    printf 'globalThis.penpotShowBeginnerGuide = true;\n' >> "$1";
  elif is_falsy "$raw"; then
    printf 'globalThis.penpotShowBeginnerGuide = false;\n' >> "$1";
  else
    echo "nginx-entrypoint: PENPOT_SHOW_BEGINNER_GUIDE must be a boolean (true/false/1/0/t/f); keeping the js/config.js default" >&2;
  fi
}

update_beginner_guide_videos() {
  # Overrides the fork default beginner-guide video URLs from config.js with
  # the JSON object in $PENPOT_BEGINNER_GUIDE_VIDEOS (highest priority:
  # appended assignments run last, after the defaults in the file).
  #
  # Not validated in shell either (same policy as
  # update_default_expanded_asset_groups above): escaped payload, parsed in
  # the browser via JSON.parse, parse errors keep the config.js default with
  # a browser-console warning, non-string entries are dropped by app.config.
  if [ -n "${PENPOT_BEGINNER_GUIDE_VIDEOS:-}" ]; then
    local payload;
    payload=$(js_escape "${PENPOT_BEGINNER_GUIDE_VIDEOS}");
    printf 'globalThis.penpotBeginnerGuideVideos = (function (v) { try { return JSON.parse(v); } catch (e) { console.warn("penpot: PENPOT_BEGINNER_GUIDE_VIDEOS is not valid JSON; keeping the js/config.js default"); return globalThis.penpotBeginnerGuideVideos; } })("%s");\n' "$payload" >> "$1";
  fi
}

update_flags /var/www/app/js/config.js
update_oidc_name /var/www/app/js/config.js
update_help_uris /var/www/app/js/config.js
update_table_component_ids /var/www/app/js/config.js
update_auto_unbind_library_ids /var/www/app/js/config.js
update_default_expanded_asset_groups /var/www/app/js/config.js
update_default_palette_library /var/www/app/js/config.js
update_hide_tokens /var/www/app/js/config.js
update_embed_parent_origin /var/www/app/js/config.js
update_show_beginner_guide /var/www/app/js/config.js
update_beginner_guide_videos /var/www/app/js/config.js

#########################################
## Nginx Config
#########################################

export PENPOT_BACKEND_URI=${PENPOT_BACKEND_URI:-http://penpot-backend:6060}
export PENPOT_EXPORTER_URI=${PENPOT_EXPORTER_URI:-http://penpot-exporter:6061}
export PENPOT_NITRATE_URI=${PENPOT_NITRATE_URI:-http://penpot-nitrate:3000}
export PENPOT_HTTP_SERVER_MAX_BODY_SIZE=${PENPOT_HTTP_SERVER_MAX_BODY_SIZE:-367001600} # Default to 350MiB
export PENPOT_IPV6_LISTEN_DIRECTIVE=${PENPOT_IPV6_LISTEN_DIRECTIVE:-"listen [::]:8080 default_server reuseport backlog=16384;"}
if is_truthy "${PENPOT_DISABLE_IPV6_LISTEN:-}"; then
  export PENPOT_IPV6_LISTEN_DIRECTIVE=""
fi
envsubst "\$PENPOT_BACKEND_URI,\$PENPOT_EXPORTER_URI,\$PENPOT_NITRATE_URI,\$PENPOT_HTTP_SERVER_MAX_BODY_SIZE,\$PENPOT_IPV6_LISTEN_DIRECTIVE" \
        < /tmp/nginx.conf.template > /etc/nginx/nginx.conf

if [[ $PENPOT_FLAGS == *"enable-mcp"* ]]; then
    export PENPOT_MCP_URI=${PENPOT_MCP_URI:-http://penpot-mcp:4401}
    export PENPOT_MCP_URI_WS=${PENPOT_MCP_URI_WS:-http://penpot-mcp:4402}

    envsubst "\$PENPOT_MCP_URI,\$PENPOT_MCP_URI_WS" \
             < /tmp/nginx-mcp-locations.conf.template > /etc/nginx/overrides/server.d/mcp-locations.conf
else
    rm -f /etc/nginx/overrides/server.d/mcp-locations.conf
fi

PENPOT_DEFAULT_INTERNAL_RESOLVER="$(awk 'BEGIN{ORS=" "} $1=="nameserver" { sub(/%.*$/,"",$2); print ($2 ~ ":")? "["$2"]": $2}' /etc/resolv.conf)"
export PENPOT_INTERNAL_RESOLVER=${PENPOT_INTERNAL_RESOLVER:-$PENPOT_DEFAULT_INTERNAL_RESOLVER}
envsubst "\$PENPOT_INTERNAL_RESOLVER" \
         < /tmp/resolvers.conf.template > /etc/nginx/overrides/http.d/resolvers.conf

# Runs after the include file is in place and before nginx starts: the framing
# policy depends on PENPOT_EMBED_PARENT_ORIGIN, which the app frontend config
# above has already been read for.
update_frame_ancestors /etc/nginx/nginx-security-headers.conf

exec "$@";

#!/usr/bin/env bash

is_truthy() {
  local value="${1,,}"
  [[ "$value" == "true" || "$value" == "t" || "$value" == "1" ]]
}

is_falsy() {
  local value="${1,,}"
  [[ "$value" == "false" || "$value" == "f" || "$value" == "0" ]]
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
  # The value is validated with jq before being written. js/config.js is a
  # classic script and app.config builds the expanded-groups value while the
  # namespace is loaded, so a malformed entry (eg. "groups" being a number)
  # throws there and the whole UI fails to boot -- not just this feature.
  # On any invalid input we warn and keep the default from js/config.js, so a
  # missing jq in the image degrades to "default kept" rather than a broken file.
  if [ -n "${PENPOT_DEFAULT_EXPANDED_ASSET_GROUPS:-}" ]; then
    local check='select(type == "array" and all(.[]; type == "object" and (.libraryId | type == "string") and (.groups | type == "array") and all(.groups[]; type == "string")))'
    local value="";

    # select() is required: what follows it is a predicate, so a bare
    # `jq -e "<predicate>"` would emit the boolean `true` and write
    # "x = true;". With select() a rejected value produces no output at all.
    # -e is required as well: without it that empty output still exits 0 and
    # "x = ;" would be emitted -- a syntax error that would break every other
    # global in js/config.js too. -c keeps the output on a single line.
    if ! value=$(printf '%s' "$PENPOT_DEFAULT_EXPANDED_ASSET_GROUPS" | jq -ec "$check" 2>/dev/null) \
       || [ -z "$value" ]; then
      echo "nginx-entrypoint: PENPOT_DEFAULT_EXPANDED_ASSET_GROUPS must be a JSON array of {libraryId: string, groups: [string]}; keeping the js/config.js default" >&2;
      return;
    fi

    printf 'globalThis.penpotDefaultExpandedAssetGroups = %s;\n' "$value" >> "$1";
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

update_flags /var/www/app/js/config.js
update_oidc_name /var/www/app/js/config.js
update_help_uris /var/www/app/js/config.js
update_table_component_ids /var/www/app/js/config.js
update_auto_unbind_library_ids /var/www/app/js/config.js
update_default_expanded_asset_groups /var/www/app/js/config.js
update_default_palette_library /var/www/app/js/config.js

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

exec "$@";

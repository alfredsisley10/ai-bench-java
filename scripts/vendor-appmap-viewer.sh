#!/usr/bin/env bash
#
# Vendors the official AppMap viewer (`@appland/components`) into
# bench-webui's static resources so the trace-viewer page can serve
# the same Vue.js viewer shipped by the VS Code extension and the
# Confluence app.
#
# What it does:
#   1. Pulls @appland/components from npm and runs it through esbuild
#      to produce a single browser-ready IIFE bundle that includes
#      all 15+ peer deps (Vue, Vuex, marked, mermaid, sql-formatter,
#      highlight.js, dom-to-svg, dompurify, pako, sax, diff, etc.).
#   2. Drops the bundle at:
#        bench-webui/src/main/resources/static/appmap/components.iife.js
#      The viewer template auto-detects this file and loads the
#      official viewer when present, falling back to the server-side
#      call-tree renderer otherwise.
#
# Run from the repo root. Re-run any time you want to upgrade the
# vendored viewer.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TARGET_DIR="$REPO_ROOT/bench-webui/src/main/resources/static/appmap"
WORK_DIR="$(mktemp -d -t appmap-vendor-XXXX)"
trap 'rm -rf "$WORK_DIR"' EXIT

echo "==> staging in $WORK_DIR"
cd "$WORK_DIR"

cat > package.json <<'JSON'
{
  "name": "appmap-viewer-bundle",
  "version": "0.0.0",
  "private": true,
  "type": "module",
  "dependencies": {
    "@appland/components": "*",
    "@appland/models": "*",
    "@appland/openapi": "*",
    "vue": "^2.7.16"
  },
  "devDependencies": {
    "esbuild": "*"
  }
}
JSON

cat > entry.js <<'JS'
// Bundle the official viewer + Vue 2 runtime + the models layer as a
// single IIFE. AppMap's components are Vue 2 components that consume
// a fully parsed AppMap object — built via buildAppMap() from
// @appland/models — not the raw .appmap.json. Re-export everything
// under one global so the host page can do:
//   const a = AppMapComponents.buildAppMap().source(json).normalize().build();
//   new AppMapComponents.Vue({
//     render: h => h(AppMapComponents.VVsCodeExtension, { props: { appMap: a } })
//   });
import Vue from 'vue';
export { Vue };
export * from '@appland/components';
export {
    buildAppMap,
    AppMap as AppMapModel,
    AppMapFilter,
    fullyQualifiedFunctionName,
} from '@appland/models';
JS

# Stub for @appland/client only — it reaches for node's http/https for
# AppMap-cloud calls we don't use. The viewer's trace rendering needs
# none of it.
cat > stub.js <<'JS'
const noop = () => undefined;
const handler = {
  get: (_target, _prop) => noop,
};
export default new Proxy({}, handler);
export const AI = noop;
export const setConfiguration = noop;
export const DefaultApiURL = '';
JS

# Browser shim for node's `url` module so @appland/openapi/rpcRequest.js
# (which does `require('url')` for URL parsing) resolves cleanly. The
# DOM URL constructor is a near drop-in.
cat > url-stub.js <<'JS'
function parse(input) {
  try {
    const u = new URL(input);
    return {
      href: u.href, protocol: u.protocol, hostname: u.hostname, host: u.host,
      port: u.port, pathname: u.pathname, search: u.search, hash: u.hash,
      query: Object.fromEntries(u.searchParams),
    };
  } catch { return { pathname: input || '', query: {} }; }
}
function format(o) { return o && o.href || ''; }
function resolve(base, ref) { try { return new URL(ref, base).href; } catch { return ref; } }
const URLObj = (typeof URL !== 'undefined') ? URL : function () {};
const URLSearchParamsObj = (typeof URLSearchParams !== 'undefined') ? URLSearchParams : function () {};
export default { parse, format, resolve, URL: URLObj, URLSearchParams: URLSearchParamsObj };
export { parse, format, resolve, URLObj as URL, URLSearchParamsObj as URLSearchParams };
JS

# Browser-friendly assert shim so @appland/components' `import ie from "assert"`
# resolves without pulling node's full assert module. Trace rendering does
# not rely on assertions firing in production.
cat > assert-stub.js <<'JS'
function assert(cond, msg) { if (!cond) throw new Error('assert: ' + (msg || 'failed')); }
assert.ok = assert;
assert.fail = (msg) => { throw new Error('assert.fail: ' + (msg || '')); };
assert.equal = (a, b, msg) => assert(a == b, msg);
assert.strictEqual = (a, b, msg) => assert(a === b, msg);
assert.deepEqual = assert.deepStrictEqual = assert.equal;
assert.notEqual = (a, b, msg) => assert(a != b, msg);
assert.notStrictEqual = (a, b, msg) => assert(a !== b, msg);
export default assert;
export { assert };
JS

echo "==> npm install"
npm install --silent

echo "==> bundling with esbuild"
npx esbuild entry.js \
    --bundle \
    --format=iife \
    --global-name=AppMapComponents \
    --minify \
    --target=es2020 \
    --alias:@appland/client="$WORK_DIR/stub.js" \
    --alias:url="$WORK_DIR/url-stub.js" \
    --alias:http="$WORK_DIR/stub.js" \
    --alias:https="$WORK_DIR/stub.js" \
    --alias:assert="$WORK_DIR/assert-stub.js" \
    --define:process.env.NODE_ENV='"production"' \
    --define:global=globalThis \
    --outfile=components.iife.js

mkdir -p "$TARGET_DIR"
mv components.iife.js "$TARGET_DIR/"
echo "==> wrote $TARGET_DIR/components.iife.js ($(wc -c < "$TARGET_DIR/components.iife.js") bytes)"
echo
echo "Done. Restart bench-webui; the trace viewer will pick up the bundle"
echo "automatically and switch from the server-side fallback to the official"
echo "interactive viewer (filter, sequence diagram, drill-down, pan/zoom)."

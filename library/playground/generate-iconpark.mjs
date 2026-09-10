#!/usr/bin/env node
// Generate an IconPark.penpot file (outline + filled) for the Icons sidebar.
//
// From library/:
//   pnpm run build
//   node playground/generate-iconpark.mjs
//
// Optional: ICONPARK_LIMIT=20 to generate a short sample.
//
// Then in Penpot: open a project → ⋯ → 导入文件 → pick playground/IconPark.penpot
// → right-click the file → 新增为共享库 → in a working file, 素材 → 管理库 → 连接共享库.
//
// IconPark SVG paths are authored on a 48x48 viewBox. The board must use that
// same size or the glyph is clipped into a solid square.

import {createRequire} from "node:module";
import {writeFile} from "node:fs/promises";
import path from "node:path";
import {fileURLToPath, pathToFileURL} from "node:url";

import svgpath from "svgpath";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const require = createRequire(import.meta.url);

export const ICON_SIZE = 48;
export const STROKE_WIDTH = 2;
export const GLYPH_COLOR = "#495e74";
const GRID_GAP = 16;
const GRID_COLS = 8;
export const COMPONENT_PATH = "图标 / IconPark";
export const UNCATEGORIZED_CATEGORY = "其它";

// Official Chinese sidebar order from iconpark.oceanengine.com/official.
// Keep in sync with frontend/src/app/main/data/workspace/icons.cljs
export const CATEGORY_ORDER = [
  "基础",
  "安全 & 防护",
  "办公文档",
  "编辑",
  "表情",
  "测量 & 试验",
  "抽象图形",
  "电商财产",
  "动物",
  "多媒体音乐",
  "服饰",
  "符号标识",
  "工业",
  "化妆美妆",
  "几何图形",
  "建筑",
  "箭头方向",
  "交流沟通",
  "交通旅游",
  "界面组件",
  "链接",
  "美颜调整",
  "母婴儿童",
  "能源 & 生命",
  "品牌",
  "生活",
  "时间日期",
  "食品",
  "手势动作",
  "数据",
  "数据图表",
  "体育运动",
  "天气",
  "星座",
  "医疗健康",
  "硬件",
  "用户人名",
  "游戏",
  "其它",
];

const NON_ICON_EXPORTS = new Set([
  "default",
  "IconWrapper",
  "IconConverter",
  "setConfig",
  "getConfig",
]);

export function iconparkComponentPath(categoryCN, theme = "outline") {
  const category = String(categoryCN ?? "").trim() || UNCATEGORIZED_CATEGORY;
  const style = theme === "filled" ? "filled" : "outline";
  return `${COMPONENT_PATH} / ${category} / ${style}`;
}

export function toKebabName(exportName) {
  return exportName
    .replace(/([a-z0-9])([A-Z])/g, "$1-$2")
    .replace(/([A-Z]+)([A-Z][a-z])/g, "$1-$2")
    .toLowerCase();
}

export function listIconExportNames(mod) {
  return Object.keys(mod)
    .filter((key) => typeof mod[key] === "function" && !NON_ICON_EXPORTS.has(key))
    .sort((a, b) => a.localeCompare(b));
}

export function uniqueComponentName(title, name, titleCounts) {
  const label = String(title ?? "").trim() || name;
  if ((titleCounts.get(label) ?? 0) > 1) {
    return `${label} (${name})`;
  }
  return label;
}

export function countTitles(items) {
  const counts = new Map();
  for (const item of items) {
    const label = String(item.title ?? "").trim() || item.name;
    counts.set(label, (counts.get(label) ?? 0) + 1);
  }
  return counts;
}

export function normalizeHex(color) {
  if (color == null || color === "" || color === "none" || color === "currentColor" || color === "transparent") {
    return null;
  }
  const value = String(color).trim();
  if (/^#[0-9a-fA-F]{3}$/.test(value)) {
    return `#${[...value.slice(1)].map((ch) => ch + ch).join("")}`;
  }
  if (/^#[0-9a-fA-F]{6}$/.test(value)) {
    return value;
  }
  return null;
}

export function resolvePaint(color) {
  if (color == null || color === "" || color === "none" || color === "transparent") {
    return null;
  }
  if (color === "currentColor") {
    return GLYPH_COLOR;
  }
  return normalizeHex(color);
}

export function parseAttrs(source) {
  const attrs = {};
  const attrRe = /([:\w-]+)\s*=\s*("([^"]*)"|'([^']*)')/g;
  for (const match of source.matchAll(attrRe)) {
    attrs[match[1]] = match[3] ?? match[4];
  }
  return attrs;
}

export function parseViewBox(svg) {
  const match = String(svg).match(/viewBox\s*=\s*("([^"]*)"|'([^']*)')/i);
  const raw = match?.[2] ?? match?.[3];
  if (raw == null) {
    return {minX: 0, minY: 0, width: ICON_SIZE, height: ICON_SIZE};
  }
  const parts = raw.trim().split(/[\s,]+/).map(Number);
  if (parts.length !== 4 || parts.some((n) => !Number.isFinite(n))) {
    return {minX: 0, minY: 0, width: ICON_SIZE, height: ICON_SIZE};
  }
  const [minX, minY, width, height] = parts;
  return {
    minX,
    minY,
    width: width || ICON_SIZE,
    height: height || ICON_SIZE,
  };
}

export function svgPathToContent(d) {
  const content = [];

  svgpath(d)
    .abs()
    .unshort()
    .unarc()
    .iterate((segment, _index, x, y) => {
      const [cmd, ...args] = segment;
      switch (cmd) {
        case "M":
          content.push({command: "move-to", params: {x: args[0], y: args[1]}});
          break;
        case "L":
          content.push({command: "line-to", params: {x: args[0], y: args[1]}});
          break;
        case "H":
          content.push({command: "line-to", params: {x: args[0], y}});
          break;
        case "V":
          content.push({command: "line-to", params: {x, y: args[0]}});
          break;
        case "C":
          content.push({
            command: "curve-to",
            params: {
              c1x: args[0],
              c1y: args[1],
              c2x: args[2],
              c2y: args[3],
              x: args[4],
              y: args[5],
            },
          });
          break;
        case "Q": {
          const [qx, qy, ex, ey] = args;
          content.push({
            command: "curve-to",
            params: {
              c1x: x + (2 / 3) * (qx - x),
              c1y: y + (2 / 3) * (qy - y),
              c2x: ex + (2 / 3) * (qx - ex),
              c2y: ey + (2 / 3) * (qy - ey),
              x: ex,
              y: ey,
            },
          });
          break;
        }
        case "Z":
          content.push({command: "close-path", params: {}});
          break;
        default:
          throw new Error(`Unsupported SVG path command: ${cmd}`);
      }
    });

  if (content.length === 0) {
    throw new Error("SVG path produced no Penpot content");
  }

  return content;
}

export function translateContent(content, dx, dy) {
  return content.map((segment) => {
    const params = segment.params;
    if (params == null) return segment;
    const next = {...params};
    for (const key of ["x", "y", "c1x", "c1y", "c2x", "c2y"]) {
      if (typeof next[key] === "number") {
        next[key] += key.endsWith("x") ? dx : dy;
      }
    }
    return {...segment, params: next};
  });
}

function scalePathD(d, scale, originX, originY) {
  return svgpath(d)
    .translate(-originX, -originY)
    .scale(scale)
    .toString();
}

function pointsToPathD(points, close) {
  const pairs = String(points)
    .trim()
    .split(/[\s,]+/)
    .map(Number)
    .filter((n) => Number.isFinite(n));
  if (pairs.length < 4) return null;
  const [x0, y0] = pairs;
  let d = `M${x0} ${y0}`;
  for (let i = 2; i < pairs.length; i += 2) {
    d += `L${pairs[i]} ${pairs[i + 1]}`;
  }
  if (close) d += "Z";
  return d;
}

function num(value, fallback = 0) {
  const parsed = Number.parseFloat(value);
  return Number.isFinite(parsed) ? parsed : fallback;
}

function paintFromAttrs(attrs, scale) {
  return {
    fill: resolvePaint(attrs.fill),
    stroke: resolvePaint(attrs.stroke),
    strokeWidth: num(attrs["stroke-width"] ?? attrs.strokeWidth, STROKE_WIDTH) * scale,
    strokeLinecap: attrs["stroke-linecap"] ?? attrs.strokeLinecap ?? "round",
  };
}

const NON_DRAWABLE_SVG = /<(mask|clipPath|defs|title|desc)\b[^>]*>[\s\S]*?<\/\1>/gi;

export function stripNonDrawableSvg(svg) {
  let previous;
  let next = String(svg);
  do {
    previous = next;
    next = previous.replace(NON_DRAWABLE_SVG, "");
  } while (next !== previous);
  return next;
}

function isFullBoardRect(element) {
  return element.tag === "rect"
    && Math.abs(element.x) < 0.51
    && Math.abs(element.y) < 0.51
    && Math.abs(element.width - ICON_SIZE) < 0.51
    && Math.abs(element.height - ICON_SIZE) < 0.51;
}

function isFullBoardFillPath(element) {
  if (element.tag !== "path" || !element.fill || !element.d) {
    return false;
  }
  const d = element.d.replace(/[\s,]+/g, " ").trim();
  return /M\s*48[\s]+0/i.test(d) && /H\s*0/i.test(d) && /V\s*48/i.test(d);
}

function isBoardCover(element) {
  return isFullBoardRect(element) || isFullBoardFillPath(element);
}

export function extractSvgElements(svg) {
  const drawable = stripNonDrawableSvg(svg);
  const viewBox = parseViewBox(drawable);
  const scale = ICON_SIZE / viewBox.width;
  const elements = [];
  const tagRe = /<(path|rect|circle|ellipse|line|polyline|polygon)\b([^>]*?)\/?>/gi;

  const pushDrawable = (element) => {
    if (!isBoardCover(element)) {
      elements.push(element);
    }
  };

  for (const match of drawable.matchAll(tagRe)) {
    const tag = match[1].toLowerCase();
    const attrs = parseAttrs(match[2]);
    const paint = paintFromAttrs(attrs, scale);

    if (paint.fill == null && paint.stroke == null) {
      continue;
    }

    if (tag === "path" && attrs.d) {
      pushDrawable({tag, d: scalePathD(attrs.d, scale, viewBox.minX, viewBox.minY), ...paint});
      continue;
    }

    if (tag === "rect") {
      pushDrawable({
        tag,
        x: (num(attrs.x) - viewBox.minX) * scale,
        y: (num(attrs.y) - viewBox.minY) * scale,
        width: num(attrs.width) * scale,
        height: num(attrs.height) * scale,
        rx: num(attrs.rx ?? attrs.ry) * scale,
        ...paint,
      });
      continue;
    }

    if (tag === "circle" || tag === "ellipse") {
      const cx = (num(attrs.cx) - viewBox.minX) * scale;
      const cy = (num(attrs.cy) - viewBox.minY) * scale;
      const r = num(attrs.r ?? attrs.rx ?? attrs.ry) * scale;
      pushDrawable({tag: "circle", cx, cy, r, ...paint});
      continue;
    }

    if (tag === "line") {
      const x1 = (num(attrs.x1) - viewBox.minX) * scale;
      const y1 = (num(attrs.y1) - viewBox.minY) * scale;
      const x2 = (num(attrs.x2) - viewBox.minX) * scale;
      const y2 = (num(attrs.y2) - viewBox.minY) * scale;
      pushDrawable({tag: "path", d: `M${x1} ${y1}L${x2} ${y2}`, ...paint});
      continue;
    }

    const d = pointsToPathD(attrs.points ?? "", tag === "polygon");
    if (d) {
      pushDrawable({tag: "path", d: scalePathD(d, scale, viewBox.minX, viewBox.minY), ...paint});
    }
  }
  return elements;
}

function outlinePaint(element) {
  const linecap = element.strokeLinecap === "square" ? "square" : "round";
  const shape = {fills: [], strokes: []};

  if (element.fill) {
    shape.fills = [{fillColor: element.fill, fillOpacity: 1}];
  }

  if (element.stroke) {
    shape.strokes = [{
      strokeColor: element.stroke,
      strokeOpacity: 1,
      strokeWidth: element.strokeWidth,
      strokeStyle: "solid",
      strokeAlignment: "center",
      strokeCapStart: linecap,
      strokeCapEnd: linecap,
    }];
  }

  return shape;
}

const SCALE_CONSTRAINTS = {
  constraintsH: "scale",
  constraintsV: "scale",
};

export function elementToPenpotShape(element, name, offsetX, offsetY) {
  const paint = outlinePaint(element);

  if (element.tag === "rect") {
    return {
      type: "rect",
      name,
      x: element.x + offsetX,
      y: element.y + offsetY,
      width: element.width,
      height: element.height,
      r1: element.rx,
      r2: element.rx,
      r3: element.rx,
      r4: element.rx,
      ...SCALE_CONSTRAINTS,
      ...paint,
    };
  }

  if (element.tag === "circle") {
    return {
      type: "circle",
      name,
      x: element.cx - element.r + offsetX,
      y: element.cy - element.r + offsetY,
      width: element.r * 2,
      height: element.r * 2,
      ...SCALE_CONSTRAINTS,
      ...paint,
    };
  }

  return {
    type: "path",
    name,
    content: translateContent(svgPathToContent(element.d), offsetX, offsetY),
    ...SCALE_CONSTRAINTS,
    ...paint,
  };
}

export function loadIconMeta() {
  try {
    const raw = require("@icon-park/svg/icons.json");
    const list = Array.isArray(raw) ? raw : [];
    return new Map(list.map((item) => [item.name, item]));
  } catch {
    return new Map();
  }
}

const ICON_THEMES = ["outline", "filled"];

function renderIconSvg(render, theme) {
  return render({
    theme,
    size: ICON_SIZE,
    strokeWidth: STROKE_WIDTH,
    fill: GLYPH_COLOR,
  });
}

function gridPosition(index) {
  const col = index % GRID_COLS;
  const row = Math.floor(index / GRID_COLS);
  return {
    x: col * (ICON_SIZE + GRID_GAP),
    y: row * (ICON_SIZE + GRID_GAP),
  };
}

function categoryRank(category) {
  const index = CATEGORY_ORDER.indexOf(category);
  return index === -1 ? CATEGORY_ORDER.length : index;
}

export function buildCatalog(IconPark, metaByName, limit) {
  const exports = listIconExportNames(IconPark);
  const items = [];

  for (const exportName of exports) {
    const name = toKebabName(exportName);
    const meta = metaByName.get(name);
    items.push({
      exportName,
      name,
      title: meta?.title || name,
      category: String(meta?.categoryCN ?? "").trim() || UNCATEGORIZED_CATEGORY,
      tag: Array.isArray(meta?.tag) ? meta.tag : [],
    });
  }

  const titleCounts = countTitles(items);
  const catalog = items
    .map((item) => ({
      ...item,
      componentName: uniqueComponentName(item.title, item.name, titleCounts),
    }))
    .sort((a, b) => {
      const rank = categoryRank(a.category) - categoryRank(b.category);
      if (rank !== 0) return rank;
      return a.componentName.localeCompare(b.componentName, "zh");
    });

  if (Number.isFinite(limit) && limit > 0) {
    return catalog.slice(0, limit);
  }
  return catalog;
}

export function searchBlobForItem(item) {
  const parts = [];
  if (item.name) {
    parts.push(item.name);
  }
  if (item.exportName) {
    parts.push(item.exportName);
  }
  for (const tag of item.tag ?? []) {
    if (tag) {
      parts.push(String(tag));
    }
  }
  return parts.join(" ").toLowerCase();
}

export function buildSearchIndex(catalog) {
  const index = {};
  for (const item of catalog) {
    index[item.componentName] = searchBlobForItem(item);
  }
  return index;
}

function cljsString(value) {
  return `"${String(value).replaceAll("\\", "\\\\").replaceAll("\"", "\\\"")}"`;
}

export function renderSearchIndexCljs(index) {
  const entries = Object.entries(index)
    .sort(([left], [right]) => left.localeCompare(right, "zh"))
    .map(([name, blob]) => `   ${cljsString(name)} ${cljsString(blob)}`);
  return `;; AUTO-GENERATED by library/playground/generate-iconpark.mjs. Do not edit.

(ns app.main.data.workspace.iconpark-search)

(def index
  {
${entries.join("\n")}})
`;
}

export const SEARCH_INDEX_CLJS = path.resolve(
  __dirname,
  "../../frontend/src/app/main/data/workspace/iconpark_search.cljs",
);

export async function writeSearchIndexCljs(catalog) {
  await writeFile(SEARCH_INDEX_CLJS, renderSearchIndexCljs(buildSearchIndex(catalog)));
}

function addIconShape(context, element, name, offsetX, offsetY) {
  const shape = elementToPenpotShape(element, name, offsetX, offsetY);
  if (shape.type === "rect") {
    context.addRect(shape);
  } else if (shape.type === "circle") {
    context.addCircle(shape);
  } else {
    context.addPath(shape);
  }
}

async function writeSearchIndexOnly() {
  const IconPark = await import("@icon-park/svg");
  const catalog = buildCatalog(IconPark, loadIconMeta());
  await writeSearchIndexCljs(catalog);
  console.log(`Wrote search index (${catalog.length} icons) to ${SEARCH_INDEX_CLJS}`);
}

async function main() {
  if (process.argv.includes("--search-index-only")) {
    await writeSearchIndexOnly();
    return;
  }

  const [IconPark, penpot] = await Promise.all([
    import("@icon-park/svg"),
    import("#self"),
  ]);

  const limit = Number.parseInt(process.env.ICONPARK_LIMIT ?? "", 10);
  const outFile = path.resolve(__dirname, "IconPark.penpot");
  const metaByName = loadIconMeta();
  const catalog = buildCatalog(IconPark, metaByName, limit);
  await writeSearchIndexCljs(catalog);
  const context = penpot.createBuildContext({referer: "iconpark-generator"});

  context.addFile({name: "IconPark"});

  let index = 0;
  let skipped = 0;
  const byCategory = new Map();
  for (const item of catalog) {
    const list = byCategory.get(item.category) ?? [];
    list.push(item);
    byCategory.set(item.category, list);
  }

  const categories = [
    ...CATEGORY_ORDER.filter((category) => byCategory.has(category)),
    ...[...byCategory.keys()].filter((category) => !CATEGORY_ORDER.includes(category)),
  ];

  for (const theme of ICON_THEMES) {
    for (const category of categories) {
      const icons = byCategory.get(category) ?? [];
      if (icons.length === 0) continue;

      context.addPage({name: `${category} / ${theme}`});

      icons.forEach((item, pageIndex) => {
        try {
          const svg = renderIconSvg(IconPark[item.exportName], theme);
          const svgElements = extractSvgElements(svg);
          if (svgElements.length === 0) {
            throw new Error("SVG had no drawable elements");
          }

          const {x, y} = gridPosition(pageIndex);
          const componentId = context.genId();
          const frameId = context.addBoard({
            name: item.componentName,
            x,
            y,
            width: ICON_SIZE,
            height: ICON_SIZE,
            fills: [],
            proportionLock: true,
            proportion: 1,
            componentFile: context.currentFileId,
            componentId,
            componentRoot: true,
            mainInstance: true,
          });

          svgElements.forEach((element, elementIndex) => {
            addIconShape(context, element, `shape-${elementIndex + 1}`, x, y);
          });

          context.closeBoard();
          context.addComponent({
            componentId,
            fileId: context.currentFileId,
            frameId,
            name: item.componentName,
            path: iconparkComponentPath(item.category, theme),
          });
          index += 1;
        } catch (cause) {
          skipped += 1;
          console.warn(`Skip ${item.exportName} ${theme}: ${cause.message}`);
        }
      });

      context.closePage();
    }
  }

  context.closeFile();

  const bytes = await penpot.exportAsBytes(context);
  await writeFile(outFile, bytes);
  console.log(`Wrote ${index} components (${skipped} skipped) to ${outFile}`);
}

const isMain = process.argv[1] != null
  && pathToFileURL(path.resolve(process.argv[1])).href === import.meta.url;

if (isMain) {
  main().catch((cause) => {
    console.error(cause);
    if (cause?.cause) {
      console.error("Inner cause:", cause.cause);
      if (cause.explain) {
        console.error("Explain:", cause.explain);
      }
    }
    process.exit(1);
  });
}

/**
 * 文件类型图标：一枚圆角底 + 白色字形。
 *
 * 不用 emoji、也不引图标库：emoji 在 WebView 里各机器字体不一，看着不专业；
 * 图标库（vscode-icons 之类）动辄几百 KB 且要额外装依赖。这里只用 24×24 的内联 SVG，
 * 常见的十来种类型各一枚，颜色按大众熟悉的惯例给（Word 蓝、Excel 绿、PDF 红……）。
 *
 * 底色 + 单字母/单字形的做法在 24px 这种小尺寸下最清楚 —— 细节一多糊成一团。
 */

const C = {
  java: '#E76F00',
  word: '#2B579A',
  excel: '#217346',
  ppt: '#D24726',
  pdf: '#C0392B',
  markdown: '#5F6B7A',
  text: '#8A9188',
  image: '#1D9E75',
  archive: '#7F77DD',
  code: '#534AB7',
  data: '#BA7517',
  db: '#185FA5',
  file: '#888780'
}

/** 底：圆角方形 */
function badge(color) {
  return `<rect x="1.6" y="1.6" width="20.8" height="20.8" rx="5.6" fill="${color}"/>`
}

/** 字形：白色文字。长一点的字（PDF）用小号，否则出框 */
function letter(txt, fontSize) {
  return (
    `<text x="12" y="${fontSize > 8 ? 15.7 : 14.6}" text-anchor="middle" ` +
    `font-family="-apple-system, Segoe UI, system-ui, sans-serif" ` +
    `font-size="${fontSize}" font-weight="700" fill="#fff">${txt}</text>`
  )
}

/** 字形：三行横线（纯文本） */
const LINES =
  '<g stroke="#fff" stroke-width="1.7" stroke-linecap="round">' +
  '<path d="M7.2 9.2h9.6M7.2 12h9.6M7.2 14.8h6"/></g>'

/** 字形：山与太阳（图片） */
const IMAGE =
  '<path d="M4.6 17.2l4.3-5.3 2.7 3.2 2.3-2.7 5.5 4.8H4.6z" fill="#fff"/>' +
  '<circle cx="9.1" cy="8.4" r="1.7" fill="#fff"/>'

/** 字形：盒子 + 拉链（压缩包） */
const ARCHIVE =
  '<rect x="6.6" y="6.2" width="10.8" height="11.6" rx="2" fill="none" ' +
  'stroke="#fff" stroke-width="1.6"/>' +
  '<path d="M12 6.2v2.2M12 10.4v2.2M12 14.6v2.2" stroke="#fff" stroke-width="1.5" ' +
  'stroke-linecap="round"/>'

/** 字形：尖括号（代码） */
const CODE =
  '<path d="M9.6 8.6L6.2 12l3.4 3.4M14.4 8.6L17.8 12l-3.4 3.4" fill="none" ' +
  'stroke="#fff" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"/>'

/** 字形：花括号（配置） */
const BRACES =
  '<path d="M10.3 7.4c-1.4 0-2 .8-2 1.9v1.1c0 .9-.5 1.4-1.4 1.6.9.2 1.4.7 1.4 1.6v1.1c0 1.1.6 1.9 2 1.9" ' +
  'fill="none" stroke="#fff" stroke-width="1.5" stroke-linecap="round"/>' +
  '<path d="M13.7 7.4c1.4 0 2 .8 2 1.9v1.1c0 .9.5 1.4 1.4 1.6-.9.2-1.4.7-1.4 1.6v1.1c0 1.1-.6 1.9-2 1.9" ' +
  'fill="none" stroke="#fff" stroke-width="1.5" stroke-linecap="round"/>'

/** 字形：数据库（SQL） */
const DB =
  '<ellipse cx="12" cy="8.4" rx="4.7" ry="1.9" fill="none" stroke="#fff" stroke-width="1.5"/>' +
  '<path d="M7.3 8.4v7.2c0 1 2.1 1.9 4.7 1.9s4.7-.9 4.7-1.9V8.4" fill="none" ' +
  'stroke="#fff" stroke-width="1.5"/>' +
  '<path d="M7.3 12c0 1 2.1 1.9 4.7 1.9s4.7-.9 4.7-1.9" fill="none" stroke="#fff" stroke-width="1.5"/>'

/** 字形：咖啡杯（Java，沿用大众认识的那枚） */
const COFFEE =
  '<path d="M6.4 10.6h9v3.5c0 2.3-1.9 4.2-4.2 4.2h-.6c-2.3 0-4.2-1.9-4.2-4.2v-3.5z" fill="#fff"/>' +
  '<path d="M15.4 11.7h1.2a2 2 0 0 1 0 4h-1.2" fill="none" stroke="#fff" stroke-width="1.5"/>' +
  '<path d="M9.1 7.6c.9-.7.9-1.4 0-2.1M12.5 7.6c.9-.7.9-1.4 0-2.1" fill="none" ' +
  'stroke="#fff" stroke-width="1.4" stroke-linecap="round"/>'

const ICONS = {
  java: badge(C.java) + COFFEE,
  word: badge(C.word) + letter('W', 10.5),
  excel: badge(C.excel) + letter('X', 10.5),
  ppt: badge(C.ppt) + letter('P', 10.5),
  pdf: badge(C.pdf) + letter('PDF', 6.6),
  markdown: badge(C.markdown) + letter('M', 10.5),
  text: badge(C.text) + LINES,
  image: badge(C.image) + IMAGE,
  archive: badge(C.archive) + ARCHIVE,
  code: badge(C.code) + CODE,
  data: badge(C.data) + BRACES,
  db: badge(C.db) + DB,
  file: badge(C.file) + LINES
}

const TYPE_BY_EXT = {
  java: 'java',

  doc: 'word', docx: 'word', wps: 'word', rtf: 'word', odt: 'word',
  xls: 'excel', xlsx: 'excel', csv: 'excel', tsv: 'excel', ods: 'excel',
  ppt: 'ppt', pptx: 'ppt', pps: 'ppt', odp: 'ppt',
  pdf: 'pdf',

  md: 'markdown', markdown: 'markdown',
  txt: 'text', log: 'text', text: 'text',

  png: 'image', jpg: 'image', jpeg: 'image', gif: 'image', bmp: 'image',
  webp: 'image', svg: 'image', ico: 'image', tif: 'image', tiff: 'image',

  zip: 'archive', rar: 'archive', '7z': 'archive', tar: 'archive', gz: 'archive',
  bz2: 'archive', xz: 'archive',

  js: 'code', mjs: 'code', cjs: 'code', ts: 'code', tsx: 'code', jsx: 'code',
  vue: 'code', html: 'code', htm: 'code', css: 'code', scss: 'code', less: 'code',
  py: 'code', rb: 'code', go: 'code', rs: 'code', c: 'code', cpp: 'code',
  h: 'code', hpp: 'code', cs: 'code', php: 'code', sh: 'code', bat: 'code',
  ps1: 'code', gradle: 'code', kt: 'code', swift: 'code', lua: 'code', r: 'code',

  json: 'data', xml: 'data', yml: 'data', yaml: 'data', toml: 'data',
  ini: 'data', conf: 'data', cfg: 'data', env: 'data', properties: 'data',

  sql: 'db'
}

function extOf(name) {
  const s = String(name || '')
  const i = s.lastIndexOf('.')
  return i > 0 && i < s.length - 1 ? s.slice(i + 1).toLowerCase() : ''
}

/** 文件名 → 图标类型（java / word / excel / … / file） */
export function iconType(name) {
  return TYPE_BY_EXT[extOf(name)] || 'file'
}

/** 文件名 → 图标的 SVG 字符串 */
export function iconSvg(name) {
  return `<svg viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg">${ICONS[iconType(name)]}</svg>`
}

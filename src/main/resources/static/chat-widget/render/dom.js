const SVG_NS = "http://www.w3.org/2000/svg";

// PlainNode = { tag, ns?, className?, text?, attrs?, children? }
export function h(tag, opts = {}, children = []) {
  const node = { tag };
  if (opts.ns) node.ns = opts.ns;
  if (opts.className) node.className = opts.className;
  if (opts.text != null) node.text = String(opts.text);
  if (opts.attrs) node.attrs = opts.attrs;
  if (children && children.length) node.children = children;
  return node;
}

// 실제 DOM을 만드는 유일한 함수. innerHTML은 절대 쓰지 않는다.
export function mount(node, doc = globalThis.document) {
  const el = node.ns === "svg"
    ? doc.createElementNS(SVG_NS, node.tag)
    : doc.createElement(node.tag);
  if (node.className) el.setAttribute("class", node.className);
  if (node.attrs) {
    for (const [k, v] of Object.entries(node.attrs)) el.setAttribute(k, String(v));
  }
  if (node.text != null) el.textContent = node.text;
  if (node.children) {
    for (const c of node.children) el.append(mount(c, doc));
  }
  return el;
}

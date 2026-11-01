// The Claww desktop UI. Mirrors the Android app: Home (widgets) · Chat · Projects, swipeable,
// themes, wallpapers (static, animated, photo, video), liquid glass with any tint, attachments.
"use strict";
const T = window.__TAURI__;
const invoke = (cmd, args) => T.core.invoke(cmd, args);
const listen = (ev, fn) => T.event.listen(ev, e => fn(e.payload));
const fileSrc = p => T.core.convertFileSrc(p);
const $ = (s, el = document) => el.querySelector(s);
const h = (tag, attrs = {}, ...kids) => {
  const e = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs || {})) {
    if (k === "class") e.className = v;
    else if (k === "style") e.style.cssText = v;
    else if (k.startsWith("on")) e.addEventListener(k.slice(2), v);
    else if (v !== false && v != null) e.setAttribute(k, v);
  }
  for (const k of kids.flat()) if (k != null && k !== false) e.append(k.nodeType ? k : document.createTextNode(String(k)));
  return e;
};
const uid = () => Date.now().toString(36) + Math.random().toString(36).slice(2, 8);

// ---------- storage ----------
const P = {
  get(k, d) { try { const v = localStorage.getItem("claww." + k); return v == null ? d : JSON.parse(v); } catch { return d; } },
  set(k, v) { localStorage.setItem("claww." + k, JSON.stringify(v)); },
};
const Store = {
  convs: P.get("convs", []),          // {id,title,project,updated}
  projects: P.get("projects", []),    // {id,name,notes,updated}
  msgs(c) { return P.get("c_" + c.id, []); },  // {u,t,a:[{path,mime,name}],s}
  saveConv(c, msgs) {
    c.updated = Date.now();
    if (!this.convs.find(x => x.id === c.id)) this.convs.unshift(c);
    P.set("c_" + c.id, msgs); P.set("convs", this.convs);
    const p = this.project(c.project); if (p) { p.updated = c.updated; this.saveProjects(); }
  },
  deleteConv(c) { this.convs = this.convs.filter(x => x.id !== c.id); localStorage.removeItem("claww.c_" + c.id); P.set("convs", this.convs); },
  saveProjects() { P.set("projects", this.projects); },
  newProject(name) { const p = { id: uid(), name: name.trim() || "Untitled project", notes: "", updated: Date.now() }; this.projects.unshift(p); this.saveProjects(); return p; },
  deleteProject(p) { this.convs.filter(c => c.project === p.id).forEach(c => this.deleteConv(c)); this.projects = this.projects.filter(x => x.id !== p.id); this.saveProjects(); },
  project(id) { return this.projects.find(p => p.id === id); },
  newConv(project) { return { id: uid(), title: "New chat", project: project || null, updated: Date.now() }; },
  regular() { return this.convs.filter(c => !c.project).sort((a, b) => b.updated - a.updated); },
  ofProject(id) { return this.convs.filter(c => c.project === id).sort((a, b) => b.updated - a.updated); },
};
const ago = ms => { const m = Math.floor((Date.now() - ms) / 60000); return m < 1 ? "just now" : m < 60 ? m + "m ago" : m < 1440 ? Math.floor(m / 60) + "h ago" : new Date(ms).toLocaleDateString(undefined, { month: "short", day: "numeric" }); };

// ---------- settings ----------
const S = {
  get host() { return (P.get("host", "http://127.0.0.1:11434") || "").replace(/\/$/, ""); },
  get model() { return P.get("model", "llama3.2:3b"); },
};
let PATHS = { workspace: "", data: "" };
let SYS = null;

// ---------- themes ----------
const THEMES = {
  Ember: ["#0e0e10", "#1c1c20", "#28282e", "#ececf0", "#9696a0", "#ff7a45", "#000"],
  Midnight: ["#0a0e1a", "#161e32", "#222c46", "#e4eaf8", "#8c98b4", "#6ea0ff", "#000"],
  Forest: ["#0c1410", "#18241e", "#24342c", "#e4f0e8", "#8ca596", "#6ed28c", "#000"],
  Grape: ["#120c1a", "#221830", "#322444", "#f0e8fa", "#a596b9", "#be82ff", "#000"],
  AMOLED: ["#000000", "#141414", "#222222", "#ffffff", "#8c8c8c", "#ffffff", "#000"],
  Paper: ["#f6f4f0", "#ffffff", "#e8e5e0", "#1c1c1e", "#6e6e74", "#e65c28", "#fff"],
};
const hexRgb = x => { const n = parseInt(x.slice(1), 16); return [(n >> 16) & 255, (n >> 8) & 255, n & 255]; };
const lum = x => { const [r, g, b] = hexRgb(x); return (0.299 * r + 0.587 * g + 0.114 * b) / 255; };
function themeColors() {
  const name = P.get("theme", "Ember");
  if (name === "Custom accent") {
    const base = [...THEMES.Ember]; base[5] = P.get("customAccent", "#7c9cff"); base[6] = lum(base[5]) > 0.6 ? "#000" : "#fff"; return base;
  }
  return THEMES[name] || THEMES.Ember;
}
const GRADIENTS = { Sunset: ["#ff7850", "#5a1e6e"], Aurora: ["#14b496", "#141e5a"], Ocean: ["#2878dc", "#0a1432"], Dusk: ["#7850c8", "#f07896"] };
const ANIMATED = () => {
  const c = themeColors();
  return {
    "Theme flow": ["blobs", c[0], [c[5], c[2], "#" + hexRgb(c[5]).reverse().map(v => v.toString(16).padStart(2, "0")).join("")]],
    Lava: ["blobs", "#190814", ["#ff5a28", "#e62878", "#8c28c8", "#ffaa3c"]],
    "Aurora flow": ["blobs", "#060c1e", ["#1edca0", "#2878ff", "#a050ff"]],
    Starfield: ["stars", "#040610", ["#ffffff", "#5a46c8"]],
    Waves: ["waves", "#081024", ["#286edc", "#1eaac8", "#5a50dc", "#14c8aa"]],
  };
};
const TINTS = { Clear: "#ffffff", Theme: null, Blue: "#5aa0ff", Mint: "#50dcb4", Pink: "#ff78be", Violet: "#aa78ff", Amber: "#ffbe50", Smoke: "#141418" };

let wallAnim = null;
function applyLook() {
  const c = themeColors(), r = document.documentElement.style;
  ["--bg", "--surface", "--surface2", "--fg", "--muted", "--accent", "--on-accent"].forEach((v, i) => r.setProperty(v, c[i]));
  const tintName = P.get("glassTint", "Clear");
  const tint = tintName === "Custom" ? P.get("glassCustom", "#78c8ff") : tintName === "Theme" ? c[5] : TINTS[tintName] || "#ffffff";
  r.setProperty("--glass-tint", hexRgb(tint).join(","));
  r.setProperty("--glass-a", (0.06 + P.get("glassStrength", 30) / 100 * 0.5).toFixed(3));
  r.setProperty("--bubble-op", (P.get("bubbleOpacity", 100) / 100).toFixed(2));
  r.setProperty("--wall-blur", P.get("blur", 0) + "px");
  r.setProperty("--dim", (P.get("dim", 45) / 100).toFixed(2));
  document.body.classList.toggle("glass", P.get("glass", false));
  document.body.classList.toggle("light-bubbles", P.get("bubbleOpacity", 100) < 60);
  // wallpaper
  const wall = $("#wall"), w = P.get("wall", "None");
  if (wallAnim) { cancelAnimationFrame(wallAnim.raf); wallAnim = null; }
  wall.innerHTML = ""; wall.style.background = "";
  document.body.classList.toggle("has-wall", w !== "None");
  if (GRADIENTS[w]) wall.style.background = `linear-gradient(${GRADIENTS[w][0]}, ${GRADIENTS[w][1]})`;
  else if (ANIMATED()[w]) startAnimated(wall, ...ANIMATED()[w]);
  else if (w === "Your photo" && P.get("wallPath", "")) wall.append(h("img", { src: fileSrc(P.get("wallPath", "")) }));
  else if (w === "Your video" && P.get("wallVideo", "")) { const v = h("video", { src: fileSrc(P.get("wallVideo", "")), autoplay: "", loop: "", playsinline: "" }); v.muted = true; wall.append(v); }
}

function startAnimated(wall, kind, bg, cols) {
  const cv = h("canvas"); wall.append(cv);
  const ctx = cv.getContext("2d");
  const stars = Array.from({ length: 160 }, (_, i) => [Math.random(), Math.random(), 0.3 + Math.random() * 1.4, Math.random() * 6.28]);
  const t0 = performance.now(); let last = 0;
  const st = { raf: 0 }; wallAnim = st;
  const glow = (x, y, r, col, a) => {
    const g = ctx.createRadialGradient(x, y, 0, x, y, r);
    const [R, G, B] = hexRgb(col); g.addColorStop(0, `rgba(${R},${G},${B},${a})`); g.addColorStop(1, `rgba(${R},${G},${B},0)`);
    ctx.fillStyle = g; ctx.beginPath(); ctx.arc(x, y, r, 0, 6.29); ctx.fill();
  };
  const frame = now => {
    st.raf = requestAnimationFrame(frame);
    if (document.hidden || now - last < 33) return; last = now;
    const dpr = Math.min(devicePixelRatio || 1, 1.5);
    const W = Math.floor(innerWidth * dpr / 2), H = Math.floor(innerHeight * dpr / 2); // half-res: it's blurred/dimmed anyway
    if (cv.width !== W || cv.height !== H) { cv.width = W; cv.height = H; }
    const s = (now - t0) / 1000;
    ctx.fillStyle = bg; ctx.fillRect(0, 0, W, H);
    if (kind === "stars") {
      for (const p of stars) {
        const y = (p[1] + s * 0.01 * p[2]) % 1, tw = 0.45 + 0.55 * (Math.sin(s * 1.7 + p[3]) + 1) / 2;
        ctx.fillStyle = `rgba(255,255,255,${tw * 0.9})`; ctx.beginPath(); ctx.arc(p[0] * W, y * H, p[2] * 0.9, 0, 6.29); ctx.fill();
      }
      glow(W * (0.5 + 0.3 * Math.sin(s * 0.07)), H * 0.35, W * 0.9, cols[1], 0.28);
    } else if (kind === "waves") {
      for (let k = 0; k < 4; k++) {
        const base = H * (0.55 + k * 0.11), amp = H * (0.035 + k * 0.008);
        ctx.beginPath(); ctx.moveTo(0, H);
        for (let x = 0; x <= W + 6; x += 6) ctx.lineTo(x, base + amp * Math.sin(x / W * 6.28 * (1.2 + k * 0.3) + s * (0.6 + k * 0.25) + k));
        ctx.lineTo(W, H); ctx.closePath();
        const [R, G, B] = hexRgb(cols[k % cols.length]); ctx.fillStyle = `rgba(${R},${G},${B},${(70 + k * 35) / 255})`; ctx.fill();
      }
    } else {
      cols.forEach((col, i) => {
        const a = s * (0.11 + i * 0.037) + i * 2.1;
        glow(W * (0.5 + 0.38 * Math.sin(a * 1.3 + i)), H * (0.5 + 0.38 * Math.cos(a + i * 0.7)), Math.max(W, H) * (0.45 + 0.08 * Math.sin(a * 2)), col, 0.8);
      });
    }
  };
  st.raf = requestAnimationFrame(frame);
}

// ---------- modal helpers ----------
function modal(title, body, buttons = [["Done", null, true]]) {
  const sheet = $("#sheet"); sheet.innerHTML = "";
  sheet.append(h("h2", {}, title), body);
  const act = h("div", { class: "actions" });
  buttons.forEach(([label, fn, primary]) => act.append(h("button", { class: primary ? "primary" : "", onclick: () => { if (fn && fn() === false) return; closeModal(); } }, label)));
  sheet.append(act);
  $("#modal").classList.remove("hidden");
}
function closeModal() { $("#modal").classList.add("hidden"); }
$("#modal").addEventListener("click", e => { if (e.target.id === "modal") closeModal(); });
function ask(title, placeholder, initial = "", multi = false) {
  return new Promise(res => {
    const f = h(multi ? "textarea" : "input", { class: "field", placeholder });
    f.value = initial;
    f.addEventListener("keydown", e => { if (e.key === "Enter" && !multi) { closeModal(); res(f.value.trim()); } });
    modal(title, f, [["Cancel", () => res(null)], ["OK", () => res(f.value.trim()), true]]);
    setTimeout(() => f.focus(), 30);
  });
}
function menu(title, items) { // items: [label, fn]
  const box = h("div", {}, items.map(([l, fn]) => h("div", { class: "list-item", onclick: () => { closeModal(); fn(); } }, l)));
  modal(title, box, [["Close", null]]);
}
const lbl = s => h("div", { class: "lbl" }, s);
function slider(label, key, def, max, onChange) {
  const r = h("input", { type: "range", min: 0, max });
  r.value = P.get(key, def);
  r.addEventListener("input", () => { P.set(key, +r.value); onChange ? onChange() : applyLook(); });
  return [lbl(label), r];
}
function swatches(items, selected, pick) { // items: [name, cssBackground]
  return h("div", { class: "swatches" }, items.map(([n, bg]) =>
    h("div", { class: "sw" + (n === selected ? " on" : ""), onclick: () => pick(n) }, h("div", { style: `background:${bg}` }), n)));
}

// ---------- status ----------
let status = { color: "var(--muted)", line: "Starting…", ok: false };
const statusEls = [];
async function pollStatus() {
  let ok = false, has = false;
  try { await invoke("http_get", { url: S.host + "/api/version" }); ok = true; } catch {}
  if (ok) try {
    const tags = JSON.parse(await invoke("http_get", { url: S.host + "/api/tags" })).models || [];
    const want = S.model.includes(":") ? S.model : S.model + ":latest";
    has = tags.some(m => m.name === want);
  } catch {}
  status = {
    ok, color: has ? "#50c878" : ok ? "#f0b43c" : "var(--muted)",
    line: has ? `Ready · ${S.model}` : ok ? `Ollama running · ${S.model} not downloaded` : "Can't reach Ollama · tap to start it",
  };
  statusEls.forEach(([dot, txt]) => { dot.style.background = status.color; if (txt) txt.textContent = status.line; });
  liveUpdaters.forEach(f => f());
}

// ---------- pager ----------
const track = $("#track"), pager = $("#pager");
let tab = P.get("tab", 1);
function go(i, anim = true) {
  tab = Math.max(0, Math.min(2, i)); P.set("tab", tab);
  track.classList.toggle("drag", !anim);
  track.style.transform = `translateX(${-tab * 100 / 3}%)`;
  document.querySelectorAll("#tabs button").forEach(b => b.classList.toggle("on", +b.dataset.tab === tab));
  if (tab === 0) renderHome();
  if (tab === 2 && !openProject) showProjectList();
}
document.querySelectorAll("#tabs button").forEach(b => b.addEventListener("click", () => go(+b.dataset.tab)));
addEventListener("keydown", e => { if ((e.ctrlKey || e.metaKey) && ["1", "2", "3"].includes(e.key)) { e.preventDefault(); go(+e.key - 1); } });
// Touch / pen swipe
(() => {
  let sx = 0, sy = 0, drag = false, id = null, w = 1, t0 = 0;
  pager.addEventListener("pointerdown", e => { if (e.pointerType === "mouse") return; sx = e.clientX; sy = e.clientY; id = e.pointerId; drag = false; w = pager.clientWidth; t0 = performance.now(); });
  pager.addEventListener("pointermove", e => {
    if (e.pointerId !== id) return;
    const dx = e.clientX - sx, dy = e.clientY - sy;
    if (!drag && Math.abs(dx) > 14 && Math.abs(dx) > Math.abs(dy) * 1.4) { drag = true; track.classList.add("drag"); try { pager.setPointerCapture(id); } catch {} }
    if (drag) {
      let off = -tab * w + dx; const min = -2 * w;
      if (off > 0) off /= 3; if (off < min) off = min + (off - min) / 3;
      track.style.transform = `translateX(${off / 3 / w * 100}%)`;
    }
  });
  const end = e => {
    if (e.pointerId !== id) return; id = null;
    if (!drag) return; drag = false; track.classList.remove("drag");
    const dx = e.clientX - sx, v = dx / Math.max(1, performance.now() - t0);
    go(dx < -w * 0.25 || v < -0.6 ? tab + 1 : dx > w * 0.25 || v > 0.6 ? tab - 1 : tab);
  };
  pager.addEventListener("pointerup", end); pager.addEventListener("pointercancel", end);
  // Trackpad horizontal swipe
  let acc = 0, lock = 0;
  pager.addEventListener("wheel", e => {
    if (Math.abs(e.deltaX) <= Math.abs(e.deltaY) * 1.5) return;
    const now = performance.now(); if (now < lock) return;
    acc += e.deltaX;
    if (Math.abs(acc) > 120) { go(tab + (acc > 0 ? 1 : -1)); acc = 0; lock = now + 450; }
    clearTimeout(pager._wt); pager._wt = setTimeout(() => acc = 0, 180);
  }, { passive: true });
})();

// ---------- Home ----------
const WIDGETS = [["status", "Ollama status"], ["device", "Device & chip"], ["clock", "Clock"], ["recent", "Recent chats"], ["projects", "Projects"], ["prompt", "Quick prompt"], ["note", "Note"]];
let liveUpdaters = [];
const widgets = () => P.get("widgets", [{ type: "status" }, { type: "device" }, { type: "recent" }]);
function renderHome() {
  const home = $("#home"); home.innerHTML = ""; liveUpdaters = [];
  const list = widgets();
  list.forEach((w, i) => {
    const c = buildWidget(w, i);
    c.addEventListener("contextmenu", e => {
      e.preventDefault();
      menu((WIDGETS.find(x => x[0] === w.type) || [])[1] || "Widget", [
        ["Move up", () => { const l = widgets(); if (i > 0) { [l[i - 1], l[i]] = [l[i], l[i - 1]]; P.set("widgets", l); renderHome(); } }],
        ["Move down", () => { const l = widgets(); if (i < l.length - 1) { [l[i + 1], l[i]] = [l[i], l[i + 1]]; P.set("widgets", l); renderHome(); } }],
        ["Remove", () => { const l = widgets(); l.splice(i, 1); P.set("widgets", l); renderHome(); }],
      ]);
    });
    longPress(c, () => c.dispatchEvent(new MouseEvent("contextmenu")));
    home.append(c);
  });
  home.append(h("div", { class: "card panel click addcard", onclick: addWidget }, "＋  Add a widget"));
  home.append(h("div", { class: "muted", style: "text-align:center" }, "Right-click or long-press a widget to move or remove it. Swipe, or Ctrl+1/2/3, to switch tabs."));
  liveUpdaters.forEach(f => f());
}
function longPress(el, fn) {
  let t; el.addEventListener("pointerdown", e => { if (e.pointerType !== "mouse") t = setTimeout(fn, 550); });
  ["pointerup", "pointercancel", "pointermove"].forEach(ev => el.addEventListener(ev, () => clearTimeout(t)));
}
async function addWidget() {
  menu("Add a widget", WIDGETS.map(([type, name]) => [name, async () => {
    const l = widgets();
    if (type === "prompt") { const t = await ask("Quick prompt", "e.g. Summarize what we did today"); if (!t) return; l.push({ type, text: t }); }
    else if (type === "note") { const t = await ask("Note", "Anything you want on Home", "", true); if (t == null) return; l.push({ type, text: t }); }
    else l.push({ type });
    P.set("widgets", l); renderHome();
  }]));
}
function buildWidget(w, i) {
  const c = h("div", { class: "card panel" });
  const hdr = s => c.append(h("div", { class: "hdr" }, s));
  switch (w.type) {
    case "status": {
      hdr("Ollama");
      const dot = h("span", { class: "dot", style: "position:static;display:inline-block;width:12px;height:12px;border-radius:6px" });
      const line = h("span", { class: "big" });
      c.append(h("div", { class: "row" }, dot, line), h("div", { class: "muted", style: "margin-top:6px" }, `${S.host} · click for settings`));
      liveUpdaters.push(() => { dot.style.background = status.color; line.textContent = status.line; });
      c.classList.add("click"); c.onclick = openSettings; break;
    }
    case "device": {
      hdr("Device");
      const name = h("div", { class: "big" }), stats = h("div", { class: "muted", style: "margin-top:6px;white-space:pre-line" });
      c.append(name, stats);
      liveUpdaters.push(async () => {
        try { SYS = await invoke("sys_info"); } catch { return; }
        name.textContent = SYS.host ? `${SYS.host} · ${SYS.os}` : SYS.os;
        stats.textContent = `${SYS.cpu} · ${SYS.cores} threads\nRAM ${(SYS.memFree / 1e9).toFixed(1)} GB free of ${(SYS.memTotal / 1e9).toFixed(0)} GB`;
      });
      break;
    }
    case "clock": {
      hdr("Today");
      const time = h("div", { style: "font-size:34px;font-weight:600" }), date = h("div", { class: "muted", style: "font-size:14px" });
      c.append(time, date);
      liveUpdaters.push(() => { const d = new Date(); time.textContent = d.toLocaleTimeString(undefined, { hour: "numeric", minute: "2-digit" }); date.textContent = d.toLocaleDateString(undefined, { weekday: "long", month: "long", day: "numeric" }); });
      break;
    }
    case "recent": {
      hdr("Recent chats");
      const recent = [...Store.convs].sort((a, b) => b.updated - a.updated).slice(0, 4);
      if (!recent.length) c.append(h("div", { class: "muted" }, "No chats yet. Swipe to Chat to start one."));
      recent.forEach(cv => { const p = Store.project(cv.project); c.append(h("div", { class: "list-item", style: "padding:6px 0", onclick: () => openConv(cv) }, h("div", {}, cv.title), h("div", { class: "muted", style: "font-size:12px" }, (p ? p.name + " · " : "") + ago(cv.updated)))); });
      break;
    }
    case "projects": {
      hdr("Projects");
      if (!Store.projects.length) c.append(h("div", { class: "muted" }, "No projects yet. Click to create one."));
      [...Store.projects].sort((a, b) => b.updated - a.updated).slice(0, 4).forEach(p => c.append(h("div", { class: "list-item", style: "padding:6px 0", onclick: e => { e.stopPropagation(); showProjectDetail(p); go(2); } }, `${p.name}  ·  ${Store.ofProject(p.id).length} chats`)));
      c.classList.add("click"); c.onclick = () => go(2); break;
    }
    case "prompt": {
      hdr("Quick prompt");
      c.append(h("div", { style: "font-size:16px" }, w.text), h("div", { style: "color:var(--accent);font-size:12px;margin-top:8px" }, "Click to ask in Chat"));
      c.classList.add("click"); c.onclick = () => { go(1); chatPane.input.value = w.text; chatPane.send(); }; break;
    }
    case "note": {
      hdr("Note");
      c.append(h("div", { style: "white-space:pre-wrap" }, w.text || "Click to write"));
      c.classList.add("click");
      c.onclick = async () => { const t = await ask("Note", "Anything you want on Home", w.text || "", true); if (t == null) return; const l = widgets(); l[i].text = t; P.set("widgets", l); renderHome(); };
      break;
    }
  }
  return c;
}

// ---------- Projects ----------
let openProject = null;
const layers = ["#projList", "#projDetail", "#projChat"];
const showLayer = i => layers.forEach((s, k) => $(s).classList.toggle("hidden", k !== i));
function showProjectList() {
  openProject = null; P.set("curProject", null);
  const el = $("#projList"); el.innerHTML = "";
  el.append(h("div", { class: "row", style: "margin-bottom:12px" }, h("h1", { class: "grow" }, "Projects"), h("button", { class: "chip", onclick: () => newProject() }, "+ New project")));
  if (!Store.projects.length) el.append(h("div", { class: "card panel" }, h("div", { class: "big" }, "No projects yet"),
    h("div", { class: "muted", style: "margin-top:6px;font-size:14px" }, "A project keeps its own chats, notes and folder together, so claw remembers the context. Create one here, or ask in Chat: \"create a project called Garage build\".")));
  [...Store.projects].sort((a, b) => b.updated - a.updated).forEach(p => {
    const n = Store.ofProject(p.id).length;
    el.append(h("div", { class: "card panel click", onclick: () => showProjectDetail(p) }, h("div", { class: "big" }, p.name),
      h("div", { class: "muted", style: "margin-top:4px" }, `${n} chat${n === 1 ? "" : "s"} · ${ago(p.updated)}${p.notes ? " · " + p.notes.slice(0, 60) : ""}`)));
  });
  showLayer(0);
}
async function newProject(then) {
  const name = await ask("New project", "Project name"); if (!name) return;
  const p = Store.newProject(name); then ? then(p) : showProjectDetail(p); return p;
}
function projectDir(p) { return PATHS.data ? `${PATHS.data}/projects/${p.id}`.replace(/\\/g, "/") : ""; }
function showProjectDetail(p) {
  openProject = p; P.set("curProject", p.id);
  const el = $("#projDetail"); el.innerHTML = "";
  el.append(h("div", { class: "row", style: "margin-bottom:12px" },
    h("button", { class: "back", onclick: showProjectList }, "‹"), h("h1", { class: "grow", style: "margin:0" }, p.name),
    h("button", { class: "chip", onclick: () => menu(p.name, [
      ["Rename", async () => { const n = await ask("Rename project", "Project name", p.name); if (n) { p.name = n; Store.saveProjects(); showProjectDetail(p); } }],
      ["Delete project", () => modal(`Delete ${p.name}?`, h("div", { class: "muted" }, "Its chats are deleted too. Files claw made in the project folder stay on disk."), [["Cancel", null], ["Delete", () => { Store.deleteProject(p); showProjectList(); }, true]])],
    ]) }, "Edit")));
  el.append(h("div", { class: "card panel click", onclick: async () => { const t = await ask("Project notes", "Goals, context, rules", p.notes, true); if (t != null) { p.notes = t; Store.saveProjects(); showProjectDetail(p); } } },
    h("div", { class: "hdr" }, "Project notes"), h("div", { style: "white-space:pre-wrap", class: p.notes ? "" : "muted" }, p.notes || "Click to add goals, context or rules claw should follow in this project."),
    h("div", { class: "muted", style: "margin-top:8px;font-size:11px" }, "Folder: " + projectDir(p))));
  el.append(h("button", { class: "btn-accent", onclick: () => openProjectChat(Store.newConv(p.id)) }, `＋  New chat in ${p.name}`));
  const chats = Store.ofProject(p.id);
  if (chats.length) el.append(h("div", { class: "hdr", style: "margin:8px 4px" }, "Chats"));
  chats.forEach(cv => {
    const card = h("div", { class: "card panel click", onclick: () => openProjectChat(cv) }, h("div", {}, cv.title), h("div", { class: "muted", style: "font-size:12px" }, ago(cv.updated)));
    card.addEventListener("contextmenu", e => { e.preventDefault(); confirmDelete(cv, () => showProjectDetail(p)); });
    longPress(card, () => confirmDelete(cv, () => showProjectDetail(p)));
    el.append(card);
  });
  showLayer(1);
}
function confirmDelete(cv, after) { modal(`Delete "${cv.title}"?`, h("div"), [["Cancel", null], ["Delete", () => { Store.deleteConv(cv); after(); }, true]]); }
function openProjectChat(cv) { openProject = Store.project(cv.project); projPane.open(cv); showLayer(2); }
function openConv(cv) {
  if (!cv.project) { chatPane.open(cv); go(1); }
  else { const p = Store.project(cv.project); if (p) showProjectDetail(p); openProjectChat(cv); go(2); }
}

// ---------- attachments ----------
const TEXT_EXT = ["txt", "md", "json", "csv", "kt", "java", "rs", "py", "js", "ts", "html", "css", "xml", "yml", "yaml", "toml", "sh", "c", "cpp", "h", "go", "log", "ini", "cfg", "ps1", "bat"];
const extOf = n => (n.includes(".") ? n.split(".").pop() : "").toLowerCase();
const isText = (n, m) => (m || "").startsWith("text/") || /json|xml/.test(m || "") || TEXT_EXT.includes(extOf(n));
const mimeOf = n => { const e = extOf(n); return ({ png: "image/png", jpg: "image/jpeg", jpeg: "image/jpeg", gif: "image/gif", webp: "image/webp", bmp: "image/bmp", mp4: "video/mp4", webm: "video/webm", mov: "video/quicktime", mkv: "video/x-matroska" })[e] || (TEXT_EXT.includes(e) ? "text/plain" : "application/octet-stream"); };
const fileB64 = f => new Promise((res, rej) => { const r = new FileReader(); r.onload = () => res(String(r.result).split(",")[1] || ""); r.onerror = rej; r.readAsDataURL(f); });
// Read a saved file back as a same-origin blob URL (canvas can't read asset:// pixels across origins).
async function blobUrl(a) {
  const b64 = await invoke("read_b64", { path: a.path });
  const bin = atob(b64); const u = new Uint8Array(bin.length); for (let i = 0; i < bin.length; i++) u[i] = bin.charCodeAt(i);
  return URL.createObjectURL(new Blob([u], { type: a.mime }));
}
function imgToJpegB64(src, max = 1024) {
  return new Promise(res => {
    const img = new Image(); img.crossOrigin = "anonymous";
    img.onload = () => { const k = Math.min(1, max / Math.max(img.width, img.height)); const c = h("canvas"); c.width = img.width * k; c.height = img.height * k; c.getContext("2d").drawImage(img, 0, 0, c.width, c.height); try { res(c.toDataURL("image/jpeg", 0.85).split(",")[1]); } catch { res(null); } };
    img.onerror = () => res(null); img.src = src;
  });
}
async function videoFramesB64(src, n = 4, max = 768) {
  const v = h("video"); v.muted = true; v.crossOrigin = "anonymous"; v.preload = "auto"; v.src = src;
  await new Promise(r => { v.onloadedmetadata = r; v.onerror = r; setTimeout(r, 8000); });
  const out = []; const d = v.duration || 0; if (!d || !isFinite(d)) return out;
  for (let k = 1; k <= n; k++) {
    await new Promise(r => { v.onseeked = r; v.currentTime = d * k / (n + 1); setTimeout(r, 4000); });
    const s = Math.min(1, max / Math.max(v.videoWidth, v.videoHeight)); const c = h("canvas"); c.width = v.videoWidth * s; c.height = v.videoHeight * s;
    c.getContext("2d").drawImage(v, 0, 0, c.width, c.height);
    try { out.push(c.toDataURL("image/jpeg", 0.85).split(",")[1]); } catch {}
  }
  return out;
}
function thumb(a, small, onRemove) {
  const t = h("div", { class: "thumb" + (small ? " small" : "") });
  if (a.mime.startsWith("image/")) t.append(h("img", { src: fileSrc(a.path) }));
  else if (a.mime.startsWith("video/")) { const v = h("video", { src: fileSrc(a.path) + "#t=0.5", preload: "metadata" }); v.muted = true; t.append(v, h("div", { class: "play" }, "▶")); }
  else t.append(h("div", { class: "ext" }, (extOf(a.name) || "file").toUpperCase().slice(0, 5), h("small", {}, a.name)));
  if (onRemove) t.append(h("button", { class: "x", onclick: e => { e.stopPropagation(); onRemove(); } }, "×"));
  else t.onclick = () => { if (a.mime.startsWith("image/")) modal(a.name, h("img", { src: fileSrc(a.path), style: "max-width:100%;border-radius:12px" })); else if (a.mime.startsWith("video/")) modal(a.name, h("video", { src: fileSrc(a.path), controls: "", style: "max-width:100%;border-radius:12px" })); else modal(a.name, h("div", { class: "muted" }, a.path)); };
  return t;
}

// ---------- chat pane ----------
const ARROW = `<svg viewBox="0 0 18 18" fill="none" stroke="currentColor" stroke-width="2.7" stroke-linecap="round" stroke-linejoin="round"><path d="M9 16.6V1.4M1.4 8.6 9 1.4l7.6 7.2"/></svg>`;
const PLUS = `<svg viewBox="0 0 18 18" fill="none" stroke="currentColor" stroke-width="2.7" stroke-linecap="round"><path d="M9 1.4v15.2M1.4 9h15.2"/></svg>`;
const ansi = /\u001B\[[0-9;?]*[ -\/]*[@-~]/g;
const handlers = {};  // job id -> {out, done}
listen("claw-out", p => handlers[p.id]?.out(p.chunk));
listen("claw-done", p => handlers[p.id]?.done(p));
listen("chat-line", p => handlers[p.id]?.out(p.line));
listen("chat-line-done", p => handlers[p.id]?.done(p));
listen("pull-line", p => handlers[p.id]?.out(p.line));
listen("pull-line-done", p => handlers[p.id]?.done(p));

function ChatPane(root, projectMode) {
  const self = {};
  let conv = null, msgs = [], pending = [], busy = false, job = null, stopped = false;
  const title = h("div", { class: "pane-title" });
  const dot = h("i"), stxt = h("span");
  statusEls.push([dot, stxt]);
  const head = h("div", { class: "pane-head" },
    projectMode ? h("button", { class: "back", onclick: () => openProject ? showProjectDetail(openProject) : showProjectList() }, "‹") : null,
    h("div", { class: "grow" }, title, h("div", { class: "status", onclick: () => status.ok ? openSettings() : invoke("start_ollama").then(() => setTimeout(pollStatus, 1500)).catch(e => modal("Ollama", h("div", {}, String(e)))) }, dot, stxt)),
    projectMode ? null : h("button", { class: "chip", onclick: showHistory }, "Chats"),
    h("button", { class: "chip", onclick: () => { if (!busy) self.open(Store.newConv(projectMode ? conv.project : null)); } }, "New"));
  const list = h("div", { class: "msgs" });
  const pend = h("div", { class: "pending hidden" });
  const input = h("textarea", { rows: 1, placeholder: projectMode ? "Message claw about this project" : "Message claw" });
  const picker = h("input", { type: "file", multiple: "", class: "hidden" });
  const plus = h("button", { class: "cbtn plus", title: "Attach photos, videos or files", onclick: () => picker.click() }); plus.innerHTML = PLUS;
  const sendBtn = h("button", { class: "cbtn send", title: "Send", style: "color:var(--on-accent)", onclick: () => self.send() });
  const setBusy = b => { busy = b; sendBtn.innerHTML = b ? `<div class="stopsq"></div>` : ARROW; sendBtn.title = b ? "Stop" : "Send"; };
  setBusy(false);
  root.append(h("div", { class: "pane" }, head, list, h("div", { class: "composer-wrap" }, h("div", { class: "composer panel" }, pend, h("div", { class: "bar" }, input, plus, sendBtn)), picker)));
  self.input = input;
  input.addEventListener("input", () => { input.style.height = "auto"; input.style.height = Math.min(160, input.scrollHeight) + "px"; });
  input.addEventListener("keydown", e => { if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); self.send(); } });
  input.addEventListener("paste", e => { const fs = [...(e.clipboardData?.files || [])]; if (fs.length) { e.preventDefault(); fs.forEach(addFile); } });
  root.addEventListener("dragover", e => e.preventDefault());
  root.addEventListener("drop", e => { e.preventDefault(); [...(e.dataTransfer?.files || [])].forEach(addFile); });
  picker.addEventListener("change", () => { [...picker.files].forEach(addFile); picker.value = ""; });

  const project = () => Store.project(conv?.project);
  const workDir = () => conv?.project ? projectDir(project()) : PATHS.workspace;
  const setTitle = () => { title.textContent = projectMode ? `${project()?.name || "Project"} · ${conv.title}` : (conv.title === "New chat" ? "Chat" : conv.title); };

  async function addFile(f) {
    try {
      const b64 = await fileB64(f);
      const path = await invoke("save_file", { dir: workDir() + "/attachments", name: f.name || "pasted.png", b64 });
      pending.push({ path, mime: f.type || mimeOf(f.name), name: path.split(/[\\/]/).pop() }); renderPending();
    } catch (e) { modal("Couldn't attach", h("div", {}, String(e))); }
  }
  function renderPending() {
    pend.innerHTML = ""; pend.classList.toggle("hidden", !pending.length);
    pending.forEach(a => pend.append(thumb(a, true, () => { pending = pending.filter(x => x !== a); renderPending(); })));
  }
  function bubble(text, user, atts = [], stoppedNote = false) {
    const m = h("div", { class: "msg" + (user ? " user" : "") });
    if (atts.length) m.append(h("div", { class: "atts", style: user ? "" : "justify-content:flex-start" }, atts.map(a => thumb(a))));
    const b = h("div", { class: "bubble" }); b.textContent = text; if (!text) b.classList.add("hidden");
    if (stoppedNote) markStopped(b, text);
    m.append(b); list.append(m); list.scrollTop = list.scrollHeight; return b;
  }
  function markStopped(b, partial) { b.classList.remove("hidden"); b.textContent = partial; b.append(h("span", { class: "stopnote" }, "Not finished · you stopped this reply")); }

  self.open = c => {
    conv = c; msgs = Store.msgs(c); pending = []; renderPending(); list.innerHTML = "";
    if (!projectMode) P.set("curChat", c.id);
    setTitle();
    if (!msgs.length) bubble(projectMode ? `New chat in ${project()?.name}. I'll keep this project's notes in mind, and files I make go in its folder.`
      : "Hi. I'm claw, running on your PC with Ollama.\nAsk me anything, attach photos, videos or files with + (or drag them in), or say \"create a project called …\".", false);
    else msgs.forEach(m => bubble(m.t, m.u, m.a || [], m.s));
  };
  function showHistory() {
    const l = Store.regular();
    if (!l.length) return modal("Chats", h("div", { class: "muted" }, "No saved chats yet."));
    modal("Chats", h("div", {}, l.map(c => h("div", { class: "list-item", onclick: () => { closeModal(); if (!busy) self.open(c); } }, h("div", {}, c.title), h("div", { class: "muted", style: "font-size:12px" }, ago(c.updated))))),
      [["Delete current", () => { if (!busy && Store.convs.find(x => x.id === conv.id)) confirmDelete(conv, () => self.open(Store.newConv(null))); return false; }], ["Close", null, true]]);
  }
  const context = prior => {
    let s = prior.map(m => (m.u ? "User: " : "Assistant: ") + m.t + (m.a?.length ? ` [attached: ${m.a.map(a => a.name).join(", ")}]` : "") + (m.s ? " [reply cut off by user]" : "")).join("\n");
    return s.length > 8000 ? s.slice(-8000) : s;
  };
  const preamble = () => { const p = project(); return p ? `You are working in the project "${p.name}". The current directory is this project's folder.` + (p.notes ? `\nProject notes:\n${p.notes}` : "") + "\n\n" : ""; };

  self.send = async () => {
    if (busy) { stopped = true; if (job) invoke("stop", { id: job }); return; }
    const text = input.value.trim(), atts = pending.slice();
    if (!text && !atts.length) return;
    input.value = ""; input.style.height = "auto"; pending = []; renderPending();
    if (!projectMode && !atts.length) {
      const m = text.match(/^(?:please\s+)?(?:can you\s+)?(?:create|make|start|add|new)\s+(?:a\s+|me\s+a\s+)?(?:new\s+)?project(?:\s+(?:called|named|for))?\s*[:\-]?\s*(.*)$/i);
      if (m) {
        const name = m[1].trim().replace(/^["']|["'.?!]+$/g, "");
        bubble(text, true);
        if (!name) { bubble("Sure. What should the project be called?", false); newProject(p => { bubble(`Created project "${p.name}". It's in the Projects tab.`, false); }); }
        else { const p = Store.newProject(name); bubble(`Created project "${p.name}". It's in the Projects tab, swipe or press Ctrl+3 to open it.`, false); showProjectDetail(p); }
        return;
      }
    }
    const prior = msgs.slice();
    const um = { u: true, t: text, a: atts }; msgs.push(um);
    if (conv.title === "New chat") { conv.title = (text || atts[0].name).split("\n")[0].slice(0, 40); setTitle(); }
    Store.saveConv(conv, msgs);
    bubble(text, true, atts);
    const out = bubble("…", false);
    const am = { u: false, t: "" }; msgs.push(am);
    setBusy(true); stopped = false;
    let acc = "";
    const show = () => { out.textContent = acc.trim() || "…"; list.scrollTop = list.scrollHeight; };
    const finish = () => {
      delete handlers[job]; job = null;
      am.t = acc.trim(); am.s = stopped; Store.saveConv(conv, msgs);
      if (stopped) markStopped(out, am.t);
      setBusy(false);
    };
    const visual = atts.filter(a => a.mime.startsWith("image/") || a.mime.startsWith("video/"));
    job = uid();
    try {
      if (visual.length) {
        const images = []; let notes = "";
        for (const a of visual) {
          const url = await blobUrl(a);
          if (a.mime.startsWith("image/")) { const b = await imgToJpegB64(url); if (b) images.push(b); }
          else { const f = await videoFramesB64(url); images.push(...f); notes += `\n(The video "${a.name}" is shown as ${f.length} frames in time order.)`; }
        }
        for (const a of atts.filter(x => !visual.includes(x)))
          notes += isText(a.name, a.mime) ? `\n\nAttached file ${a.name}:\n\`\`\`\n${await invoke("read_text", { path: a.path, max: 20000 })}\n\`\`\`` : `\n(Also attached: ${a.name}, ${a.mime})`;
        const messages = [];
        const pre = preamble(); if (pre) messages.push({ role: "system", content: pre.trim() });
        prior.slice(-20).forEach(m => m.t && messages.push({ role: m.u ? "user" : "assistant", content: m.t }));
        messages.push({ role: "user", content: (text || "What's in this?") + notes, images });
        handlers[job] = {
          out: line => {
            try {
              const j = JSON.parse(line);
              if (j.error) acc += /image|vision|multimodal/i.test(j.error) ? `${S.model} can't see images. Pick a vision model in Settings, like gemma3:4b, qwen2.5vl:7b or llava, then try again.\n(${j.error})` : `Ollama error: ${j.error}`;
              else acc += j.message?.content || "";
              show();
            } catch {}
          },
          done: p => { if (p.error && !stopped && !acc) { acc = `Couldn't reach Ollama: ${p.error}`; show(); } finish(); },
        };
        await invoke("http_stream", { id: job, url: S.host + "/api/chat", body: JSON.stringify({ model: S.model, stream: true, messages }), event: "chat-line" });
      } else {
        let files = "";
        for (const a of atts) {
          files += isText(a.name, a.mime) ? `\n\nAttached file ${a.path}:\n\`\`\`\n${await invoke("read_text", { path: a.path, max: 12000 })}\n\`\`\`` : `\n\nAttached file: ${a.path} (${a.mime}). Use your tools to inspect it if needed.`;
        }
        const body = (text || "Please look at the attached file(s).") + files;
        const ctx = context(prior);
        const prompt = preamble() + (ctx ? `Earlier in this conversation:\n${ctx}\nNew message from the user:\n${body}` : body);
        handlers[job] = {
          out: chunk => { acc += chunk.replace(ansi, ""); show(); },
          done: p => { if (!stopped && p.code !== 0 && !acc.trim()) { acc = `claw stopped (code ${p.code}). Is Ollama running and ${S.model} downloaded?`; show(); } finish(); },
        };
        await invoke("ensure_dir", { path: workDir() });
        await invoke("claw_run", { id: job, prompt, model: S.model, host: S.host, cwd: workDir() });
      }
    } catch (e) { acc = "Error: " + e; show(); finish(); }
  };
  return self;
}

// ---------- settings ----------
function openSettings() {
  const host = h("input", { class: "field", placeholder: "http://127.0.0.1:11434" }); host.value = S.host;
  const model = h("input", { class: "field", placeholder: "llama3.2:3b" }); model.value = S.model;
  const prog = h("progress", { max: 100, value: 0, class: "hidden" }), pst = h("div", { class: "muted" }), installed = h("div", { style: "white-space:pre-line;font-size:13px" }, "…");
  const ram = SYS ? SYS.memTotal / 1e9 : 0;
  const tip = (ram >= 24 ? `This PC has ${ram.toFixed(0)} GB RAM: it can run 14B models like qwen2.5-coder:14b; 7–8B ones are faster.`
    : ram >= 12 ? `This PC has ${ram.toFixed(0)} GB RAM: 7–8B models like qwen2.5-coder:7b or llama3.1:8b run well.`
    : `Best with 1–3B models like llama3.2:3b or qwen2.5-coder:1.5b.`) + " To show it photos or videos, use a vision model like gemma3:4b or qwen2.5vl:7b.";
  const save = () => { P.set("host", host.value.trim() || "http://127.0.0.1:11434"); P.set("model", model.value.trim() || "llama3.2:3b"); pollStatus(); };
  const refresh = async () => { try { const m = JSON.parse(await invoke("http_get", { url: S.host + "/api/tags" })).models || []; installed.textContent = m.map(x => `${x.name}  ·  ${(x.size / 1e9).toFixed(1)} GB`).join("\n") || "None yet"; } catch { installed.textContent = "Ollama not reachable"; } };
  const pull = h("button", { class: "btn-accent", style: "margin:12px 0 4px", onclick: async () => {
    save(); prog.classList.remove("hidden"); pull.disabled = true; const id = uid();
    handlers[id] = {
      out: line => { try { const j = JSON.parse(line); if (j.error) pst.textContent = j.error; else { pst.textContent = j.total ? `${j.status}  ${Math.round(j.completed / 1048576)} / ${Math.round(j.total / 1048576)} MB` : j.status; if (j.total) prog.value = j.completed * 100 / j.total; } } catch {} },
      done: p => { delete handlers[id]; pull.disabled = false; prog.classList.add("hidden"); if (p.error) pst.textContent = "Download failed: " + p.error; refresh(); pollStatus(); },
    };
    invoke("http_stream", { id, url: S.host + "/api/pull", body: JSON.stringify({ name: S.model }), event: "pull-line" });
  } }, "Download model");
  const body = h("div", {}, lbl("Ollama address"), host, lbl("Model"), model, h("div", { class: "muted", style: "margin-top:8px" }, tip), pull, prog, pst,
    h("button", { class: "chip", style: "margin-top:10px", onclick: () => invoke("start_ollama").then(() => { pst.textContent = "Starting Ollama…"; setTimeout(() => { refresh(); pollStatus(); }, 2000); }).catch(e => pst.textContent = String(e)) }, "Start Ollama on this PC"),
    lbl("Installed models"), installed);
  modal("Settings", body, [["Done", save, true]]);
  refresh();
}

// ---------- look ----------
function openLook() {
  const body = h("div");
  const cur = P.get("theme", "Ember");
  const rerender = () => { applyLook(); openLook(); };
  body.append(lbl("Theme"), swatches([...Object.entries(THEMES).map(([n, c]) => [n, `linear-gradient(135deg, ${c[1]}, ${c[5]})`]), ["Custom accent", `linear-gradient(135deg, #1c1c20, ${P.get("customAccent", "#7c9cff")})`]], cur, n => {
    if (n === "Custom accent") { const ci = h("input", { type: "color" }); ci.value = P.get("customAccent", "#7c9cff"); ci.oninput = () => { P.set("customAccent", ci.value); P.set("theme", n); applyLook(); }; ci.onchange = rerender; ci.click(); }
    else { P.set("theme", n); rerender(); }
  }));
  const w = P.get("wall", "None");
  body.append(lbl("Wallpaper"), swatches([["None", "var(--bg)"], ...Object.entries(GRADIENTS).map(([n, g]) => [n, `linear-gradient(${g[0]}, ${g[1]})`]), ["Your photo", "var(--surface2)"]], w, n => {
    if (n === "Your photo") pickWallFile("image/*", "wallPath", n); else { P.set("wall", n); rerender(); }
  }));
  body.append(lbl("Animated wallpaper"), swatches([...Object.entries(ANIMATED()).map(([n, [, bg, cols]]) => [n, `linear-gradient(135deg, ${cols[0]}, ${bg}, ${cols[cols.length - 1]})`]), ["Your video", "var(--surface2)"]], w, n => {
    if (n === "Your video") pickWallFile("video/*", "wallVideo", n); else { P.set("wall", n); rerender(); }
  }));
  body.append(...slider("Wallpaper dimming", "dim", 45, 90), ...slider("Wallpaper blur (frosted glass)", "blur", 0, 40));
  body.append(...slider("Chat bubble opacity", "bubbleOpacity", 100, 100));
  const gs = h("input", { type: "checkbox" }); gs.checked = P.get("glass", false); gs.onchange = () => { P.set("glass", gs.checked); applyLook(); };
  body.append(lbl("Liquid glass"), h("label", { class: "switch" }, "Glass bubbles, cards and bars", gs));
  const tints = Object.entries(TINTS).map(([n, c]) => [n, n === "Theme" ? "var(--accent)" : `linear-gradient(135deg, ${c}e6, ${c}5a)`]);
  tints.push(["Custom", `linear-gradient(135deg, ${P.get("glassCustom", "#78c8ff")}, transparent)`]);
  body.append(lbl("Glass tint"), swatches(tints, P.get("glassTint", "Clear"), n => {
    P.set("glass", true);
    if (n === "Custom") { const ci = h("input", { type: "color" }); ci.value = P.get("glassCustom", "#78c8ff"); ci.oninput = () => { P.set("glassCustom", ci.value); P.set("glassTint", "Custom"); applyLook(); }; ci.onchange = rerender; ci.click(); }
    else { P.set("glassTint", n); rerender(); }
  }));
  body.append(...slider("Glass tint strength", "glassStrength", 30, 100));
  modal("Look", body);
}
function pickWallFile(accept, key, wallName) {
  const f = h("input", { type: "file", accept });
  f.onchange = async () => {
    const file = f.files[0]; if (!file) return;
    try { const path = await invoke("save_file", { dir: PATHS.data + "/wallpapers", name: file.name, b64: await fileB64(file) }); P.set(key, path); P.set("wall", wallName); applyLook(); openLook(); }
    catch (e) { modal("Couldn't set wallpaper", h("div", {}, String(e))); }
  };
  f.click();
}

// ---------- boot ----------
let chatPane, projPane;
(async () => {
  try { PATHS = await invoke("paths"); PATHS.data = String(PATHS.data).replace(/\\/g, "/"); PATHS.workspace = String(PATHS.workspace).replace(/\\/g, "/"); } catch {}
  try { SYS = await invoke("sys_info"); } catch {}
  applyLook();
  statusEls.push([$("#topDot"), null]);
  $("#btnSettings").onclick = openSettings; $("#btnLook").onclick = openLook;
  chatPane = ChatPane($("#chat"), false);
  projPane = ChatPane($("#projChat"), true);
  const last = Store.convs.find(c => c.id === P.get("curChat", "") && !c.project);
  chatPane.open(last || Store.newConv(null));
  const cp = Store.project(P.get("curProject", null));
  if (cp) showProjectDetail(cp); else showProjectList();
  go(tab, false);
  renderHome();
  if (PATHS.clawFound === false) chatPane.input.placeholder = "claw binary missing from this install";
  // Try to start Ollama once if it isn't running locally.
  try { await invoke("http_get", { url: S.host + "/api/version" }); } catch { if (/127\.0\.0\.1|localhost/.test(S.host)) invoke("start_ollama").catch(() => {}); }
  pollStatus(); setInterval(pollStatus, 4000);
  addEventListener("keydown", e => { if (e.key === "Escape") closeModal(); });
})();

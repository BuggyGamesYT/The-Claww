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
// Displacement map for the refraction filter: edges of a rounded rect push pixels inward (R = x, G = y).
let lensDone = false;
function ensureLensMap() {
  if (lensDone) return; lensDone = true;
  const N = 256, c = h("canvas"); c.width = c.height = N; const ctx = c.getContext("2d"); const img = ctx.createImageData(N, N);
  const r = 0.22, band = 0.2;
  const sd = (x, y) => { const qx = Math.abs(x) - 0.5 + r, qy = Math.abs(y) - 0.5 + r; return Math.min(Math.max(qx, qy), 0) + Math.hypot(Math.max(qx, 0), Math.max(qy, 0)) - r; };
  for (let j = 0; j < N; j++) for (let i = 0; i < N; i++) {
    const x = (i + 0.5) / N - 0.5, y = (j + 0.5) / N - 0.5, d = sd(x, y), e = 1 / N;
    let nx = sd(x + e, y) - sd(x - e, y), ny = sd(x, y + e) - sd(x, y - e); const l = Math.hypot(nx, ny) || 1; nx /= l; ny /= l;
    const t = Math.min(Math.max(-d / band, 0), 1), k = Math.pow(1 - t, 2.2);
    const o = (j * N + i) * 4; img.data[o] = 128 + nx * k * 127; img.data[o + 1] = 128 + ny * k * 127; img.data[o + 2] = 128; img.data[o + 3] = 255;
  }
  ctx.putImageData(img, 0, 0);
  const fe = document.getElementById("lens-map"); fe.setAttribute("href", c.toDataURL()); fe.setAttributeNS("http://www.w3.org/1999/xlink", "xlink:href", c.toDataURL());
}
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
  r.setProperty("--lg-blur", (P.get("glassBlur", 45) * 0.32).toFixed(1) + "px");
  r.setProperty("--lg-rim", (P.get("glassRim", 30) / 100).toFixed(2));
  const liquid = P.get("glass", false) && P.get("glassStyle", "Liquid") === "Liquid";
  document.body.classList.toggle("glass", P.get("glass", false) && !liquid);
  document.body.classList.toggle("liquid", liquid);
  document.body.classList.toggle("svgbf", liquid && /Chrome|Edg\//.test(navigator.userAgent));
  if (liquid) ensureLensMap();
  document.body.classList.toggle("light-bubbles", P.get("bubbleOpacity", 100) < 60);
  // wallpaper
  const wall = $("#wall"), w = P.get("wall", "None");
  if (wallAnim) { cancelAnimationFrame(wallAnim.raf); wallAnim = null; }
  wall.innerHTML = ""; wall.style.background = "";
  document.body.classList.toggle("has-wall", w !== "None");
  if (GRADIENTS[w]) wall.style.background = `linear-gradient(${GRADIENTS[w][0]}, ${GRADIENTS[w][1]})`;
  else if (ANIMATED()[w]) startAnimated(wall, ...ANIMATED()[w]);
  else if (w === "Your photo" && P.get("wallPath", "")) wall.append(h("img", { src: fileSrc(P.get("wallPath", "")) }));
  else if (w === "Your video" && P.get("wallVideo", "")) {
    // Crash guard: if the app died while a video wallpaper was starting, the next launch skips it.
    if (P.get("videoBoot", 0) >= 2) { P.set("wall", "None"); P.set("videoBoot", 0); setTimeout(() => modal("Video wallpaper turned off", h("div", {}, "The app closed while starting your video wallpaper, so it's been switched off. On Arch, install video codecs with: sudo pacman -S gst-libav gst-plugins-good gst-plugins-bad, then pick the video again in Look.")), 800); return applyLook(); }
    P.set("videoBoot", P.get("videoBoot", 0) + 1);
    const vp = P.get("wallVideo", "");
    const v = h("video", { autoplay: "", loop: "", playsinline: "" }); v.muted = true;
    // WebKitGTK's media player (GStreamer) can't stream from Tauri's asset:// scheme and shows black, so on Linux play it from a blob URL.
    if (/Linux/.test(navigator.userAgent) && !/Android/.test(navigator.userAgent)) {
      (wallBlob.path === vp ? Promise.resolve(wallBlob.url) : blobUrl({ path: vp, mime: mimeOf(vp) }).then(u => { if (wallBlob.url) URL.revokeObjectURL(wallBlob.url); wallBlob = { path: vp, url: u }; return u; }))
        .then(u => { v.src = u; v.play().catch(() => {}); }).catch(() => { P.set("videoBoot", 0); v.remove(); });
    } else v.src = fileSrc(vp);
    v.addEventListener("playing", () => setTimeout(() => P.set("videoBoot", 0), 4000), { once: true });
    v.addEventListener("error", () => { P.set("videoBoot", 0); v.remove(); wall.style.background = "var(--bg)"; }, { once: true });
    wall.append(v);
  }
}
// Don't decode the video wallpaper while the window is minimized or hidden.
document.addEventListener("visibilitychange", () => { const v = document.querySelector("#wall video"); if (v) document.hidden ? v.pause() : v.play().catch(() => {}); });

let wallBlob = { path: "", url: "" };
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

// Wallpaper and glass settings, cleared by "Reset wallpaper & glass" (Look), Ctrl+Shift+R or --reset-look. Chats and projects stay.
const LOOK_KEYS = ["wall", "wallPath", "wallVideo", "videoBoot", "blur", "dim", "glass", "glassStyle", "glassBlur", "glassRim", "glassTint", "glassStrength", "bubbleOpacity"];
function resetLook() { LOOK_KEYS.forEach(k => localStorage.removeItem("claww." + k)); applyLook(); }

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
function toggle(label, key, def, onChange) {
  const c = h("input", { type: "checkbox" }); c.checked = P.get(key, def);
  c.onchange = () => { P.set(key, c.checked); onChange && onChange(c.checked); };
  return h("label", { class: "switch" }, label, c);
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
// Wide = landscape window at tablet/PC size: Home merges into the left of Chat (no Home tab) and project chats get a sidebar.
let wide = false;
const pageCount = () => wide ? 2 : 3;
const pageOf = t => wide ? Math.max(0, t - 1) : t;
function applyWide() {
  const w = innerWidth >= 900 && innerWidth > innerHeight;
  if (w === wide && document.body.dataset.wideInit) return;
  wide = w; document.body.dataset.wideInit = 1; document.body.classList.toggle("wide", wide);
  const home = $("#home");
  if (wide) { $("#chat").prepend(home); if (tab === 0) tab = 1; } else track.prepend(home);
  go(tab, false); if (wide) renderHome();
  if (wide && openProject && !$("#projChat").classList.contains("hidden")) renderProjSide();
}
addEventListener("resize", () => applyWide());
// Never let focus or find-in-page scroll the pager (or a page) sideways: the track's transform alone picks the tab.
[pager, ...document.querySelectorAll("#track > .page, #app")].forEach(el => el.addEventListener("scroll", () => { if (el.scrollLeft) el.scrollLeft = 0; }));
function go(i, anim = true) {
  tab = Math.max(wide ? 1 : 0, Math.min(2, i)); P.set("tab", tab);
  track.classList.toggle("drag", !anim);
  track.style.transform = `translateX(${-pageOf(tab) * 100 / pageCount()}%)`;
  document.querySelectorAll("#tabs button").forEach(b => b.classList.toggle("on", +b.dataset.tab === tab));
  if (tab === 0 || (wide && tab === 1)) renderHome();
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
      const n = pageCount(); let off = -pageOf(tab) * w + dx; const min = -(n - 1) * w;
      if (off > 0) off /= 3; if (off < min) off = min + (off - min) / 3;
      track.style.transform = `translateX(${off / n / w * 100}%)`;
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
  const fresh = P.get("newModelNames", []);
  if (fresh.length) home.append(h("div", { class: "card panel click", onclick: openModels }, h("div", { class: "hdr" }, "New AI models"),
    h("div", { class: "big" }, `${fresh.length} new model${fresh.length === 1 ? "" : "s"} released`), h("div", { class: "muted", style: "margin-top:4px" }, fresh.join(", "))));
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
        let bat = "";
        try { if (navigator.getBattery) { const b = await navigator.getBattery(); if (b && !(b.charging && b.level === 1 && b.chargingTime === 0)) bat = ` · Battery ${Math.round(b.level * 100)}%`; } } catch {}
        stats.textContent = `${SYS.cpu} · ${SYS.cores} threads\nRAM ${(SYS.memFree / 1e9).toFixed(1)} GB free of ${(SYS.memTotal / 1e9).toFixed(0)} GB${bat}`;
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
function openProjectChat(cv) { openProject = Store.project(cv.project); projPane.open(cv); showLayer(2); if (wide) renderProjSide(); }
function renderProjSide() {
  const side = $("#projSide"); side.innerHTML = "";
  side.append(h("div", { class: "row", style: "margin-bottom:12px" }, h("div", { class: "lbl grow", style: "margin:0" }, "Projects"),
    h("button", { class: "chip", onclick: () => newProject(p => openProjectChat(Store.ofProject(p.id)[0] || Store.newConv(p.id))) }, "+")));
  [...Store.projects].sort((a, b) => b.updated - a.updated).forEach(p => {
    const on = openProject && p.id === openProject.id;
    side.append(h("div", { class: "side-proj" + (on ? " on" : ""), title: "Click to open · right-click for project details",
      onclick: () => openProjectChat(Store.ofProject(p.id)[0] || Store.newConv(p.id)), oncontextmenu: e => { e.preventDefault(); showProjectDetail(p); } }, p.name));
    if (on) Store.ofProject(p.id).forEach(cv => side.append(h("div", { class: "side-chat" + (projPane.conv && projPane.conv.id === cv.id ? " on" : ""), onclick: () => openProjectChat(cv) }, cv.title)));
  });
}
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
const MIC = `<svg viewBox="0 0 18 18" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><rect x="6" y="1.4" width="6" height="9.6" rx="3"/><path d="M3 8.4a6 6 0 0 0 12 0M9 14.4v2.2"/></svg>`;
const PLUS = `<svg viewBox="0 0 18 18" fill="none" stroke="currentColor" stroke-width="2.7" stroke-linecap="round"><path d="M9 1.4v15.2M1.4 9h15.2"/></svg>`;
const ansi = /\u001B\[[0-9;?]*[ -\/]*[@-~]/g;
const handlers = {};  // job id -> {out, done}
function clawStatus(js) {
  let u; try { u = JSON.parse(js); } catch { return null; }
  let i = u.input; if (typeof i === "string") { try { i = JSON.parse(i); } catch { i = {}; } } i = i || {};
  const short = (s, n) => { s = String(s || "").replace(/\s+/g, " ").trim(); return s.length > n ? s.slice(0, n - 1) + "…" : s; };
  const file = p => String(p || "").split(/[\\/]/).pop() || "a file";
  const path = i.file_path || i.filePath || i.path;
  switch (u.name) {
    case "WebSearch": case "web_search": return `Searching the web for ${short(i.query, 50)}`;
    case "WebFetch": case "web_fetch": return `Reading ${short(String(i.url || "").replace(/^https?:\/\/(www\.)?/, "").split("/")[0], 40)}`;
    case "bash": case "Bash": return "Using the terminal";
    case "read_file": case "Read": return `Reading ${short(file(path), 40)}`;
    case "write_file": case "Write": return `Writing ${short(file(path), 40)}`;
    case "edit_file": case "Edit": return `Editing ${short(file(path), 40)}`;
    case "glob_search": case "Glob": return "Looking for files";
    case "grep_search": case "Grep": return "Searching your code";
    case "TodoWrite": return "Planning the steps";
    case "Agent": case "Task": return "Handing off a sub-task";
    default: return `Using ${u.name}`;
  }
}
function clawReply(raw) {
  let j = null;
  for (const l of raw.split("\n").reverse()) {
    const t = l.trim(); if (!t.startsWith("{")) continue;
    try { const o = JSON.parse(t); if ("message" in o) { j = o; break; } } catch {}
  }
  if (!j) return raw.trim();
  const short = (s, n = 70) => { s = String(s || "").replace(/\s+/g, " ").trim(); return s.length > n ? s.slice(0, n - 1) + "…" : s; };
  const steps = [...new Set((j.tool_uses || []).map(u => {
    let i = u.input; if (typeof i === "string") { try { i = JSON.parse(i); } catch { i = {}; } } i = i || {};
    const path = i.file_path || i.filePath || i.path;
    switch (u.name) {
      case "WebSearch": case "web_search": return `🔎 Searched the web: ${short(i.query)}`;
      case "WebFetch": case "web_fetch": return `🌐 Read ${short(i.url)}`;
      case "bash": case "Bash": return `⌨️ Ran ${short(i.command, 60)}`;
      case "read_file": case "Read": return `📄 Read ${short(path)}`;
      case "write_file": case "Write": return `✏️ Wrote ${short(path)}`;
      case "edit_file": case "Edit": return `📝 Edited ${short(path)}`;
      case "glob_search": case "Glob": case "grep_search": case "Grep": return `🔎 Searched files: ${short(i.pattern)}`;
      case "TodoWrite": return "🗒️ Updated the to-do list";
      default: return `🔧 Used ${u.name}`;
    }
  }))];
  void steps;
  return String(j.message || "").trim();
}
listen("claw-out", p => handlers[p.id]?.out(p.chunk));
listen("claw-done", p => handlers[p.id]?.done(p));
listen("chat-line", p => handlers[p.id]?.out(p.line));
listen("chat-line-done", p => handlers[p.id]?.done(p));
listen("pull-line", p => handlers[p.id]?.out(p.line));
listen("pull-line-done", p => handlers[p.id]?.done(p));


// ---------- model advisor: right model for the task ----------
const Advisor = {
  LABEL: { code: "coding", vision: "photos and video", reason: "math and reasoning", write: "writing", chat: "general chat" },
  CAT: {"code": [["qwen2.5-coder:32b", 20, 24], ["qwen2.5-coder:14b", 9, 12], ["qwen2.5-coder:7b", 4.7, 6.5], ["qwen2.5-coder:3b", 1.9, 3.5], ["qwen2.5-coder:1.5b", 1, 2.2], ["qwen2.5-coder:0.5b", 0.4, 1.2]], "vision": [["qwen2.5vl:32b", 21, 26], ["gemma3:27b", 17, 22], ["gemma3:12b", 8.1, 11], ["qwen2.5vl:7b", 6, 8], ["gemma3:4b", 3.3, 5], ["qwen2.5vl:3b", 3.2, 4.5], ["moondream", 1.7, 2.5]], "reason": [["qwen3:32b", 20, 24], ["deepseek-r1:14b", 9, 12], ["qwen3:14b", 9.3, 12], ["qwen3:8b", 5.2, 7], ["deepseek-r1:8b", 5.2, 7], ["qwen3:4b", 2.6, 4], ["qwen3:1.7b", 1.4, 2.2], ["deepseek-r1:1.5b", 1.1, 2]], "write": [["gemma3:27b", 17, 22], ["gemma3:12b", 8.1, 11], ["llama3.1:8b", 4.9, 7], ["gemma3:4b", 3.3, 5], ["llama3.2:3b", 2, 3.5], ["gemma3:1b", 0.8, 1.5]], "chat": [["qwen3:14b", 9.3, 12], ["gemma3:12b", 8.1, 11], ["qwen3:8b", 5.2, 7], ["llama3.1:8b", 4.9, 7], ["gemma3:4b", 3.3, 5], ["qwen3:4b", 2.6, 4], ["llama3.2:3b", 2, 3.5], ["llama3.2:1b", 1.3, 2], ["gemma3:1b", 0.8, 1.5]]},
  vision: /^(gemma3:(?!1b)|qwen2\.5vl|qwen3-vl|llava|bakllava|moondream|llama3\.2-vision|minicpm-v|granite3\.2-vision|mistral-small3\.[12])/,
  task(msg, hasVisual, names) {
    if (hasVisual) return "vision";
    const m = msg.toLowerCase();
    if (names.some(n => /\.(kt|java|rs|py|js|ts|c|cpp|h|go|sh|html|css|sql|json|xml|ya?ml|toml|ps1|bat)$/i.test(n)) || m.includes("```") ||
      /\b(code|coding|function|bug|debug|stack ?trace|compile|compiler|python|kotlin|java|rust|javascript|typescript|c\+\+|golang|sql|regex|api|script|bash|shell|html|css|refactor|unit test|git|gradle|npm|cargo|class|method|variable|exception|segfault|syntax)\b/.test(m)) return "code";
    if (/\b(math|solve|calculate|compute|prove|proof|equation|algebra|calculus|integral|derivative|probability|statistics|logic|puzzle|riddle|step by step|reason|how many|percent)\b/.test(m) || /\d+\s*[-+*/^×÷]\s*\d+/.test(m)) return "reason";
    if (/\b(write|rewrite|essay|story|poem|lyrics|email|letter|cover letter|blog|article|summari[sz]e|summary|translate|paraphrase|proofread|caption|speech)\b/.test(m)) return "write";
    return "chat";
  },
  advise(task, installed, current, ramGb, share) {
    const fits = (this.CAT[task] || this.CAT.chat).filter(e => e[2] <= ramGb * share).map(([name, gb, ram]) => ({ name, gb, ram }));
    const inst = new Set(installed.map(normName));
    const rank = n => { const i = fits.findIndex(e => normName(e.name) === normName(n)); return i < 0 ? Infinity : i; };
    let cur = rank(current);
    const curVisionOk = task !== "vision" || this.vision.test(normName(current));
    if (task === "vision" && curVisionOk && cur === Infinity) cur = fits.length;
    const bestInst = fits.find(e => inst.has(normName(e.name)));
    const instRank = bestInst ? rank(bestInst.name) : Infinity;
    let switchTo = null;
    if (bestInst && normName(bestInst.name) !== normName(current) && (instRank < cur || !curVisionOk)) switchTo = bestInst.name;
    else if (task === "vision" && !curVisionOk) switchTo = installed.find(n => this.vision.test(normName(n))) || null;
    const best = fits[0];
    const suggest = best && !inst.has(normName(best.name)) && rank(best.name) < Math.min(cur, instRank) ? best : null;
    return { task, switchTo, suggest };
  },
};
// ---------- per-task model picks ("Models for tasks" in Settings) ----------
const TASKS = [["code", "Coding"], ["chat", "General chat"], ["reason", "Math"], ["vision", "Vision (photos/video)"], ["write", "Writing"]];
const taskPick = task => (P.get("taskModels", {}) || {})[task] || "";
function setTaskPick(task, name) { const m = P.get("taskModels", {}) || {}; if (name) m[task] = name; else delete m[task]; P.set("taskModels", m); }

// ---------- photo/video context budget ----------
// Memory (num_ctx) for a photo or video chat: the Memory setting, or when it's Auto a roomier 16K (8K on smaller PCs).
const visionCtx = () => P.get("ctxLen", 0) || ((SYS ? SYS.memTotal / 1e9 : 8) >= 12 ? 16384 : 8192);
// How many video frames and how big, so every image fits in that memory with room for the reply.
function visionPlan(ctx, nImages, nVideos, textChars) {
  const frames = ctx >= 16384 ? 6 : ctx >= 8192 ? 4 : 3;
  const count = Math.max(1, nImages + nVideos * frames);
  const budget = Math.max(600, ctx * 0.55 - textChars / 3.5);
  // About one token per 28x28 pixel patch; 2.5x margin because models count image tokens differently.
  const side = Math.floor(28 * Math.sqrt(budget / count / 2.5));
  return { frames, imgMax: Math.max(224, Math.min(1024, side)), vidMax: Math.max(224, Math.min(768, side)) };
}
// "request (4304 tokens) exceeds the available context size (4096 tokens)" -> plain words and what to do.
function friendlyError(err) {
  const e = String(err || "");
  if (!/exceeds the available context|context size|context length|context window|too many tokens/i.test(e)) return null;
  const nums = e.match(/\((\d+) tokens\)[^(]*\((\d+) tokens\)/);
  return "That was more than the model can hold in its memory" + (nums ? ` (it needed ${nums[1]} tokens, ${nums[2]} were available)` : "") +
    ". Open Settings and set Memory to 16K or 32K (bigger uses more RAM), then send it again.";
}

async function installedModels() { try { return (JSON.parse(await invoke("http_get", { url: S.host + "/api/tags" })).models || []).map(m => m.name); } catch { return null; } }

// ---------- Speech to text: record in the window, transcribe on this PC with whisper.cpp ----------
const Speech = {
  state: "idle", pane: null, stream: null, ctx: null, node: null, src: null, chunks: [], rate: 48000,
  set(st, note) { this.state = st; if (this.pane) { this.pane.input.placeholder = note || this.pane.placeholder; this.pane.updateBtns(); } },
  fail(msg) { const p = this.pane; this.cleanup(); this.set("idle"); this.pane = null; if (p) p.updateBtns(); modal("Speech to text", h("div", {}, msg)); },
  async toggle(pane) {
    if (this.state === "rec" && this.pane === pane) return this.stop();
    if (this.state !== "idle") return;
    this.pane = pane;
    try {
      const st = await invoke("whisper_status");
      if (!st.bin) return this.fail("Speech to text isn't included in this build of The Claww.");
      if (!st.model) await this.download();
      await this.start();
    } catch (e) { this.fail(String(e && e.message || e)); }
  },
  download() {
    this.set("dl", "Getting the speech model (first time only)…");
    return new Promise((res, rej) => {
      const offs = [];
      const done = err => { offs.forEach(f => f.then(u => u())); err ? rej("Couldn't download the speech model: " + err) : res(); };
      offs.push(T.event.listen("whisper-dl", e => { const p = e.payload; if (p.total) this.set("dl", `Getting the speech model (first time only)… ${Math.round(p.done * 100 / p.total)}%`); }));
      offs.push(T.event.listen("whisper-dl-done", e => done(e.payload.error)));
      invoke("whisper_download").catch(err => done(String(err)));
    });
  },
  async start() {
    if (!navigator.mediaDevices?.getUserMedia) throw "This window can't reach the microphone.";
    try { this.stream = await navigator.mediaDevices.getUserMedia({ audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true } }); }
    catch (e) { throw "The microphone isn't available (" + (e && e.name || e) + "). Check that one is plugged in and that apps may use it."; }
    const AC = window.AudioContext || window.webkitAudioContext;
    this.ctx = new AC(); this.rate = this.ctx.sampleRate; this.chunks = [];
    this.src = this.ctx.createMediaStreamSource(this.stream);
    this.node = this.ctx.createScriptProcessor(4096, 1, 1);
    this.node.onaudioprocess = e => { if (this.state === "rec") this.chunks.push(new Float32Array(e.inputBuffer.getChannelData(0))); };
    this.src.connect(this.node); this.node.connect(this.ctx.destination);
    if (this.ctx.state === "suspended") await this.ctx.resume().catch(() => {});
    this.set("rec", "Listening… click the mic again to stop");
  },
  cleanup() {
    try { this.src && this.src.disconnect(); this.node && this.node.disconnect(); } catch {}
    try { this.stream && this.stream.getTracks().forEach(t => t.stop()); } catch {}
    try { this.ctx && this.ctx.close(); } catch {}
    this.src = this.node = this.stream = this.ctx = null;
  },
  async stop() {
    const pane = this.pane, chunks = this.chunks, rate = this.rate;
    this.set("busy", "Turning your words into text…"); this.cleanup(); this.chunks = [];
    try {
      const pcm = resample16k(chunks, rate);
      if (pcm.length < 16000 * 0.3) throw "That was too short. Click the mic, speak, then click it again.";
      const text = await invoke("transcribe", { wav: wavB64(pcm) });
      this.set("idle"); this.pane = null; pane.updateBtns();
      if (text) pane.insertText(text); else modal("Speech to text", h("div", {}, "No words were heard. Try again a little closer to the mic."));
    } catch (e) { this.pane = pane; this.fail(String(e && e.message || e)); }
  },
};
function resample16k(chunks, rate) {
  const n = chunks.reduce((a, c) => a + c.length, 0), src = new Float32Array(n); let o = 0;
  chunks.forEach(c => { src.set(c, o); o += c.length; });
  if (rate === 16000) return src;
  const k = rate / 16000, out = new Float32Array(Math.floor(n / k));
  for (let i = 0; i < out.length; i++) {
    // Average the source samples this output sample covers (simple low-pass), so 48 kHz audio doesn't alias.
    const a = Math.floor(i * k), b = Math.min(n, Math.max(a + 1, Math.floor((i + 1) * k))); let s = 0;
    for (let j = a; j < b; j++) s += src[j];
    out[i] = s / (b - a);
  }
  return out;
}
function wavB64(pcm) {
  const buf = new ArrayBuffer(44 + pcm.length * 2), v = new DataView(buf);
  const str = (o, t) => { for (let i = 0; i < t.length; i++) v.setUint8(o + i, t.charCodeAt(i)); };
  str(0, "RIFF"); v.setUint32(4, 36 + pcm.length * 2, true); str(8, "WAVE"); str(12, "fmt "); v.setUint32(16, 16, true);
  v.setUint16(20, 1, true); v.setUint16(22, 1, true); v.setUint32(24, 16000, true); v.setUint32(28, 32000, true); v.setUint16(32, 2, true); v.setUint16(34, 16, true);
  str(36, "data"); v.setUint32(40, pcm.length * 2, true);
  for (let i = 0; i < pcm.length; i++) { const x = Math.max(-1, Math.min(1, pcm[i])); v.setInt16(44 + i * 2, x < 0 ? x * 0x8000 : x * 0x7fff, true); }
  const u = new Uint8Array(buf); let bin = "";
  for (let i = 0; i < u.length; i += 0x8000) bin += String.fromCharCode.apply(null, u.subarray(i, i + 0x8000));
  return btoa(bin);
}

function ChatPane(root, projectMode) {
  const self = {};
  let conv = null, msgs = [], pending = [], busy = false, job = null, stopped = false;
  const title = h("div", { class: "pane-title" });
  const dot = h("i"), stxt = h("span");
  statusEls.push([dot, stxt]);
  const SEARCH = `<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round"><circle cx="6.7" cy="6.7" r="5"/><path d="M10.5 10.5 15 15"/></svg>`;
  const sInput = h("input", { placeholder: projectMode ? "Search this chat" : "Search all chats" });
  const sCount = h("span", { class: "cnt" });
  const sBox = h("div", { class: "searchbox" }, h("span", { style: "display:grid;place-items:center;width:16px;flex:none;cursor:pointer", onclick: () => openSearch() }), sInput, sCount,
    projectMode ? h("button", { title: "Previous", onclick: () => jump(-1) }, "˄") : null, projectMode ? h("button", { title: "Next", onclick: () => jump(1) }, "˅") : null,
    h("button", { title: "Close search", onclick: () => closeSearch() }, "✕"));
  sBox.firstChild.innerHTML = SEARCH;
  const head = h("div", { class: "pane-head" },
    projectMode ? h("button", { class: "back", onclick: () => openProject ? showProjectDetail(openProject) : showProjectList() }, "‹") : null,
    h("div", { class: "grow" }, title, h("div", { class: "status", onclick: () => status.ok ? openSettings() : invoke("start_ollama", { ctxLen: P.get("ctxLen", 0) }).then(() => setTimeout(pollStatus, 1500)).catch(e => modal("Ollama", h("div", {}, String(e)))) }, dot, stxt)),
    sBox,
    projectMode ? null : h("button", { class: "chip", onclick: showHistory }, "Chats"),
    h("button", { class: "chip", onclick: () => { if (!busy) self.open(Store.newConv(projectMode ? conv.project : null)); } }, "New"));
  const list = h("div", { class: "msgs" });
  const results = h("div", { class: "results hidden" });
  let hits = [], hitIdx = 0, searching = false;
  let who = P.get("searchWho", "all");   // all = both, me = only mine, ai = only claw's
  const whoOk = user => who === "all" || (who === "me") === !!user;
  const whoRow = h("div", { class: "chips who hidden" });
  const renderWho = () => { whoRow.innerHTML = ""; [["all", "Both"], ["me", "Only mine"], ["ai", "Only AI"]].forEach(([k, l]) => whoRow.append(h("button", { class: "chip" + (who === k ? " on" : ""), onclick: () => { who = k; P.set("searchWho", k); renderWho(); runSearch(sInput.value.trim()); } }, l))); };
  renderWho();
  function openSearch() { if (searching) return; searching = true; head.classList.add("searching"); whoRow.classList.remove("hidden"); requestAnimationFrame(() => sBox.classList.add("open")); setTimeout(() => sInput.focus(), 120); }
  function closeSearch() {
    if (!searching) return; searching = false; sInput.value = ""; runSearch(""); whoRow.classList.add("hidden"); sBox.classList.remove("open");
    setTimeout(() => head.classList.remove("searching"), 260);
  }
  sInput.addEventListener("input", () => runSearch(sInput.value.trim()));
  sInput.addEventListener("keydown", e => { if (e.key === "Escape") closeSearch(); if (e.key === "Enter") jump(e.shiftKey ? -1 : 1); });
  const esc = t => t.replace(/[&<>]/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;" })[c]);
  const mark = (t, q) => { const e = esc(t); if (!q) return e; const pat = esc(q).replace(/[.*+?^${}()|[\]\\]/g, "\\$&"); return e.replace(new RegExp(pat, "gi"), m => `<mark>${m}</mark>`); };
  function clearMarks() { list.querySelectorAll(".bubble[data-raw]").forEach(b => { b.innerHTML = b.dataset.raw; delete b.dataset.raw; b.classList.remove("hit"); }); hits = []; }
  function findInChat(q, flash) {
    clearMarks(); sCount.textContent = ""; if (!q) return;
    hits = [...list.querySelectorAll(".bubble")].filter(b => whoOk(b.closest(".msg")?.classList.contains("user")) && b.textContent.toLowerCase().includes(q.toLowerCase()));
    hits.forEach(b => { b.dataset.raw = b.innerHTML; b.innerHTML = mark(b.textContent, q); });
    hitIdx = hits.length - 1; sCount.textContent = hits.length ? `${hitIdx + 1}/${hits.length}` : "0";
    if (hits.length) showHit();
    if (flash) setTimeout(() => { if (!searching) clearMarks(); }, 4000);
  }
  function showHit() { hits.forEach(b => b.classList.remove("hit")); const b = hits[hitIdx]; b.classList.add("hit"); b.scrollIntoView({ behavior: "smooth", block: "center" }); }
  function jump(d) { if (!projectMode || !hits.length) return; hitIdx = (hitIdx + d + hits.length) % hits.length; sCount.textContent = `${hitIdx + 1}/${hits.length}`; showHit(); }
  function runSearch(q) {
    if (projectMode) return findInChat(q);
    results.innerHTML = "";
    if (!q) { results.classList.add("hidden"); list.classList.remove("hidden"); sCount.textContent = ""; return; }
    results.classList.remove("hidden"); list.classList.add("hidden");
    let n = 0; const ql = q.toLowerCase();
    Store.regular().forEach(c => {
      const ms = Store.msgs(c); const m = ms.find(x => whoOk(x.u) && (x.t || "").toLowerCase().includes(ql));
      if (!(who === "all" && c.title.toLowerCase().includes(ql)) && !m) return; n++;
      const card = h("div", { class: "card panel click" });
      card.append(h("div", { class: "big" })); card.lastChild.innerHTML = mark(c.title, q);
      if (m) { const i = m.t.toLowerCase().indexOf(ql), from = Math.max(0, i - 40); const sn = h("div", { class: "muted", style: "margin-top:4px" }); sn.innerHTML = mark((m.u ? "You: " : "claw: ") + (from ? "…" : "") + m.t.slice(from, i + q.length + 90).replace(/\n/g, " "), q); card.append(sn); }
      card.append(h("div", { class: "muted", style: "font-size:11px;margin-top:4px" }, ago(c.updated)));
      card.onclick = () => { if (busy) return; closeSearch(); self.open(c); setTimeout(() => findInChat(q, true), 60); };
      results.append(card);
    });
    sCount.textContent = String(n);
    if (!n) results.append(h("div", { class: "muted", style: "text-align:center;margin-top:30px" }, `No chats mention "${q}".`));
  }
  const pend = h("div", { class: "pending hidden" });
  const input = h("textarea", { rows: 1, placeholder: projectMode ? "Message claw about this project" : "Message claw" });
  const picker = h("input", { type: "file", multiple: "", class: "hidden" });
  const plus = h("button", { class: "cbtn plus", title: "Attach photos, videos or files", onclick: () => picker.click() }); plus.innerHTML = PLUS;
  const sendBtn = h("button", { class: "cbtn send", title: "Send", style: "color:var(--on-accent)", onclick: () => self.send() });
  // Mic sits where the send arrow is while the box is empty; typing (or attaching) swaps it back to send.
  const micBtn = h("button", { class: "cbtn mic", title: "Speak instead of typing", onclick: () => Speech.toggle(self) }); micBtn.innerHTML = MIC;
  const updateBtns = () => {
    const mine = Speech.pane === self && Speech.state !== "idle";
    const showMic = mine || (!busy && !input.value.trim() && !pending.length);
    micBtn.classList.toggle("hidden", !showMic); sendBtn.classList.toggle("hidden", showMic);
    micBtn.classList.toggle("rec", mine && Speech.state === "rec");
    micBtn.classList.toggle("wait", mine && Speech.state !== "rec");
  };
  const setBusy = b => { busy = b; sendBtn.innerHTML = b ? `<div class="stopsq"></div>` : ARROW; sendBtn.title = b ? "Stop" : "Send"; updateBtns(); };
  setBusy(false);
  root.append(h("div", { class: "pane" }, head, whoRow, list, results, h("div", { class: "composer-wrap" }, h("div", { class: "composer panel" }, pend, h("div", { class: "bar" }, input, plus, micBtn, sendBtn)), picker)));
  self.input = input;
  self.updateBtns = updateBtns;
  self.placeholder = input.placeholder;
  // Words from speech go into the box (after anything already typed).
  self.insertText = t => { t = (t || "").trim(); if (!t) return; input.value = (input.value.trim() ? input.value.replace(/\s*$/, " ") : "") + t; input.dispatchEvent(new Event("input")); input.focus(); };
  input.addEventListener("input", () => { input.style.height = "auto"; input.style.height = Math.min(160, input.scrollHeight) + "px"; updateBtns(); });
  // Up/Down recall your earlier messages in this chat (terminal-style). Only when the caret is on the first/last line, so multi-line editing still works.
  let hist = null, histDraft = "";
  const recall = t => { input.value = t; input.dispatchEvent(new Event("input")); const n = t.length; input.setSelectionRange(n, n); };
  input.addEventListener("keydown", e => {
    if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); hist = null; self.send(); return; }
    if ((e.key !== "ArrowUp" && e.key !== "ArrowDown") || e.shiftKey || e.altKey || e.ctrlKey || e.metaKey) return;
    const v = input.value, c = input.selectionStart;
    if (input.selectionEnd !== c) return;
    const sent = (msgs || []).filter(m => m.u && (m.t || "").trim()).map(m => m.t);
    if (e.key === "ArrowUp") {
      if (v.slice(0, c).includes("\n") || !sent.length) return;
      if (hist === null) { histDraft = v; hist = sent.length; }
      if (hist === 0) return;
      e.preventDefault(); hist--; recall(sent[hist]);
    } else {
      if (hist === null || v.slice(c).includes("\n")) return;
      e.preventDefault(); hist++;
      if (hist >= sent.length) { hist = null; recall(histDraft); } else recall(sent[hist]);
    }
  });
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
    pend.innerHTML = ""; pend.classList.toggle("hidden", !pending.length); updateBtns();
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
    self.conv = c;
    if (searching) closeSearch();
    conv = c; msgs = Store.msgs(c); hist = null; pending = []; renderPending(); list.innerHTML = "";
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
    input.value = ""; input.style.height = "auto"; pending = []; renderPending(); updateBtns();
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
    // Right model for the task: auto-switch to a better installed one (if enabled), suggest a better download.
    const visualPre = atts.some(a => a.mime.startsWith("image/") || a.mime.startsWith("video/"));
    let runModel = S.model, adv = null;
    const inst = await installedModels();
    const task = Advisor.task(text, visualPre, atts.map(a => a.name));
    // A model picked for this kind of task in Settings wins; "Auto" keeps the right-model-for-the-task logic.
    const pick = taskPick(task);
    const picked = pick && (!inst || inst.some(n => normName(n) === normName(pick))) ? pick : null;
    const usingNote = m => out.parentNode.insertBefore(h("div", { class: "muted", style: "font-size:12px;margin:0 0 4px 6px" }, `Using ${m} for ${Advisor.LABEL[task]}`), out);
    if (picked) { runModel = picked; if (normName(picked) !== normName(S.model)) usingNote(runModel); }
    else {
      if (inst) adv = Advisor.advise(task, inst, S.model, SYS ? SYS.memTotal / 1e9 : 8, 0.75);
      if (adv && adv.switchTo && P.get("autoSwitch", false)) { runModel = adv.switchTo; usingNote(runModel); }
    }
    const show = () => { out.textContent = acc.trim() || "…"; list.scrollTop = list.scrollHeight; };
    const finish = () => {
      delete handlers[job]; job = null;
      am.t = acc.trim(); am.s = stopped; Store.saveConv(conv, msgs);
      if (stopped) markStopped(out, am.t);
      setBusy(false);
      if (adv && adv.suggest && !stopped) suggestCard(out, adv);
    };
    const visual = atts.filter(a => a.mime.startsWith("image/") || a.mime.startsWith("video/"));
    job = uid();
    try {
      if (visual.length) {
        const images = []; let notes = "";
        const ctx = visionCtx();
        const hist = []; let histChars = 0;
        for (const m of prior.slice(-20).reverse()) { if (!m.t) continue; if (histChars + m.t.length > ctx * 0.25 * 3.5) break; histChars += m.t.length; hist.unshift(m); }
        const plan = visionPlan(ctx, visual.filter(a => a.mime.startsWith("image/")).length, visual.filter(a => !a.mime.startsWith("image/")).length, histChars + text.length);
        for (const a of visual) {
          const url = await blobUrl(a);
          if (a.mime.startsWith("image/")) { const b = await imgToJpegB64(url, plan.imgMax); if (b) images.push(b); }
          else { const f = await videoFramesB64(url, plan.frames, plan.vidMax); images.push(...f); notes += `\n(The video "${a.name}" is shown as ${f.length} frames in time order.)`; }
        }
        for (const a of atts.filter(x => !visual.includes(x)))
          notes += isText(a.name, a.mime) ? `\n\nAttached file ${a.name}:\n\`\`\`\n${await invoke("read_text", { path: a.path, max: 20000 })}\n\`\`\`` : `\n(Also attached: ${a.name}, ${a.mime})`;
        const messages = [];
        const pre = preamble(); if (pre) messages.push({ role: "system", content: pre.trim() });
        hist.forEach(m => messages.push({ role: m.u ? "user" : "assistant", content: m.t }));
        messages.push({ role: "user", content: (text || "What's in this?") + notes, images });
        handlers[job] = {
          out: line => {
            try {
              const j = JSON.parse(line);
              if (j.error) acc += friendlyError(j.error) || (/image|vision|multimodal/i.test(j.error) ? `${runModel} can't see images. Pick a vision model in Settings, like gemma3:4b, qwen2.5vl:7b or llava, then try again.\n(${j.error})` : `Ollama error: ${j.error}`);
              else acc += j.message?.content || "";
              show();
            } catch {}
          },
          done: p => { if (p.error && !stopped && !acc) { acc = `Couldn't reach Ollama: ${p.error}`; show(); } finish(); },
        };
        await invoke("http_stream", { id: job, url: S.host + "/api/chat", body: JSON.stringify({ model: runModel, stream: true, messages, options: { num_ctx: ctx } }), event: "chat-line" });
      } else {
        let files = "";
        for (const a of atts) {
          files += isText(a.name, a.mime) ? `\n\nAttached file ${a.path}:\n\`\`\`\n${await invoke("read_text", { path: a.path, max: 12000 })}\n\`\`\`` : `\n\nAttached file: ${a.path} (${a.mime}). Use your tools to inspect it if needed.`;
        }
        const body = (text || "Please look at the attached file(s).") + files;
        const ctx = context(prior);
        const prompt = preamble() + (ctx ? `Earlier in this conversation:\n${ctx}\nNew message from the user:\n${body}` : body);
        // claw answers with one JSON line at the end: show only the reply plus a short line per tool used.
        let raw = "";
        acc = "Thinking…"; show(); out.classList.add("thinking");
        handlers[job] = {
          out: chunk => {
            raw += chunk;
            const evs = [...raw.matchAll(/CLAW_TOOL (\{.*\})\s*$/gm)];
            if (evs.length && !stopped) { const st = clawStatus(evs[evs.length - 1][1]); if (st) { acc = st + "…"; show(); } }
          },
          done: p => {
            out.classList.remove("thinking");
            acc = stopped ? "" : clawReply(raw.replace(/^.*CLAW_TOOL \{.*$/gm, "").replace(ansi, ""));
            if (!stopped && /exceeds the available context size/i.test(acc)) acc = friendlyError(acc) || acc;
            if (!stopped && p.code !== 0 && !acc.trim()) acc = `claw stopped (code ${p.code}). Is Ollama running and ${runModel} downloaded?`;
            show(); finish();
          },
        };
        await invoke("ensure_dir", { path: workDir() });
        await invoke("claw_run", { id: job, prompt, model: runModel, host: S.host, cwd: workDir() });
      }
    } catch (e) { acc = "Error: " + e; show(); finish(); }
  };
  function suggestCard(out, adv) {
    const e = adv.suggest;
    if (!P.get("suggestModels", true) || P.get("noSuggest", []).includes(e.name) || Date.now() < P.get("snooze:" + e.name, 0)) return;
    const what = Advisor.LABEL[adv.task];
    const card = h("div", { class: "suggest panel" },
      h("div", { style: "font-weight:600" }, `Better model for ${what}: ${e.name}`),
      h("div", { class: "muted", style: "font-size:12.5px;margin:4px 0 8px" }, `${e.gb.toFixed(1)} GB download, runs on this PC.` +
        (adv.switchTo && !P.get("autoSwitch", false) ? ` You already have ${adv.switchTo}, which is good for this. Turn on Auto-switch in Settings to use it automatically.` : "")));
    const gone = () => card.remove();
    card.append(h("div", { class: "row", style: "gap:8px" },
      h("button", { class: "chip", onclick: () => { DL.enqueue([e.name]); gone(); } }, "Download"),
      h("button", { class: "chip", onclick: () => { P.set("snooze:" + e.name, Date.now() + 7 * 864e5); gone(); } }, "Not now"),
      h("button", { class: "chip", onclick: () => { P.set("noSuggest", [...P.get("noSuggest", []), e.name]); gone(); } }, "Don't suggest")));
    out.parentNode.append(card); list.scrollTop = list.scrollHeight;
  }
  return self;
}

// ---------- settings ----------
// Memory (context length) for this PC's Ollama. Same choices as the Android app.
const MEM_OPTS = [[0, "Auto"], [8192, "8K"], [16384, "16K"], [32768, "32K"], [65536, "64K"], [131072, "128K"]];
const isLocalHost = u => /^(https?:\/\/)?(127\.0\.0\.1|localhost|\[::1\])(:|\/|$)/i.test(u.trim());
function memorySection(hostNow) {
  const row = h("div", { class: "memrow" }), note = h("div", { class: "muted", style: "margin-top:6px" }), st = h("div", { class: "muted", style: "margin-top:4px" });
  let applying = false;
  const paint = () => {
    const cur = P.get("ctxLen", 0); row.innerHTML = "";
    MEM_OPTS.forEach(([v, l]) => row.append(h("button", { class: "chip" + (v === cur ? " on" : ""), disabled: applying ? "" : null, onclick: () => apply(v) }, l)));
    const ram = SYS ? (SYS.memTotal / 1e9).toFixed(0) : "?";
    note.textContent = (cur === 0 ? "Auto lets Ollama pick." : `${(MEM_OPTS.find(o => o[0] === cur) || [0, cur])[1]} tokens of memory.`) +
      ` Bigger uses more RAM and can be slower; 32K suits web searches on ${ram} GB. Changing it restarts Ollama on this PC` +
      (/Linux/.test(navigator.userAgent) ? " (asks for your password once)." : ".") +
      (isLocalHost(hostNow()) ? "" : " Your Ollama address points to another computer: this only applies to this PC's Ollama.");
  };
  async function apply(v) {
    const prev = P.get("ctxLen", 0);
    if (applying || v === prev) return;
    applying = true; P.set("ctxLen", v); paint();
    st.style.color = ""; st.textContent = "Applying…";
    try { st.textContent = await invoke("set_ollama_memory", { ctxLen: v }); setTimeout(pollStatus, 2500); }
    catch (e) { P.set("ctxLen", prev); st.style.color = "#eb5a50"; st.textContent = "Couldn't change memory: " + e; }
    applying = false; paint();
  }
  paint();
  return [lbl("Memory · how much of the chat and web pages the model can hold"), row, note, st];
}
// "Models for tasks": pick an installed model per kind of task, or Auto.
function taskModelsSection() {
  const box = h("div", { class: "taskrows" });
  const render = names => {
    box.innerHTML = "";
    TASKS.forEach(([k, label]) => {
      const cur = taskPick(k);
      const sel = h("select", { class: "field" });
      sel.append(h("option", { value: "" }, "Auto"));
      const opts = [...new Set([...(names || []), ...(cur ? [cur] : [])])];
      opts.forEach(n => sel.append(h("option", { value: n }, n + (names && !names.some(x => normName(x) === normName(n)) ? " (not installed)" : ""))));
      sel.value = cur;
      sel.onchange = () => setTaskPick(k, sel.value);
      box.append(h("div", { class: "taskrow" }, h("span", {}, label), sel));
    });
  };
  render(null);
  return { els: [lbl("Models for tasks · Auto picks the right model for the task"), box,
    h("div", { class: "muted", style: "margin-top:6px" }, "When a message is about one of these, claw uses the model you pick here.")], render };
}
// GitHub: a personal access token lets claw work with your repos, issues and pull requests (GitHub's MCP server).
function githubSection() {
  const st = h("div", { class: "muted", style: "margin-top:6px" }), msg = h("div", { class: "muted", style: "margin-top:4px" });
  const f = h("input", { class: "field", type: "password", placeholder: "Paste a GitHub personal access token", autocomplete: "off", spellcheck: "false" });
  const paint = async () => { const on = await invoke("github_status").catch(() => false); st.textContent = on ? "Connected · claw can use GitHub." : "Not connected."; clr.classList.toggle("hidden", !on); };
  const run = async token => {
    msg.style.color = ""; msg.textContent = "Saving…";
    try { msg.textContent = await invoke("github_set", { token }); f.value = ""; }
    catch (e) { msg.style.color = "#eb5a50"; msg.textContent = "Couldn't save: " + e; }
    paint();
  };
  const save = h("button", { class: "chip", onclick: () => { if (f.value.trim()) run(f.value.trim()); } }, "Save");
  const clr = h("button", { class: "chip hidden", onclick: () => run("") }, "Remove");
  f.addEventListener("keydown", e => { if (e.key === "Enter" && f.value.trim()) run(f.value.trim()); });
  const link = h("a", { href: "#", onclick: e => { e.preventDefault(); invoke("open_url", { url: "https://github.com/settings/tokens" }).catch(() => {}); } }, "github.com/settings/tokens");
  paint();
  return [lbl("GitHub"), h("div", { class: "row" }, h("div", { class: "grow" }, f), save, clr), st, msg,
    h("div", { class: "muted", style: "margin-top:6px" }, "Make a token at ", link, " with the repo and workflow scopes. It stays on this PC, readable only by you.")];
}
function openSettings() {
  const host = h("input", { class: "field", placeholder: "http://127.0.0.1:11434" }); host.value = S.host;
  const model = h("input", { class: "field", placeholder: "llama3.2:3b" }); model.value = S.model;
  const prog = h("progress", { max: 100, value: 0, class: "hidden" }), pst = h("div", { class: "muted" }), installed = h("div", { class: "mlist-inst" }, "…");
  const mq = h("input", { class: "field", placeholder: "Search installed models" });
  let instList = null;
  const renderInst = () => {
    installed.innerHTML = "";
    if (!instList) { installed.append(h("div", { class: "muted" }, "Ollama not reachable")); return; }
    const q = mq.value.trim().toLowerCase(), l = instList.filter(x => !q || x.name.toLowerCase().includes(q));
    if (!l.length) installed.append(h("div", { class: "muted" }, instList.length ? `No installed model matches "${mq.value.trim()}".` : "None yet. Download one above."));
    l.forEach(x => { const on = normName(x.name) === normName(model.value.trim());
      installed.append(h("div", { class: "inst" + (on ? " on" : ""), title: x.name, onclick: () => { model.value = x.name; P.set("model", x.name); pollStatus(); renderInst(); } },
        h("span", { class: "grow" }, x.name), h("span", { class: "muted" }, `${(x.size / 1e9).toFixed(1)} GB · ${on ? "In use" : "Use"}`))); });
  };
  mq.addEventListener("input", renderInst);
  const ram = SYS ? SYS.memTotal / 1e9 : 0;
  const tip = (ram >= 24 ? `This PC has ${ram.toFixed(0)} GB RAM: it can run 14B models like qwen2.5-coder:14b; 7–8B ones are faster.`
    : ram >= 12 ? `This PC has ${ram.toFixed(0)} GB RAM: 7–8B models like qwen2.5-coder:7b or llama3.1:8b run well.`
    : `Best with 1–3B models like llama3.2:3b or qwen2.5-coder:1.5b.`) + " To show it photos or videos, use a vision model like gemma3:4b or qwen2.5vl:7b.";
  const save = () => { P.set("host", host.value.trim() || "http://127.0.0.1:11434"); P.set("model", model.value.trim() || "llama3.2:3b"); pollStatus(); };
  const tasks = taskModelsSection();
  const refresh = async () => { try { instList = (JSON.parse(await invoke("http_get", { url: S.host + "/api/tags" })).models || []).sort((a, b) => a.name.localeCompare(b.name)); } catch { instList = null; } renderInst(); tasks.render(instList && instList.map(x => x.name)); };
  const pull = h("button", { class: "btn-accent", style: "margin:12px 0 4px", onclick: async () => {
    save(); prog.classList.remove("hidden"); pull.disabled = true; const id = uid();
    handlers[id] = {
      out: line => { try { const j = JSON.parse(line); if (j.error) pst.textContent = j.error; else { pst.textContent = j.total ? `${j.status}  ${Math.round(j.completed / 1048576)} / ${Math.round(j.total / 1048576)} MB` : j.status; if (j.total) prog.value = j.completed * 100 / j.total; } } catch {} },
      done: p => { delete handlers[id]; pull.disabled = false; prog.classList.add("hidden"); if (p.error) pst.textContent = "Download failed: " + p.error; refresh(); pollStatus(); },
    };
    invoke("http_stream", { id, url: S.host + "/api/pull", body: JSON.stringify({ name: S.model }), event: "pull-line" });
  } }, "Download model");
  const body = h("div", {}, lbl("Ollama address"), host, lbl("Model in use"), model, lbl("Installed models · click one to use it"), mq, installed,
    lbl("Right model for the task"), toggle("Auto-switch to a good installed model for each task (coding, photos, math, writing)", "autoSwitch", false),
    toggle("Suggest a better model to download when one would do the task better", "suggestModels", true, v => { if (v) P.set("noSuggest", []); }), h("div", { class: "muted", style: "margin-top:8px" }, tip),
    ...tasks.els,
    ...memorySection(() => host.value.trim() || S.host), pull, prog, pst,
    h("button", { class: "chip", style: "margin-top:10px", onclick: () => invoke("start_ollama", { ctxLen: P.get("ctxLen", 0) }).then(() => { pst.textContent = "Starting Ollama…"; setTimeout(() => { refresh(); pollStatus(); }, 2000); }).catch(e => pst.textContent = String(e)) }, "Start Ollama on this PC"),
    h("button", { class: "chip", style: "margin:10px 0 0 8px", onclick: () => { closeModal(); openModels(); } }, "Browse & download any model"),
    ...githubSection());
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
  body.append(lbl("Glass style"), swatches([["Liquid", "linear-gradient(135deg, rgba(255,255,255,.85), var(--accent), rgba(255,255,255,.6))"], ["Frosted", "linear-gradient(rgba(255,255,255,.45), rgba(255,255,255,.15))"]],
    P.get("glassStyle", "Liquid"), n => { P.set("glassStyle", n); P.set("glass", true); rerender(); }));
  const tints = Object.entries(TINTS).map(([n, c]) => [n, n === "Theme" ? "var(--accent)" : `linear-gradient(135deg, ${c}e6, ${c}5a)`]);
  tints.push(["Custom", `linear-gradient(135deg, ${P.get("glassCustom", "#78c8ff")}, transparent)`]);
  body.append(lbl("Glass tint"), swatches(tints, P.get("glassTint", "Clear"), n => {
    P.set("glass", true);
    if (n === "Custom") { const ci = h("input", { type: "color" }); ci.value = P.get("glassCustom", "#78c8ff"); ci.oninput = () => { P.set("glassCustom", ci.value); P.set("glassTint", "Custom"); applyLook(); }; ci.onchange = rerender; ci.click(); }
    else { P.set("glassTint", n); rerender(); }
  }));
  body.append(...slider("Glass tint strength", "glassStrength", 30, 100));
  body.append(...slider("Glass blur", "glassBlur", 45, 100), ...slider("Glass outline", "glassRim", 30, 100));
  body.append(h("button", { class: "chip", style: "margin-top:16px", onclick: () => { resetLook(); openLook(); } }, "Reset wallpaper & glass"),
    h("div", { class: "muted", style: "margin-top:6px" }, "Keeps your theme, chats and projects. Shortcut: Ctrl+Shift+R."));
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


// ---------- Models: every free model (Ollama library + all GGUF on Hugging Face) ----------
const SUGGEST = [
  ["gemma3:1b", 0.815e9, "Tiny and quick, good for simple chat", 0], ["llama3.2:3b", 2.0e9, "Fast all-rounder", 0],
  ["qwen2.5-coder:3b", 1.9e9, "Coding help", 0], ["qwen3:4b", 2.5e9, "Thinks step by step, uses tools", 6],
  ["gemma3:4b", 3.3e9, "Sees photos and videos", 6], ["qwen2.5vl:7b", 6.0e9, "Vision: reads images and screenshots", 12],
  ["phi4-mini", 2.5e9, "Strong reasoning for its size", 6], ["llama3.1:8b", 4.9e9, "Smarter general model", 12],
  ["qwen2.5-coder:7b", 4.7e9, "Best coder for 12–16 GB", 12], ["qwen2.5-coder:14b", 9.0e9, "Big coder for 24 GB+", 24],
  ["gemma3:27b", 17e9, "Top quality, needs 32 GB+", 32],
];
const gb = b => b >= 1e9 ? (b / 1e9).toFixed(1) + " GB" : (b / 1e6).toFixed(0) + " MB";
const shortN = n => n >= 1e9 ? (n / 1e9).toFixed(1) + "B" : n >= 1e6 ? (n / 1e6).toFixed(1) + "M" : n >= 1e3 ? (n / 1e3).toFixed(1) + "K" : String(n);
const ageOf = t => { if (!t) return ""; const d = Math.floor((Date.now() - t) / 864e5); return d < 1 ? "today" : d < 2 ? "yesterday" : d < 30 ? d + "d ago" : d < 365 ? Math.floor(d / 30) + "mo ago" : Math.floor(d / 365) + "y ago"; };
const fetchText = url => invoke("http_get", { url, timeoutMs: 20000 });
const normName = n => { n = n.toLowerCase(); return n.split("/").pop().includes(":") ? n : n + ":latest"; };
const Catalog = {
  async ollama(q, page, newest) {
    const html = await fetchText(`https://ollama.com/search?q=${encodeURIComponent(q)}&p=${page + 1}&o=${newest ? "newest" : "popular"}`);
    const more = html.includes('data-has-more="true"'), out = [];
    html.split('<a href="/library/').slice(1).forEach(block => {
      const name = block.slice(0, block.indexOf('"'));
      if (!name || /[/:]/.test(name)) return;
      const body = block.split("</li>")[0];
      const desc = (body.match(/<p class="mt-1[^>]*>([^<]*)<\/p>/) || [])[1] || "";
      const caps = [...body.matchAll(/text-indigo-600[^>]*>([^<]+)</g)].map(m => m[1].trim());
      const sizes = [...body.matchAll(/bg-\[#ddf4ff\][^>]*>([^<]+)</g)].map(m => m[1].trim());
      if (!sizes.length && body.includes(">cloud<")) return;
      const dl = +((body.match(/title="([\d,]+) downloads"/) || [])[1] || "0").replace(/,/g, "");
      const date = Date.parse((body.match(/title="Updated ([A-Za-z]+ \d+, \d{4})"/) || [])[1] || "") || 0;
      out.push({ id: name, source: "ollama", title: name, desc: desc.replace(/&amp;/g, "&").replace(/&#39;/g, "'"), caps, stat: [dl ? shortN(dl) + " pulls" : "", sizes.join(" · ")].filter(Boolean).join(" · "), date, downloads: dl });
    });
    return [out, more];
  },
  async hf(q, page, newest) {
    const per = 30;
    const a = JSON.parse(await fetchText(`https://huggingface.co/api/models?search=${encodeURIComponent(q)}&filter=gguf&sort=${newest ? "trendingScore" : "likes"}&direction=-1&limit=${per * (page + 1)}`));
    const out = a.slice(page * per).map(o => {
      const tags = o.tags || [];
      const caps = []; if (o.pipeline_tag === "image-text-to-text" || tags.includes("vision")) caps.push("vision");
      if (tags.some(t => /code/i.test(t))) caps.push("code"); if (tags.some(t => /reason|think/i.test(t))) caps.push("thinking");
      const base = (tags.find(t => t.startsWith("base_model:") && !t.includes(":quantized:") && !t.includes(":finetune:")) || "").replace("base_model:", "");
      return { id: o.id, source: "hf", title: o.id.split("/")[1], desc: (base ? `Based on ${base} · ` : "") + "by " + o.id.split("/")[0], caps,
        stat: `${shortN(o.downloads || 0)} downloads · ${shortN(o.likes || 0)} likes`, date: Date.parse(o.createdAt || "") || 0, downloads: o.downloads || 0 };
    });
    if (newest) out.sort((x, y) => y.date - x.date);
    return [out, a.length >= per * (page + 1)];
  },
  async variants(e) {
    if (e.source === "ollama") {
      const html = await fetchText(`https://ollama.com/library/${encodeURIComponent(e.id)}/tags`); const seen = new Set();
      return [...html.matchAll(/href="\/library\/([^"]+:[^"]+)" class="md:hidden[\s\S]*?<\/span>\s*•\s*([\d.]+\s*[KMGT]?B)\s*•\s*([^<•]*)/g)].filter(m => !seen.has(m[1]) && seen.add(m[1]))
        .map(m => ({ pull: m[1], label: m[1].split(":")[1], bytes: parseSize(m[2]), note: m[3].trim().replace(/window$/, "").trim() }));
    }
    const files = JSON.parse(await fetchText(`https://huggingface.co/api/models/${e.id}/tree/main?recursive=true`)); const by = new Map();
    files.forEach(f => {
      if (f.type !== "file" || !/\.gguf$/i.test(f.path) || /mmproj/i.test(f.path)) return;
      const m = f.path.split("/").pop().match(/(?:^|[-_.])((?:I?Q\d(?:_[A-Z0-9]+)*)|BF16|F16|F32)(?=[-_.])/i); if (!m) return;
      const q = m[1].toUpperCase(); by.set(q, (by.get(q) || 0) + (f.size || f.lfs?.size || 0));
    });
    return [...by.entries()].sort((a, b) => a[1] - b[1]).map(([q, b]) => ({ pull: `hf.co/${e.id}:${q}`, label: q, bytes: b, note: q.startsWith("Q4_K_M") ? "recommended" : /^Q8|F16/.test(q) ? "highest quality" : "" }));
  },
};
function parseSize(s) { const m = s.match(/([\d.]+)\s*([KMGT]?)B/); return m ? +m[1] * ({ K: 1e3, M: 1e6, G: 1e9, T: 1e12 }[m[2]] || 1) : 0; }
function fitOf(bytes) {
  if (!bytes || !SYS) return null; const r = bytes * 1.2 / SYS.memTotal;
  return r < 0.6 ? ["Fits", "#50c878"] : r < 0.85 ? ["Tight fit", "#f0b43c"] : ["Too big", "#eb5a50"];
}

// Background download queue (keeps the PC awake while it runs).
const DL = { jobs: [], running: false, listeners: new Set() };
DL.notify = () => DL.listeners.forEach(f => f());
DL.enqueue = pulls => { pulls.forEach(p => { if (!DL.jobs.some(j => j.pull === p && /queued|running/.test(j.state))) DL.jobs.push({ pull: p, state: "queued", status: "Queued", done: 0, total: 0 }); }); DL.notify(); DL.run(); };
DL.cancel = pull => { const j = DL.jobs.find(j => j.pull === pull && /queued|running/.test(j.state)); if (!j) return; if (j.state === "running" && j.id) invoke("stop", { id: j.id }); j.state = "cancelled"; j.status = "Cancelled"; DL.notify(); };
DL.run = async () => {
  if (DL.running) return; DL.running = true; invoke("keep_awake", { on: true }).catch(() => {});
  let j;
  while ((j = DL.jobs.find(x => x.state === "queued"))) {
    j.state = "running"; j.status = "Starting"; j.id = uid(); DL.notify();
    for (let attempt = 1; j.state === "running"; attempt++) {
      const res = await new Promise(resolve => {
        let last = 0;
        handlers[j.id] = {
          out: line => { try { const o = JSON.parse(line); if (o.error) { j.err = o.error; return; } j.status = o.status; if (o.total) j.total = o.total; if (o.completed) j.done = o.completed; if (o.status === "success") j.ok = true; const n = Date.now(); if (n - last > 400) { last = n; DL.notify(); } } catch {} },
          done: p => { delete handlers[j.id]; resolve(p); },
        };
        invoke("http_stream", { id: j.id, url: S.host + "/api/pull", body: JSON.stringify({ name: j.pull, stream: true }), event: "pull-line" });
      });
      if (j.state === "cancelled") break;
      if (j.ok) { j.state = "done"; j.status = "Downloaded"; break; }
      if (j.err || attempt > 5) { j.state = "failed"; j.status = j.err || res.error || "Failed"; break; }
      j.status = `Connection lost, retrying (${attempt}/5)`; DL.notify(); await new Promise(r => setTimeout(r, 4000 * attempt)); j.id = uid();
    }
    DL.notify(); pollStatus();
  }
  DL.running = false; invoke("keep_awake", { on: false }).catch(() => {}); DL.notify();
};

// New-model watcher: checks every 6 hours while the app is open, shows NEW badges and a Home card.
async function checkNewModels(force) {
  if (!P.get("watchModels", true) && !force) return;
  if (!force && Date.now() - P.get("lastModelCheck", 0) < 6 * 36e5) return;
  let latest = [];
  try { latest = latest.concat((await Catalog.ollama("", 0, true))[0]); } catch {}
  try { latest = latest.concat((await Catalog.hf("", 0, true))[0].filter(e => e.downloads >= 1000)); } catch {}
  if (!latest.length) return;
  const seen = new Set(P.get("seenModels", [])), first = !seen.size;
  const fresh = latest.filter(e => !seen.has(e.source + ":" + e.id));
  latest.forEach(e => seen.add(e.source + ":" + e.id));
  P.set("seenModels", [...seen].slice(-3000)); P.set("lastModelCheck", Date.now());
  if (!first && fresh.length) { P.set("newModels", [...new Set([...P.get("newModels", []), ...fresh.map(e => e.source + ":" + e.id)])].slice(-200)); P.set("newModelNames", fresh.slice(0, 6).map(e => e.title)); if (tab === 0) renderHome(); }
}

function openModels() {
  document.getElementById("models")?.remove();
  const root = h("div", { id: "models" }); document.body.append(root);
  let source = P.get("modelSource", "all"), newest = P.get("modelNewest", true), best = P.get("modelPopular", false), query = "", page = 0, gen = 0;
  const selected = new Map(); let installed = new Set();
  const newSet = new Set(P.get("newModels", [])); P.set("newModels", []); P.set("newModelNames", []);
  const search = h("input", { class: "search", placeholder: "Search every model: qwen, llama, coder, vision…" });
  const filters = h("div", { class: "chips" });
  const dlBox = h("div"), picks = h("div"), results = h("div"), statusV = h("div", { class: "muted", style: "text-align:center;padding:14px" });
  const more = h("button", { class: "chip", style: "display:block;margin:10px auto 20px" }, "Load more");
  const go = h("button", { class: "btn-accent hidden", style: "margin:8px 0 0" });
  root.append(h("div", { class: "row" }, h("button", { class: "back", onclick: () => close() }, "‹"), h("h1", { class: "grow", style: "margin:0" }, "Models"),
      h("span", { class: "muted" }, SYS ? `RAM ${(SYS.memTotal / 1e9).toFixed(0)} GB` : "")),
    search, filters, h("div", { class: "mlist" }, dlBox, picks, results, statusV, more), go);
  const close = () => { DL.listeners.delete(renderDl); root.remove(); };
  const sec = t => h("div", { class: "sec" }, t);
  const updateGo = () => { go.classList.toggle("hidden", !selected.size); go.textContent = `Download ${selected.size} model${selected.size === 1 ? "" : "s"} · ${gb([...selected.values()].reduce((a, b) => a + b, 0))}`; };
  function vrow(pull, label, bytes, note) {
    const f = fitOf(bytes), inst = installed.has(normName(pull));
    const btn = h("button", { class: "selbtn" });
    const paint = () => { if (inst) { btn.className = "selbtn use"; btn.textContent = normName(S.model) === normName(pull) ? "In use" : "Use"; } else if (selected.has(pull)) { btn.className = "selbtn on"; btn.textContent = "✓ Selected"; } else { btn.className = "selbtn"; btn.textContent = "+ Select"; } };
    btn.onclick = e => { e.stopPropagation(); if (inst) { P.set("model", pull); pollStatus(); paint(); return; } selected.has(pull) ? selected.delete(pull) : selected.set(pull, bytes); paint(); updateGo(); };
    paint();
    return h("div", { class: "vrow" }, h("div", { class: "grow" }, h("div", { style: "font-weight:600" }, label),
      h("div", { class: "muted" }, bytes ? gb(bytes) + "  " : "", f ? h("span", { class: "badge", style: `color:${f[1]}` }, f[0]) : null, note ? "  " + note : "")), btn);
  }
  function card(e) {
    const vbox = h("div", { class: "hidden" }); let loaded = false;
    const c = h("div", { class: "mcard" },
      h("div", { class: "row" }, h("div", { class: "t grow" }, e.title), newSet.has(e.source + ":" + e.id) ? h("span", { class: "badge", style: "color:var(--accent)" }, "NEW") : null, h("span", { class: "muted" }, e.source === "hf" ? "Hugging Face" : "Ollama")),
      e.desc ? h("div", { style: "font-size:13px;margin-top:3px" }, e.desc) : null,
      h("div", { class: "muted", style: "margin-top:4px" }, [ageOf(e.date), e.caps.join(" · "), e.stat].filter(Boolean).join("  ·  ")), vbox);
    c.onclick = async () => {
      vbox.classList.toggle("hidden"); if (loaded) return; loaded = true; vbox.innerHTML = ""; vbox.append(h("div", { class: "muted", style: "padding-top:8px" }, "Loading sizes…"));
      try { const vs = await Catalog.variants(e); vbox.innerHTML = ""; if (!vs.length) vbox.append(h("div", { class: "muted" }, "No downloadable builds (cloud-only or not GGUF).")); vs.forEach(v => vbox.append(vrow(v.pull, v.label, v.bytes, v.note))); }
      catch (err) { loaded = false; vbox.innerHTML = ""; vbox.append(h("div", { class: "muted" }, "Couldn't load. Click to retry.")); }
    };
    return c;
  }
  function renderPicks() {
    picks.innerHTML = ""; if (query) return;
    const mem = SYS ? SYS.memTotal : 8e9, list = SUGGEST.filter(s => s[3] <= mem / 1e9 + 0.5 && s[1] * 1.2 < mem * 0.85);
    picks.append(sec("Suggested for this PC"), h("div", { class: "mcard", style: "cursor:default" }, list.map(s => vrow(s[0], s[0], s[1], s[2]))));
  }
  function renderDl() {
    dlBox.innerHTML = ""; if (!DL.jobs.length) return;
    dlBox.append(h("div", { class: "row" }, h("div", { class: "sec grow" }, "Downloads · the PC stays awake until they finish"),
      DL.jobs.some(j => /done|failed|cancelled/.test(j.state)) ? h("button", { class: "chip", onclick: () => { DL.jobs = DL.jobs.filter(j => !/done|failed|cancelled/.test(j.state)); DL.notify(); } }, "Clear") : null));
    dlBox.append(h("div", { class: "mcard", style: "cursor:default" }, DL.jobs.map(j => h("div", { class: "vrow" },
      h("div", { class: "grow" }, h("div", { style: "font-weight:600" }, j.pull), h("div", { class: "muted" }, j.state === "running" && j.total ? `${gb(j.done)} of ${gb(j.total)} · ${j.status}` : j.status),
        j.state === "running" ? h("progress", { max: 100, value: j.total ? j.done * 100 / j.total : 0 }) : null),
      /queued|running/.test(j.state) ? h("button", { class: "selbtn", onclick: () => DL.cancel(j.pull) }, "✕") : null))));
    if (DL.jobs.some(j => j.state === "done")) refreshInstalled();
  }
  async function refreshInstalled() { try { installed = new Set((JSON.parse(await invoke("http_get", { url: S.host + "/api/tags" })).models || []).map(m => normName(m.name))); } catch {} }
  async function load(reset) {
    const g = ++gen; if (reset) { page = 0; results.innerHTML = ""; renderPicks(); } else page++;
    more.classList.add("hidden"); statusV.textContent = "Loading…"; statusV.classList.remove("hidden");
    const modes = [newest ? true : null, best ? false : null].filter(m => m !== null); if (!modes.length) modes.push(true);
    let hasMore = false, err = null; const seenIds = new Set(); const sections = [];
    for (const nw of modes) {
      let a = [], b = [];
      if (source !== "hf") try { const [l, m] = await Catalog.ollama(query, page, nw); a = l; hasMore ||= m; } catch (e) { err = String(e); }
      if (source !== "ollama") try { const [l, m] = await Catalog.hf(query, page, nw); b = l; hasMore ||= m; } catch (e) { err = String(e); }
      const merged = nw ? [...a, ...b].sort((x, y) => y.date - x.date) : Array.from({ length: Math.max(a.length, b.length) }, (_, i) => [a[i], b[i]]).flat().filter(Boolean);
      sections.push([nw, merged.filter(e => !seenIds.has(e.source + e.id) && seenIds.add(e.source + e.id))]);
    }
    if (g !== gen) return;
    sections.forEach(([nw, l]) => { if (!l.length) return; if (reset || modes.length > 1) results.append(sec((nw ? "Newest" : "Best") + (query ? ` · "${query}"` : " models"))); l.forEach(e => results.append(card(e))); });
    const empty = !results.children.length;
    statusV.classList.toggle("hidden", !empty); statusV.textContent = err ? "Couldn't reach the model catalogs: " + err : "Nothing found. Try another word.";
    more.classList.toggle("hidden", !hasMore);
  }
  more.onclick = () => load(false);
  function renderFilters() {
    filters.innerHTML = "";
    const box = (label, on, set, other) => { const cb = h("input", { type: "checkbox" }); cb.checked = on; cb.style.accentColor = "var(--accent)";
      cb.onchange = () => { if (!cb.checked && !other()) { cb.checked = true; return; } set(cb.checked); load(true); };
      filters.append(h("label", { class: "chip", style: "display:flex;gap:6px;align-items:center;cursor:pointer" }, cb, label)); };
    box("Newest at top", newest, v => { newest = v; P.set("modelNewest", v); }, () => best);
    box("Best models", best, v => { best = v; P.set("modelPopular", v); }, () => newest);
    [["all", "All sources"], ["ollama", "Ollama"], ["hf", "Hugging Face"]].forEach(([k, l]) => filters.append(h("button", { class: "chip" + (source === k ? " on" : ""), onclick: () => { source = k; P.set("modelSource", k); renderFilters(); load(true); } }, l)));
    const w = P.get("watchModels", true);
    filters.append(h("button", { class: "chip", onclick: () => { P.set("watchModels", !w); renderFilters(); } }, w ? "🔔 New-model alerts on" : "🔕 New-model alerts off"));
  }
  let t; search.addEventListener("input", () => { clearTimeout(t); t = setTimeout(() => { const q = search.value.trim(); if (q !== query) { query = q; load(true); } }, 400); });
  go.onclick = () => { DL.enqueue([...selected.keys()]); selected.clear(); updateGo(); root.querySelector(".mlist").scrollTop = 0; renderPicks(); };
  DL.listeners.add(renderDl); renderDl(); renderFilters();
  refreshInstalled().then(() => load(true));
  if (!P.get("seenModels", []).length) checkNewModels(true);
}

// ---------- boot ----------
let chatPane, projPane;
(async () => {
  try { PATHS = await invoke("paths"); PATHS.data = String(PATHS.data).replace(/\\/g, "/"); PATHS.workspace = String(PATHS.workspace).replace(/\\/g, "/"); } catch {}
  try { SYS = await invoke("sys_info"); } catch {}
  if (PATHS.resetLook) LOOK_KEYS.forEach(k => localStorage.removeItem("claww." + k));
  applyLook();
  $("#btnSettings").onclick = openSettings; $("#btnLook").onclick = openLook; $("#btnModels").onclick = openModels;
  chatPane = ChatPane($("#chat"), false);
  projPane = ChatPane($("#projChatPane"), true);
  const last = Store.convs.find(c => c.id === P.get("curChat", "") && !c.project);
  chatPane.open(last || Store.newConv(null));
  const cp = Store.project(P.get("curProject", null));
  if (cp) showProjectDetail(cp); else showProjectList();
  applyWide();
  go(tab, false);
  renderHome();
  if (PATHS.clawFound === false) chatPane.input.placeholder = "claw binary missing from this install";
  // Try to start Ollama once if it isn't running locally.
  try { await invoke("http_get", { url: S.host + "/api/version" }); } catch { if (/127\.0\.0\.1|localhost/.test(S.host)) invoke("start_ollama", { ctxLen: P.get("ctxLen", 0) }).catch(() => {}); }
  pollStatus(); setInterval(pollStatus, 4000);
  checkNewModels(false); setInterval(() => checkNewModels(false), 30 * 60000);
  addEventListener("keydown", e => { if (e.key === "Escape") closeModal(); });
  // Ctrl+Shift+R: reset wallpaper and glass look (keeps chats and projects).
  addEventListener("keydown", e => { if (e.ctrlKey && e.shiftKey && e.key.toLowerCase() === "r") { e.preventDefault(); resetLook(); modal("Look reset", h("div", {}, "Wallpaper and glass are back to defaults. Your chats and projects are untouched.")); } });
})();

const PAGE = 60;
const NEW = "@new";
const state = { all: [], apps: [], room: "", q: "", shown: PAGE, fresh: new Set() };
const $ = (id) => document.getElementById(id);
const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]);
const num = (n) => n >= 1e6 ? (n / 1e6).toFixed(1).replace(/\.0$/, "") + "M" : n >= 1000 ? (n / 1000).toFixed(1).replace(/\.0$/, "") + "k" : String(n);
const date = (d) => new Date(d + "T00:00:00Z").toLocaleDateString(undefined, { day: "numeric", month: "short", year: "numeric", timeZone: "UTC" });

function art(a) {
  const tile = a.art?.banner || a.art?.feature || a.art?.shots?.[0];
  if (tile) return `<img src="${esc(tile)}" alt="" loading="lazy">`;
  if (a.art?.icon) return `<img class="icon" src="${esc(a.art.icon)}" alt="" loading="lazy">`;
  return `<b>${esc(a.name.slice(0, 1))}</b>`;
}

function matches(a, words) {
  const hay = `${a.name} ${a.note} ${a.desc} ${a.room} ${a.repo}`.toLowerCase();
  return words.every((w) => hay.includes(w));
}

function list() {
  const q = state.q.trim().toLowerCase();
  if (/^\d{5}$/.test(q)) return state.all.filter((a) => String(a.code) === q);
  const words = q.split(/\s+/).filter(Boolean);
  if (state.room === NEW) return state.apps.filter((a) => state.fresh.has(a.repo) && (!words.length || matches(a, words)));
  return state.apps.filter((a) => (!state.room || a.room === state.room) && (words.length ? matches(a, words) : a.stars >= 10));
}

function render() {
  const all = list();
  $("count").textContent = `${all.length.toLocaleString()} ${all.length === 1 ? "app" : "apps"}`;
  $("grid").innerHTML = all.slice(0, state.shown).map((a) => `
    <button class="card" data-code="${a.code}">
      <div class="art">${art(a)}</div>
      <h3>${esc(a.name)}</h3>
      <p>${esc(a.note)}</p>
    </button>`).join("");
  $("more").hidden = all.length <= state.shown;
}

function rooms() {
  const counts = {};
  state.apps.forEach((a) => { if (a.stars >= 10) counts[a.room] = (counts[a.room] || 0) + 1; });
  const names = Object.keys(counts).sort((x, y) => (x === "Other") - (y === "Other") || counts[y] - counts[x]);
  const fresh = state.fresh.size ? [[NEW, `New this week (${state.fresh.size})`]] : [];
  $("rooms").innerHTML = [["", "All"], ...fresh, ...names.map((n) => [n, n])]
    .map(([v, t]) => `<button class="chip" data-room="${esc(v)}" aria-pressed="${v === state.room}">${esc(t)}</button>`).join("");
}

function restore(codes) {
  const apps = codes.map((c) => state.all.find((x) => String(x.code) === c)).filter(Boolean);
  $("sheet").innerHTML = `<div class="sheet"><div class="body">
    <h2>Your Open Shelf apps</h2>
    <div class="by">${apps.length} ${apps.length === 1 ? "app" : "apps"}. On your new TV, open Settings, then Restore apps, and type these codes.</div>
    <div class="big"><b style="font-size:22px;letter-spacing:0.04em">${codes.join(" ")}</b></div>
    <dl class="facts">${apps.map((a) => `<div><dt>${a.code}</dt><dd>${esc(a.name)}</dd></div>`).join("")}</dl>
    <div class="row"><button class="btn" id="close">Close</button></div>
  </div></div>`;
  $("sheet").showModal();
  $("close").onclick = () => $("sheet").close();
}

function route() {
  const h = location.hash.slice(1);
  if (h.startsWith("restore=")) restore(h.slice(8).split(".").filter((c) => /^\d{5}$/.test(c)));
  else if (h) open(h);
}

function open(code) {
  const a = state.all.find((x) => String(x.code) === String(code));
  if (!a) return;
  const facts = [
    ["Latest release", `${esc(a.version)}, ${date(a.date)}`],
    ["Stars", a.stars.toLocaleString()],
    ["Licence", esc(a.licence || "See repository")],
    ["Downloads", a.downloads ? num(a.downloads) : "Not counted"],
  ];
  $("sheet").innerHTML = `<div class="sheet">
    <div class="art">${art(a)}</div>
    <div class="body">
      <h2>${esc(a.name)}</h2>
      <div class="by">by ${esc(a.maker)}${a.archived ? " · archived" : ""}</div>
      <div>${esc(a.desc || a.note)}</div>
      <div class="big"><b>${a.code}</b><span>Type this code into Open Shelf’s search on your TV</span></div>
      <dl class="facts">${facts.map(([k, v]) => `<div><dt>${k}</dt><dd>${v}</dd></div>`).join("")}</dl>
      <div class="row">
        <a class="btn solid" href="https://github.com/${esc(a.repo)}">Open on GitHub</a>
        <button class="btn" id="close">Close</button>
      </div>
    </div></div>`;
  $("sheet").showModal();
  $("close").onclick = () => $("sheet").close();
  if (location.hash !== "#" + a.code) history.replaceState(null, "", "#" + a.code);
}

$("sheet").addEventListener("close", () => history.replaceState(null, "", location.pathname));
$("sheet").addEventListener("click", (e) => { if (e.target === $("sheet")) $("sheet").close(); });
$("grid").addEventListener("click", (e) => { const c = e.target.closest(".card"); if (c) open(c.dataset.code); });
$("rooms").addEventListener("click", (e) => {
  const c = e.target.closest(".chip"); if (!c) return;
  state.room = c.dataset.room; state.shown = PAGE; rooms(); render();
});
$("q").addEventListener("input", (e) => { state.q = e.target.value; state.shown = PAGE; render(); });
$("more").addEventListener("click", () => { state.shown += PAGE; render(); });
window.addEventListener("hashchange", route);

const weeks = fetch("new.json").then((r) => r.json()).catch(() => ({}));

fetch("catalog.json").then((r) => r.json()).then(async (c) => {
  const w = (await weeks).weeks?.[0];
  if (w?.date === c.generated) state.fresh = new Set(w.repos);
  state.all = c.apps.filter((a) => a.apks?.length);
  state.apps = state.all.filter((a) => !a.forkOf);
  $("foot").textContent = `${c.apps.length.toLocaleString()} apps, checked ${date(c.generated)}. App names, art and releases come from each project’s GitHub repository. Open Shelf is open source under GPL-3.0.`;
  rooms(); render();
  route();
}).catch(() => { $("count").textContent = "Couldn’t load the catalog. Try again in a minute."; });

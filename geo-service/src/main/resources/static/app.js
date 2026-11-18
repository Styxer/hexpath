// Demo page for the hexpath API. Plain ES module, no build step.
import { escapeHtml, label, placePopup } from "./html.js";

const L = window.L;
const center = [32.0779, 34.7740];
const map = L.map("map", { doubleClickZoom: false }).setView(center, 15);
L.tileLayer("https://tile.openstreetmap.org/{z}/{x}/{y}.png", {
  maxZoom: 19,
  attribution: '© <a href="https://www.openstreetmap.org/copyright">OpenStreetMap contributors</a>',
}).addTo(map);

const $ = (id) => document.getElementById(id);
const searchLayer = L.layerGroup().addTo(map);
const routeLayer = L.layerGroup().addTo(map);
const alongLayer = L.layerGroup().addTo(map);
let routePoints = [];
let drawing = false;
let bufferBand = null;

function toast(text) {
  const t = $("toast");
  t.textContent = text;
  t.style.display = "block";
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => (t.style.display = "none"), 6000);
}

function showRelease(res) {
  const id = res.headers.get("X-Dataset-Release");
  if (id) $("release").dataset.release = id;
}

async function api(path, options = {}) {
  const res = await fetch(path, options);
  showRelease(res);
  const body = await res.json();
  if (!res.ok) {
    toast(`${body.title}: ${body.detail}`);   // RFC 9457 problem
    throw new Error(body.detail);
  }
  return body;
}

async function refreshDataset() {
  const d = await api("/v1/dataset");
  const days = d.data_age_seconds == null ? "?" : Math.floor(d.data_age_seconds / 86400);
  $("release").textContent = `release ${d.release} · source data ${days} days old · ${d.counts.place} places`;
}

function numberIcon(n) {
  return L.divIcon({ className: "", html: `<span class="num">${n}</span>`, iconSize: [20, 20], iconAnchor: [10, 10] });
}

// ---- place search -------------------------------------------------------------------------
let searchTimer;
$("q").addEventListener("input", () => {
  clearTimeout(searchTimer);
  searchTimer = setTimeout(search, 300);
});

async function search() {
  const q = $("q").value.trim();
  searchLayer.clearLayers();
  $("search-results").innerHTML = "";
  if (!q) return;
  const c = map.getCenter();
  const params = new URLSearchParams({ q, lat: c.lat.toFixed(6), lon: c.lng.toFixed(6), radius_m: 3000, limit: 15 });
  const body = await api(`/v1/places/search?${params}`);
  for (const p of body.results) {
    const [lon, lat] = p.location.coordinates;
    L.marker([lat, lon]).bindPopup(placePopup(p)).addTo(searchLayer);
    const li = document.createElement("li");
    li.textContent = `${label(p)} · ${p.category} · ${Math.round(p.distance_m)} m`;
    li.onclick = () => map.setView([lat, lon], 17);
    $("search-results").append(li);
  }
  if (!body.results.length) $("search-results").innerHTML = "<li class='muted'>No matches</li>";
}

// ---- route drawing and along-route search ---------------------------------------------------
function metresPerPixel() {
  return (156543.03392 * Math.cos((map.getCenter().lat * Math.PI) / 180)) / 2 ** map.getZoom();
}

function drawRoute() {
  routeLayer.clearLayers();
  if (!routePoints.length) return;
  // A thick translucent line with round caps is the buffer's shape, drawn at the current zoom.
  bufferBand = L.polyline(routePoints, { color: "#0b6e4f", opacity: 0.15, lineCap: "round", lineJoin: "round" });
  bufferBand.addTo(routeLayer);
  updateBand();
  L.polyline(routePoints, { color: "#0b6e4f", weight: 3 }).addTo(routeLayer);
  $("along").disabled = routePoints.length < 2 || drawing;
}

function updateBand() {
  if (bufferBand) bufferBand.setStyle({ weight: (2 * Number($("buffer").value)) / metresPerPixel() });
}

map.on("zoomend", updateBand);
$("buffer").addEventListener("input", () => {
  $("buffer-label").textContent = `${$("buffer").value} m`;
  updateBand();
});

$("draw").onclick = () => {
  drawing = !drawing;
  $("draw").setAttribute("aria-pressed", String(drawing));
  if (drawing) {
    routePoints = [];
    alongLayer.clearLayers();
    $("along-results").innerHTML = "";
  }
  drawRoute();
};

$("clear").onclick = () => {
  drawing = false;
  $("draw").setAttribute("aria-pressed", "false");
  routePoints = [];
  routeLayer.clearLayers();
  alongLayer.clearLayers();
  $("along-results").innerHTML = "";
  $("along").disabled = true;
};

map.on("click", (e) => {
  if (!drawing) return;
  routePoints.push([e.latlng.lat, e.latlng.lng]);
  drawRoute();
});

map.on("dblclick", () => {
  if (!drawing) return;
  drawing = false;
  $("draw").setAttribute("aria-pressed", "false");
  drawRoute();
});

$("along").onclick = async () => {
  alongLayer.clearLayers();
  $("along-results").innerHTML = "";
  const body = await api("/v1/search/along-route", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      route: { type: "LineString", coordinates: routePoints.map(([lat, lon]) => [lon, lat]) },
      buffer_m: Number($("buffer").value),
      limit: 50,
    }),
  });
  const ordered = [...body.results].sort((a, b) => a.along_m - b.along_m);   // in driving order
  ordered.forEach((p, i) => {
    const [lon, lat] = p.location.coordinates;
    L.marker([lat, lon], { icon: numberIcon(i + 1) })
      .bindPopup(placePopup(p, `${p.category} · ${Math.round(p.distance_m)} m off the route`))
      .addTo(alongLayer);
    const li = document.createElement("li");
    li.textContent = `${label(p)} · ${(p.along_m / 1000).toFixed(2)} km along, ${Math.round(p.distance_m)} m off`;
    $("along-results").append(li);
  });
  if (!ordered.length) $("along-results").innerHTML = "<li class='muted'>Nothing within the buffer</li>";
};

// ---- boundaries at a point -----------------------------------------------------------------
map.on("contextmenu", async (e) => {
  const params = new URLSearchParams({ lat: e.latlng.lat.toFixed(6), lon: e.latlng.lng.toFixed(6) });
  const body = await api(`/v1/boundaries/containing?${params}`);
  $("boundaries").innerHTML = "";
  for (const b of body.results) {
    const li = document.createElement("li");
    li.textContent = `${b.name} · ${b.type.toLowerCase()}${b.origin === "synthetic" ? " · synthetic, fictional" : ""}`;
    $("boundaries").append(li);
  }
  if (!body.results.length) $("boundaries").innerHTML = "<li class='muted'>None</li>";
  L.popup().setLatLng(e.latlng)
    .setContent(body.results.map((b) => escapeHtml(b.name)).join("<br>") || "No boundary").openOn(map);
});

refreshDataset().catch(() => {});
setInterval(() => refreshDataset().catch(() => {}), 10000);   // shows a release swap or rollback live

// OpenStreetMap text is user-generated: anything that goes into HTML is escaped first.
const ENTITIES = { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" };

export function escapeHtml(value) {
  return String(value ?? "").replace(/[&<>"']/g, (c) => ENTITIES[c]);
}

export function label(p) {
  return p.name_en && p.name_en !== p.name ? `${p.name} (${p.name_en})` : p.name;
}

/** Popup HTML for a place; every data value is escaped. */
export function placePopup(p, detail = p.category) {
  return `${escapeHtml(label(p))}<br><span class="muted">${escapeHtml(detail)}</span>`;
}

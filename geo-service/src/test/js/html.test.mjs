// Run with: node --test geo-service/src/test/js
import assert from "node:assert/strict";
import test from "node:test";
import { escapeHtml, placePopup } from "../../main/resources/static/html.js";

test("escapes markup in OpenStreetMap text", () => {
  assert.equal(escapeHtml(`<img src=x onerror="alert(1)">&'`), "&lt;img src=x onerror=&quot;alert(1)&quot;&gt;&amp;&#39;");
});

test("place popups never contain raw markup from data", () => {
  const html = placePopup({ name: "<script>x()</script>", name_en: null, category: "<b>cafe</b>" }, "detail <i>");
  assert.ok(!html.includes("<script>") && !html.includes("<b>") && !html.includes("<i>"));
  assert.ok(html.includes("&lt;script&gt;x()&lt;/script&gt;"));
});

test("keeps Hebrew and English names readable", () => {
  assert.equal(placePopup({ name: "קפה מסריק", name_en: "Cafe Masaryk", category: "cafe" }),
    'קפה מסריק (Cafe Masaryk)<br><span class="muted">cafe</span>');
});

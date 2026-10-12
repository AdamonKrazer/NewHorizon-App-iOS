(() => {
  if (window.__nhPadPopupCapture) return;
  window.__nhPadPopupCapture = true;
  const originalOpen = window.open;
  function destination(value) {
    try { return new URL(String(value || ""), location.href).href; } catch (_) { return ""; }
  }
  window.open = function(url, target, features) {
    // Named current-frame navigation keeps its ordinary browser behavior.
    if (["_self", "_parent", "_top"].includes(String(target || "").toLowerCase()))
      return originalOpen.call(this, url, target, features);
    let href = "about:blank", closed = false;
    const emit = value => {
      if (closed || value == null || value === "" || String(value) === "about:blank") return;
      const next = destination(value);
      if (!/^https?:/i.test(next) || next === href) return;
      href = next;
      window.dispatchEvent(new CustomEvent("__nh_pad_popup", {detail: {url: next}}));
    };
    const loc = {assign: emit, replace: emit, toString: () => href};
    Object.defineProperty(loc, "href", {get: () => href, set: emit});
    const popup = {focus() {}, blur() {}, close() {closed = true;}};
    Object.defineProperty(popup, "closed", {get: () => closed});
    Object.defineProperty(popup, "location", {get: () => loc, set: emit});
    emit(url);
    return popup;
  };
})();

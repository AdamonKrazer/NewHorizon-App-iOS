(function() {
  var host = "";
  try { host = (location && location.hostname) ? String(location.hostname).toLowerCase() : ""; } catch(e) {}
  if (host !== "playnewhorizon.com" && !host.endsWith(".playnewhorizon.com")) return;

  try { window.__wd_only_iframe_mode__ = true; } catch(e) {}

  // 1x por pÃ¡gina (pathname)
  try {
    var key = String(location.pathname || "");
    if (window.__wd_gptfs_installed__ && window.__wd_gptfs_key__ === key) return;
    window.__wd_gptfs_installed__ = true;
    window.__wd_gptfs_key__ = key;
  } catch(e) {}

  function imp(el, prop, val) { try {
    if(el.style.getPropertyValue(prop)!==val || el.style.getPropertyPriority(prop)!=="important") el.style.setProperty(prop, val, "important");
  } catch(e) {} }

  function ensureStyle() {
    try {
      if (document.getElementById("__wd_gptfs_style__")) return;
      var s = document.createElement("style");
      s.id = "__wd_gptfs_style__";
      s.type = "text/css";
      s.textContent =
        "html,body{margin:0!important;padding:0!important;background:#fff!important;}" +
        "[data-wd-gptfs='1']{" +
          "position:fixed!important;inset:0!important;width:100vw!important;height:100vh!important;" +
          "z-index:2147483647!important;background:#fff!important;" +
          "display:flex!important;align-items:center!important;justify-content:center!important;" +
          "overflow:hidden!important;" +
        "}" +
        "[data-wd-gptfs='1'] iframe{" +
          "display:block!important;border:0!important;margin:0!important;padding:0!important;" +
          "max-width:100vw!important;max-height:100vh!important;" +
        "}";
      (document.head || document.documentElement).appendChild(s);
    } catch(e) {}
  }

  function apply() {
    try {
      if (!document || !document.body) return false;

      ensureStyle();

      // âœ… SOMENTE o slot GPT (vocÃª disse que sÃ³ existe 1 no meio)
      var slot = document.querySelector("div[id^='div-gpt-ad']");
      if (!slot) return false;

      try {
        var old = document.querySelector("[data-wd-gptfs='1']");
        if (old && old !== slot) old.removeAttribute("data-wd-gptfs");
      } catch(e) {}

      try { if(slot.getAttribute("data-wd-gptfs")!=="1") slot.setAttribute("data-wd-gptfs", "1"); } catch(e) {}

      try {
        imp(document.documentElement, "overflow", "hidden");
        imp(document.body, "overflow", "hidden");
      } catch(e) {}

      return true;
    } catch(e) { return false; }
  }

  var pend = false;
  function schedule() {
    if (pend) return;
    pend = true;
    setTimeout(function(){ pend = false; apply(); }, 120);
  }

  // tenta atÃ© o GPT criar o slot/iframe (refresh tambÃ©m recria)
  (function tick(n) {
    if (apply()) return;
    if (n <= 0) return;
    setTimeout(function(){ tick(n - 1); }, 250);
  })(120);

  try {
    var mo = new MutationObserver(function(){ schedule(); });
    mo.observe(document.documentElement, { childList:true, subtree:true, attributes:true });
  } catch(e) {}

  try { window.addEventListener("resize", schedule, true); } catch(e) {}
})();

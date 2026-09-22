(function() {
  try {
    var host = (location && location.hostname) ? String(location.hostname).toLowerCase() : "";
    if (host !== "playnewhorizon.com" && !host.endsWith(".playnewhorizon.com")) return;
  } catch(e) { return; }

  // nÃ£o conflitar com modo fullscreen do ad (display)
  try { if (window.__wd_only_iframe_mode__) return; } catch(e) {}

  try {
    var key = String(location.pathname || "");
    if (window.__wd_autoscroll_key__ === key) return;
    window.__wd_autoscroll_key__ = key;
  } catch(e) {}

  function isVisible(el) {
    try {
      if (!el) return false;
      var cs = getComputedStyle(el);
      if (!cs) return false;
      if (cs.display === "none" || cs.visibility === "hidden") return false;
      var r = el.getBoundingClientRect();
      return r && r.width > 2 && r.height > 2;
    } catch(e) { return false; }
  }

  function scoreIframe(f) {
    var s = 0;
    try {
      var key = ((f.id||"") + " " + (f.className||"") + " " + (f.name||"") + " " + (f.src||"")).toLowerCase();
      if (key.indexOf("nh_ad_iframe") !== -1) s += 5000;
      if (key.indexOf("google_ads_iframe") !== -1) s += 2500;
      if (key.indexOf("googlesyndication") !== -1) s += 1800;
      if (key.indexOf("doubleclick") !== -1) s += 1800;
      if (key.indexOf("gpt") !== -1) s += 800;
      if (key.indexOf("ad") !== -1) s += 150;

      var r = f.getBoundingClientRect();
      s += (r.width * r.height) / 50;
      var mid = Math.abs((r.top + r.height/2) - (window.innerHeight/2));
      s += Math.max(0, 800 - mid);
    } catch(e) {}
    return s;
  }

  function pickIframe() {
    try {
      var list = Array.prototype.slice.call(document.querySelectorAll("iframe"));
      if (!list.length) return null;

      var vis = list.filter(isVisible);
      if (!vis.length) vis = list;

      var best = null, bestS = -1;
      for (var i = 0; i < vis.length; i++) {
        var f = vis[i];
        var sc = scoreIframe(f);
        if (sc > bestS) { bestS = sc; best = f; }
      }
      return best;
    } catch(e) { return null; }
  }

  function doScrollTo(f) {
    try {
      var r = f.getBoundingClientRect();
      var y = (window.scrollY || window.pageYOffset || 0) + r.top - 140;
      if (y < 0) y = 0;
      window.scrollTo(0, y);

      setTimeout(function() {
        try {
          var r2 = f.getBoundingClientRect();
          var y2 = (window.scrollY || window.pageYOffset || 0) + r2.top - 140;
          if (y2 < 0) y2 = 0;
          window.scrollTo(0, y2);
        } catch(e) {}
      }, 350);
      return true;
    } catch(e) { return false; }
  }

  var tries = 0;
  var iv = setInterval(function() {
    tries++;

    var f = pickIframe();
    if (f) {
      if (doScrollTo(f)) clearInterval(iv);
      return;
    }

    if (tries > 60) {
      try {
        var h = Math.max(document.body.scrollHeight, document.documentElement.scrollHeight);
        var y = Math.floor(h * 0.35);
        window.scrollTo(0, y);
      } catch(e) {}
      clearInterval(iv);
    }
  }, 250);
})();

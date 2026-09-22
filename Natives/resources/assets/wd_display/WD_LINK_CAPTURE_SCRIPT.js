(function() {
  if (window.__wd_link_capture_installed__) return;
  window.__wd_link_capture_installed__ = true;

  function abs(url) {
    try { return new URL(String(url), location.href).href; } catch(e) { return String(url || ""); }
  }

  function log(kind, url) {
    try {
      if (!url) return;
      url = abs(url);
      if (!url || url === "about:blank") return;
      if (kind === "popup") console.log("[WD] Link popup: " + url);
      else if (kind === "nav") console.log("[WD] Link nav: " + url);
      else console.log("[WD] Link clicado: " + url);
    } catch(e) {}
  }

  // window.open (captura e BLOQUEIA popup nativo; suporta blank -> location=...)
  try {
    var _open = window.open;

    function mkProxy() {
      var hrefV = "";
      var loc = {};
      try {
        Object.defineProperty(loc, "href", {
          get: function() { return hrefV; },
          set: function(v) {
            try {
              var s = (v && v.href) ? v.href : String(v || "");
              if (s) { hrefV = s; log("popup", s); }
            } catch(e) {}
          }
        });
      } catch(e) {
        try { loc.href = hrefV; } catch(ex) {}
      }

      try {
        loc.assign = function(u) { try { loc.href = u; } catch(e) {} };
        loc.replace = function(u) { try { loc.href = u; } catch(e) {} };
      } catch(e) {}

      var w = {
        focus: function(){},
        close: function(){},
        blur: function(){},
        location: loc
      };
      // Preserve the original blank-popup contract for both location= and location.href=.
      try { Object.defineProperty(w, "location", {get:function(){return loc;},set:function(v){loc.href=v;}}); } catch(e) {}
      return w;
    }

    window.open = function(url) {
      try {
        var u = (url == null) ? "" : String(url);
        // Mesmo blank/about:blank: devolve proxy e captura quando atribuir location
        if (u && u !== "about:blank") log("popup", u);
        return mkProxy();
      } catch(e) {
        try { return _open ? _open.apply(this, arguments) : null; } catch(ex) { return null; }
      }
    };
  } catch(e) {}

  // location.assign / location.replace
  try {
    var _assign = location.assign.bind(location);
    location.assign = function(url) { try { log("nav", url); } catch(e) {} return _assign(url); };
  } catch(e) {}

  try {
    var _replace = location.replace.bind(location);
    location.replace = function(url) { try { log("nav", url); } catch(e) {} return _replace(url); };
  } catch(e) {}

  // clicks em <a>
  try {
    document.addEventListener("click", function(e) {
      try {
        var el = e.target;
        for (var i=0; i<6 && el; i++, el = el.parentElement) {
          if (el.tagName === "A") {
            var href = el.getAttribute("href");
            if (href) log("click", href);
            break;
          }
        }
      } catch(ex) {}
    }, true);
  } catch(e) {}
})();

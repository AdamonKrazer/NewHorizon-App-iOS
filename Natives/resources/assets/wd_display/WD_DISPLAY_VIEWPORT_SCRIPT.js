(function() {
  if (window.__nhDisplayViewport) return;
  var width = 0, height = 0, pending = false;
  function apply() {
    pending = false;
    if (!document.documentElement || !width || !height) return;
    var head = document.head;
    if (!head) return;
    var metas = document.querySelectorAll('meta[name="viewport"]');
    var meta = metas[0];
    if (!meta) {
      meta = document.createElement('meta');
      meta.name = 'viewport';
      head.appendChild(meta);
    }
    // The render target is in physical pixels, whereas Android's default
    // viewport is in density-independent CSS pixels. Scope this to displays.
    var scale = 1 / (window.devicePixelRatio || 1);
    var content = 'width=' + width + ',height=' + height + ',initial-scale=' + scale
      + ',minimum-scale=' + scale + ',maximum-scale=' + scale + ',user-scalable=no';
    for (var i = 0; i < metas.length; i++) {
      if (metas[i].getAttribute('content') !== content) metas[i].setAttribute('content', content);
    }
    if (meta.getAttribute('content') !== content) meta.setAttribute('content', content);
  }
  function schedule() {
    if (pending) return;
    pending = true;
    setTimeout(apply, 0);
  }
  window.__nhDisplayViewport = function(w, h) {
    width = w; height = h; apply();
  };
  new MutationObserver(function(records) {
    for (var i = 0; i < records.length; i++) {
      var r = records[i];
      if (r.type === 'childList' || (r.target.tagName === 'META' && r.target.name === 'viewport')) {
        schedule(); return;
      }
    }
  }).observe(document, {subtree:true, childList:true, attributes:true, attributeFilter:['content', 'name']});
  document.addEventListener('DOMContentLoaded', apply, {once:true});
})();
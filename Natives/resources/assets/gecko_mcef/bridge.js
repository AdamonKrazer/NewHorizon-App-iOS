(() => {
  const nativeApp = "new_horizon_mcef";
  const mainFrame = window === window.top;
  if (!mainFrame) return;
  const port = browser.runtime.connectNative(nativeApp);
  const pending = new Map();
  let queryId = 1;
  const diagnosticsEnabled = false;
  const compositorKeepAliveEnabled = true;
  let diagnosticsSequence = 0;
  let rafFrames = 0;
  let rafLast = 0;
  let rafWindowStarted = 0;
  let rafMaxGap = 0;
  let longTaskCount = 0;
  let longTaskDuration = 0;
  let longTaskMax = 0;

  function diagnostic(stage, detail) {
    if (!diagnosticsEnabled) return;
    try {
      port.postMessage({
        type: "diagnostic",
        stage,
        detail: String(detail || ""),
        url: location.href
      });
    } catch (_) {
    }
  }

  function countSelector(selector) {
    try {
      return document.querySelectorAll(selector).length;
    } catch (_) {
      return -1;
    }
  }

  function activeElementSummary() {
    try {
      const active = document.activeElement;
      if (!active) return "";
      return `${active.tagName || ""}#${active.id || ""}.${active.className || ""}`.slice(0, 120);
    } catch (_) {
      return "";
    }
  }

  function emitPagePerf(reason) {
    if (!diagnosticsEnabled) return;
    const now = performance.now();
    const elapsed = Math.max(1, now - (rafWindowStarted || now));
    const rafFps = rafFrames * 1000 / elapsed;
    const body = document.body;
    const doc = document.documentElement;
    diagnostic("page-perf", JSON.stringify({
      seq: ++diagnosticsSequence,
      reason,
      now: Math.round(now),
      url: location.href,
      title: document.title || "",
      readyState: document.readyState,
      visibility: document.visibilityState,
      hidden: document.hidden,
      rafFps: Number(rafFps.toFixed(1)),
      rafFrames,
      rafMaxGapMs: Math.round(rafMaxGap),
      longTasks: longTaskCount,
      longTaskMs: Math.round(longTaskDuration),
      longTaskMaxMs: Math.round(longTaskMax),
      nodes: countSelector("*"),
      scripts: countSelector("script"),
      iframes: countSelector("iframe"),
      videos: countSelector("video"),
      images: countSelector("img"),
      active: activeElementSummary(),
      scrollX: Math.round(window.scrollX || 0),
      scrollY: Math.round(window.scrollY || 0),
      scrollHeight: doc ? doc.scrollHeight : 0,
      viewport: `${window.innerWidth}x${window.innerHeight}`,
      bodyClass: body ? String(body.className || "").slice(0, 160) : "",
      bodyText: body ? String(body.innerText || "").length : 0
    }));
    rafFrames = 0;
    rafWindowStarted = now;
    rafMaxGap = 0;
    longTaskCount = 0;
    longTaskDuration = 0;
    longTaskMax = 0;
  }

  function trackRaf(now) {
    if (!rafWindowStarted) rafWindowStarted = now;
    if (rafLast) rafMaxGap = Math.max(rafMaxGap, now - rafLast);
    rafLast = now;
    rafFrames++;
    requestAnimationFrame(trackRaf);
  }

  if (diagnosticsEnabled || compositorKeepAliveEnabled) {
    try {
      requestAnimationFrame(trackRaf);
    } catch (error) {
      diagnostic("raf-start-error", error && error.stack ? error.stack : error);
    }
  }

  if (diagnosticsEnabled) {
    try {
      if (typeof PerformanceObserver === "function") {
        const observer = new PerformanceObserver(list => {
          for (const entry of list.getEntries()) {
            longTaskCount++;
            longTaskDuration += entry.duration || 0;
            longTaskMax = Math.max(longTaskMax, entry.duration || 0);
          }
        });
        observer.observe({ entryTypes: ["longtask"] });
        diagnostic("longtask-observer", "installed");
      } else {
        diagnostic("longtask-observer", "unavailable");
      }
    } catch (error) {
      diagnostic("longtask-observer-error", error && error.stack ? error.stack : error);
    }
  }

  function injectPageBridge() {
    const source = `(() => {
      const pending = new Map();
      let nextId = 1;
      window.cefQuery = window.cefQuery || function(request) {
        const id = nextId++;
        pending.set(id, request);
        window.dispatchEvent(new CustomEvent("__nh_mcef_query", {
          detail: {
            id,
            request: String(request.request || ""),
            persistent: Boolean(request.persistent)
          }
        }));
        return id;
      };
      window.addEventListener("__nh_mcef_query_result", event => {
        const detail = event.detail || {};
        const request = pending.get(detail.id);
        if (!request) return;
        if (!detail.persistent) pending.delete(detail.id);
        if (detail.success) {
          if (typeof request.onSuccess === "function") request.onSuccess(detail.response || "");
        } else if (typeof request.onFailure === "function") {
          request.onFailure(detail.errorCode || -1, detail.response || "");
        }
      });

      const stringify = value => {
        try {
          if (typeof value === "string") return value;
          return JSON.stringify(value);
        } catch (_) {
          return String(value);
        }
      };
      for (const level of ["log", "info", "warn", "error", "debug"]) {
        const original = console[level];
        console[level] = function(...args) {
          window.dispatchEvent(new CustomEvent("__nh_mcef_console", {
            detail: { level, message: args.map(stringify).join(" ") }
          }));
          return original.apply(this, args);
        };
      }

      const originalOpen = window.open;
      window.open = function(url, target, features) {
        window.dispatchEvent(new CustomEvent("__nh_mcef_popup", {
          detail: {
            url: url == null || String(url) === "" ? "about:blank" : String(url),
            targetFrameName: target == null ? "" : String(target)
          }
        }));
        return originalOpen.call(this, url, target, features);
      };
    })();`;
    executeInPage(source, "page-bridge");
  }

  function executeInPage(code, label) {
    try {
      if (window.wrappedJSObject && typeof window.wrappedJSObject.eval === "function") {
        window.wrappedJSObject.eval.call(window.wrappedJSObject, String(code || ""));
        diagnostic(`${label}-executed`, "wrappedJSObject");
        return true;
      }
    } catch (error) {
      diagnostic(`${label}-wrapped-error`, error && error.stack ? error.stack : error);
    }

    try {
      const script = document.createElement("script");
      script.textContent = String(code || "");
      (document.documentElement || document).appendChild(script);
      script.remove();
      diagnostic(`${label}-executed`, "script-element");
      return true;
    } catch (error) {
      diagnostic(`${label}-script-error`, error && error.stack ? error.stack : error);
      return false;
    }
  }

  diagnostic("content-script-start", `readyState=${document.readyState}`);
  injectPageBridge();
  port.postMessage({
    type: "frame-ready",
    url: location.href,
    mainFrame
  });

  window.addEventListener("__nh_mcef_query", event => {
    const detail = event.detail || {};
    pending.set(detail.id, detail);
    diagnostic("query-forward", `id=${detail.id} persistent=${Boolean(detail.persistent)}`);
    port.postMessage({
      type: "query",
      id: detail.id,
      request: detail.request || "",
      persistent: Boolean(detail.persistent),
      url: location.href
    });
  });

  window.addEventListener("__nh_mcef_console", event => {
    const detail = event.detail || {};
    port.postMessage({
      type: "console",
      level: detail.level || "log",
      message: detail.message || "",
      source: location.href,
      line: 0,
      url: location.href
    });
  });

  window.addEventListener("__nh_mcef_popup", event => {
    const detail = event.detail || {};
    port.postMessage({
      type: "popup",
      url: detail.url || "about:blank",
      targetFrameName: detail.targetFrameName || "",
      userGesture: true,
      source: location.href
    });
  });

  function elementAt(x, y) {
    try {
      return document.elementFromPoint(Number(x) || 0, Number(y) || 0)
        || document.activeElement || document.documentElement;
    } catch (_) {
      return document.activeElement || document.documentElement;
    }
  }

  function dispatchInput(message) {
    const kind = String(message.kind || "");
    const x = Number(message.x) || 0;
    const y = Number(message.y) || 0;
    const modifiers = {
      altKey: Boolean(message.altKey),
      ctrlKey: Boolean(message.ctrlKey),
      metaKey: Boolean(message.metaKey),
      shiftKey: Boolean(message.shiftKey)
    };

    if (kind === "mouse") {
      const javaType = Number(message.eventType) || 503;
      const eventName = javaType === 501 ? "mousedown"
        : javaType === 502 ? "mouseup"
        : javaType === 504 ? "mouseenter"
        : javaType === 505 ? "mouseleave"
        : javaType === 506 ? "mousemove" : "mousemove";
      const button = Math.max(0, (Number(message.button) || 1) - 1);
      const target = elementAt(x, y);
      target.dispatchEvent(new MouseEvent(eventName, {
        bubbles: true,
        cancelable: true,
        view: window,
        clientX: x,
        clientY: y,
        screenX: x,
        screenY: y,
        button,
        buttons: Number(message.buttons) || (eventName === "mousedown" ? (1 << button) : 0),
        detail: Number(message.clickCount) || 1,
        ...modifiers
      }));
      if (eventName === "mousedown" && typeof target.focus === "function") target.focus();
      if (eventName === "mouseup" && button === 0
          && typeof target.click === "function") {
        target.click();
      }
      return;
    }

    if (kind === "wheel") {
      elementAt(x, y).dispatchEvent(new WheelEvent("wheel", {
        bubbles: true,
        cancelable: true,
        view: window,
        clientX: x,
        clientY: y,
        deltaY: Number(message.deltaY) || 0,
        deltaMode: WheelEvent.DOM_DELTA_LINE,
        ...modifiers
      }));
      window.scrollBy({ top: Number(message.deltaY) || 0, behavior: "auto" });
      return;
    }

    if (kind === "key") {
      const down = Number(message.action) === 0;
      const keyCode = Number(message.keyCode) || 0;
      const character = message.character ? String(message.character) : "";
      const target = document.activeElement || document.body || document.documentElement;
      target.dispatchEvent(new KeyboardEvent(down ? "keydown" : "keyup", {
        bubbles: true,
        cancelable: true,
        key: character || String(message.key || "Unidentified"),
        code: String(message.code || ""),
        keyCode,
        which: keyCode,
        repeat: Boolean(message.repeat),
        ...modifiers
      }));
      if (down && character) {
        const before = new InputEvent("beforeinput", {
          bubbles: true,
          cancelable: true,
          data: character,
          inputType: "insertText"
        });
        if (target.dispatchEvent(before) && !before.defaultPrevented) {
          if (typeof target.setRangeText === "function"
              && typeof target.selectionStart === "number") {
            const start = target.selectionStart;
            const end = target.selectionEnd == null ? start : target.selectionEnd;
            target.setRangeText(character, start, end, "end");
          } else if (target.isContentEditable) {
            document.execCommand("insertText", false, character);
          }
          target.dispatchEvent(new InputEvent("input", {
            bubbles: true,
            data: character,
            inputType: "insertText"
          }));
        }
      } else if (down && keyCode === 8
          && typeof target.setRangeText === "function"
          && typeof target.selectionStart === "number") {
        const start = target.selectionStart;
        const end = target.selectionEnd == null ? start : target.selectionEnd;
        target.setRangeText("", start === end ? Math.max(0, start - 1) : start,
          end, "end");
        target.dispatchEvent(new InputEvent("input", {
          bubbles: true,
          data: null,
          inputType: "deleteContentBackward"
        }));
      } else if (down && keyCode === 13 && target.form) {
        if (typeof target.form.requestSubmit === "function") {
          target.form.requestSubmit();
        } else {
          target.form.submit();
        }
      }
    }
  }

  port.onMessage.addListener(message => {
    if (!message || typeof message !== "object") return;
    if (message.type === "eval") {
      diagnostic("eval-received", `length=${String(message.code || "").length}`);
      executeInPage(message.code, "eval");
    } else if (message.type === "query-result") {
      diagnostic("query-result-received", `id=${message.id} success=${Boolean(message.success)}`);
      window.dispatchEvent(new CustomEvent("__nh_mcef_query_result", {
        detail: message
      }));
    } else if (message.type === "input") {
      dispatchInput(message);
    }
  });

  port.onDisconnect.addListener(() => {
    console.error("[NH-GECKO-EXT] native port disconnected");
  });

  if (diagnosticsEnabled) {
    window.addEventListener("error", event => diagnostic("window-error",
      `${event.message || ""} ${event.filename || ""}:${event.lineno || 0}`));
    window.addEventListener("unhandledrejection", event => diagnostic("unhandled-rejection",
      event.reason && event.reason.stack ? event.reason.stack : event.reason));
    document.addEventListener("visibilitychange", () => diagnostic("visibility-change",
      `${document.visibilityState} hidden=${document.hidden}`));
    window.addEventListener("pageshow", event => diagnostic("pageshow", `persisted=${event.persisted}`));
    window.addEventListener("pagehide", event => diagnostic("pagehide", `persisted=${event.persisted}`));
    window.addEventListener("DOMContentLoaded", () => {
      diagnostic("dom-content-loaded", document.title);
      emitPagePerf("dom-content-loaded");
    }, { once: true });
  }
  window.addEventListener("load", () => {
    diagnostic("window-load", document.title);
    emitPagePerf("window-load");
    port.postMessage({
      type: "frame-load",
      url: location.href,
      mainFrame,
      title: document.title || ""
    });
  }, { once: true });

  for (const eventName of ["popstate", "hashchange"]) {
    window.addEventListener(eventName, () => port.postMessage({
      type: "history",
      url: location.href,
      mainFrame
    }));
  }
  if (diagnosticsEnabled) setInterval(() => emitPagePerf("interval"), 1000);
})();

(function () {
  var header = document.querySelector(".site-header");
  var toggle = document.querySelector(".nav-toggle");
  var nav = document.querySelector(".nav-links");

  if (toggle && nav) {
    toggle.addEventListener("click", function () {
      var open = nav.classList.toggle("is-open");
      toggle.setAttribute("aria-expanded", open ? "true" : "false");
      toggle.setAttribute("aria-label", open ? "Close menu" : "Open menu");
    });

    nav.querySelectorAll("a").forEach(function (link) {
      link.addEventListener("click", function () {
        nav.classList.remove("is-open");
        toggle.setAttribute("aria-expanded", "false");
        toggle.setAttribute("aria-label", "Open menu");
      });
    });
  }

  if (header) {
    var onScroll = function () {
      header.classList.toggle("is-scrolled", window.scrollY > 12);
    };
    onScroll();
    window.addEventListener("scroll", onScroll, { passive: true });
  }

  var downloadModal = document.createElement("div");
  downloadModal.className = "download-modal";
  downloadModal.setAttribute("hidden", "");
  downloadModal.innerHTML =
    '<div class="download-dialog" role="dialog" aria-modal="true" aria-labelledby="download-dialog-title" tabindex="-1">' +
    '<button class="download-close" type="button" aria-label="Close download options">' +
    '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true"><path d="M6 6l12 12M18 6L6 18"/></svg>' +
    "</button>" +
    '<p class="section-label">Two editions</p>' +
    '<h2 id="download-dialog-title">Choose your SUPER RIDEX</h2>' +
    '<p class="download-dialog-copy">Pick the edition that fits how you drive, then install the Android APK.</p>' +
    '<div class="download-options">' +
    '<article class="download-card">' +
    "<h3>SUPER RIDEX</h3>" +
    '<p class="download-card-tag">Standard</p>' +
    '<p class="download-card-copy">Core accessibility auto-accept for ride and delivery apps.</p>' +
    '<a class="btn btn-primary btn-block" href="downloads/superridex.apk" download>Download Standard</a>' +
    "</article>" +
    '<article class="download-card is-pro">' +
    '<p class="download-badge">Pro</p>' +
    "<h3>SUPER RIDEX PRO</h3>" +
    '<p class="download-card-tag">Pro edition</p>' +
    '<p class="download-card-copy">The Pro app with extra assistive tools and more control on shift.</p>' +
    '<a class="btn btn-pro btn-block" href="downloads/superridex-pro.apk" download>Download Pro</a>' +
    "</article>" +
    "</div>" +
    "</div>";
  document.body.appendChild(downloadModal);

  var downloadDialog = downloadModal.querySelector(".download-dialog");
  var lastDownloadTrigger = null;
  var downloadFocusables = [];

  var getDownloadFocusables = function () {
    return Array.prototype.slice.call(
      downloadDialog.querySelectorAll('a[href], button:not([disabled])')
    );
  };

  var closeDownloadModal = function () {
    if (downloadModal.hasAttribute("hidden")) return;
    downloadModal.setAttribute("hidden", "");
    document.body.classList.remove("modal-open");
    if (lastDownloadTrigger && typeof lastDownloadTrigger.focus === "function") {
      lastDownloadTrigger.focus();
    }
  };

  var openDownloadModal = function (trigger) {
    lastDownloadTrigger = trigger || null;
    downloadModal.removeAttribute("hidden");
    document.body.classList.add("modal-open");
    if (nav && toggle) {
      nav.classList.remove("is-open");
      toggle.setAttribute("aria-expanded", "false");
      toggle.setAttribute("aria-label", "Open menu");
    }
    downloadFocusables = getDownloadFocusables();
    downloadDialog.focus();
  };

  document.addEventListener("click", function (event) {
    var trigger = event.target.closest("a.js-download");
    if (trigger) {
      event.preventDefault();
      openDownloadModal(trigger);
      return;
    }

    if (event.target === downloadModal || event.target.closest(".download-close")) {
      closeDownloadModal();
      return;
    }

    if (event.target.closest(".download-modal a[download]")) {
      closeDownloadModal();
    }
  });

  document.addEventListener("keydown", function (event) {
    if (downloadModal.hasAttribute("hidden")) return;

    if (event.key === "Escape") {
      event.preventDefault();
      closeDownloadModal();
      return;
    }

    if (event.key !== "Tab") return;

    downloadFocusables = getDownloadFocusables();
    if (!downloadFocusables.length) return;

    var first = downloadFocusables[0];
    var last = downloadFocusables[downloadFocusables.length - 1];

    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault();
      first.focus();
    }
  });

  if ("IntersectionObserver" in window) {
    var observer = new IntersectionObserver(
      function (entries) {
        entries.forEach(function (entry) {
          if (entry.isIntersecting) {
            entry.target.classList.add("is-visible");
            observer.unobserve(entry.target);
          }
        });
      },
      { threshold: 0.12, rootMargin: "0px 0px -40px 0px" }
    );
    document.querySelectorAll(".reveal").forEach(function (el) {
      observer.observe(el);
    });
  } else {
    document.querySelectorAll(".reveal").forEach(function (el) {
      el.classList.add("is-visible");
    });
  }
})();

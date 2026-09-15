(() => {
  const dialog = document.getElementById('diagram-zoom-dialog');
  const viewport = document.getElementById('diagram-zoom-viewport');
  const content = document.getElementById('diagram-zoom-content');
  const canvas = document.getElementById('sequence-canvas');
  const level = document.getElementById('diagram-zoom-level');
  let figure, placeholder, opener, savedStyle, savedScroll, naturalWidth, scale, fit;
  function resize(center = false) {
    const x = (viewport.scrollLeft + viewport.clientWidth / 2) / (figure.getBoundingClientRect().width || 1);
    const y = (viewport.scrollTop + viewport.clientHeight / 2) / (figure.getBoundingClientRect().height || 1);
    figure.style.width = `${naturalWidth * scale}px`;
    level.value = `${Math.round(scale * 100)}%`;
    dialog.querySelector('[data-diagram-zoom="-"]').disabled = scale <= 0.1;
    dialog.querySelector('[data-diagram-zoom="+"]').disabled = scale >= 4;
    if (center) viewport.scrollTo({left:x * figure.offsetWidth - viewport.clientWidth / 2, top:y * figure.offsetHeight - viewport.clientHeight / 2, behavior:'instant'});
  }
  function fitWidth() {
    scale = Math.max(0.1, Math.min(1, (viewport.clientWidth - 32) / naturalWidth));
    resize();
    viewport.scrollTo({left:0, top:0, behavior:'instant'});
  }
  function open(button) {
    if (dialog.open) return;
    figure = button.closest('.sequence-card').querySelector('.sequence-figure');
    opener = button;
    savedStyle = figure.getAttribute('style');
    savedScroll = {left:canvas.scrollLeft, top:canvas.scrollTop, page:scrollY};
    naturalWidth = figure.querySelector('svg').viewBox.baseVal.width;
    placeholder = document.createElement('div');
    placeholder.style.height = `${figure.getBoundingClientRect().height}px`;
    figure.replaceWith(placeholder);
    content.append(figure);
    document.getElementById('diagram-zoom-title').textContent = `${button.closest('.sequence-card').querySelector('h3').textContent} · 다이어그램 확대`;
    dialog.showModal();
    fit = true;
    fitWidth();
    dialog.querySelector('[data-diagram-close]').focus({preventScroll:true});
  }
  canvas.addEventListener('click', event => {
    const button = event.target.closest('[data-diagram-open]');
    if (button) return open(button);
    // Message clicks keep their existing call browser and requirement actions.
    if (event.target.closest('.source-message,[data-event-details],[data-queue-details],a,button')) return;
    const drawing = event.target.closest('.sequence-figure');
    const trigger = drawing?.closest('.sequence-card').querySelector('[data-diagram-open]');
    if (trigger && !getSelection().toString()) open(trigger);
  });
  dialog.querySelector('[data-diagram-close]').addEventListener('click', () => dialog.close());
  function restore() {
    if (!figure) return;
    if (savedStyle === null) figure.removeAttribute('style');
    else figure.setAttribute('style', savedStyle);
    placeholder.replaceWith(figure);
    opener.focus({preventScroll:true});
    canvas.scrollTo({left:savedScroll.left, top:savedScroll.top, behavior:'instant'});
    scrollTo({top:savedScroll.page, behavior:'instant'});
    figure = null;
  }
  dialog.addEventListener('close', restore);
  dialog.addEventListener('click', event => {
    if (event.target !== dialog) return;
    const box = dialog.getBoundingClientRect();
    if (event.clientX < box.left || event.clientX > box.right || event.clientY < box.top || event.clientY > box.bottom) dialog.close();
  });
  dialog.querySelectorAll('[data-diagram-zoom]').forEach(button => button.addEventListener('click', () => {
    const action = button.dataset.diagramZoom;
    fit = action === 'fit';
    if (fit) return fitWidth();
    scale = action === 'actual' ? 1 : Math.max(0.1, Math.min(4, scale + (action === '+' ? 0.25 : -0.25)));
    resize(true);
  }));
  addEventListener('resize', () => { if (dialog.open && fit) fitWidth(); });
  // Restore the diagram to its source before the existing print styles run.
  addEventListener('beforeprint', () => {
    if (dialog.open) {
      dialog.close();
      restore();
    }
  });
})();

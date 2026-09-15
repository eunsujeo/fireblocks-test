(() => {
  for (const id of ['sequence-canvas', 'diagram-zoom-viewport']) {
    const viewport = document.getElementById(id);
    const dialog = viewport.closest('dialog');
    const overlay = document.createElement('div');
    overlay.className = 'participant-overlay';
    overlay.setAttribute('aria-hidden', 'true');
    overlay.hidden = true;
    dialog.append(overlay);
    let scheduled = false;
    const observed = new Set();
    function schedule() {
      if (scheduled) return;
      scheduled = true;
      requestAnimationFrame(render);
    }
    const resize = new ResizeObserver(schedule);
    resize.observe(viewport);
    function render() {
      scheduled = false;
      const diagrams = [...viewport.querySelectorAll('.sequence-figure > svg')];
      for (const svg of observed) if (!diagrams.includes(svg)) { resize.unobserve(svg); observed.delete(svg); }
      for (const svg of diagrams) if (!observed.has(svg)) { resize.observe(svg); observed.add(svg); }
      overlay.replaceChildren();
      overlay.hidden = true;
      if (!dialog.open) return;
      const area = viewport.getBoundingClientRect();
      const frame = dialog.getBoundingClientRect();
      const left = area.left + viewport.clientLeft;
      const top = area.top + viewport.clientTop;
      const right = left + viewport.clientWidth;
      overlay.style.left = `${left - frame.left - dialog.clientLeft}px`;
      overlay.style.top = `${top - frame.top - dialog.clientTop}px`;
      overlay.style.width = `${viewport.clientWidth}px`;
      let height = 0;
      for (const svg of diagrams) {
        const box = svg.getBoundingClientRect();
        if (!box.width || !box.height) continue;
        const actors = [...svg.querySelectorAll('[data-actor]')].map(group => ({
          rect:group.querySelector('rect'), text:group.querySelector('text')
        })).filter(actor => actor.rect && actor.text);
        if (!actors.length) continue;
        const headers = actors.map(actor => actor.rect.getBoundingClientRect());
        const scale = box.width / svg.viewBox.baseVal.width;
        const rowHeight = Math.max(...headers.map(rect => rect.height));
        const panelHeight = rowHeight + 12 * scale;
        // Display only after the original participant row has left the viewport.
        if (Math.max(...headers.map(rect => rect.bottom)) > top || box.bottom <= top + panelHeight) continue;
        const start = Math.max(left, box.left), end = Math.min(right, box.right);
        if (end <= start) continue;
        const panel = document.createElement('div');
        panel.className = 'participant-panel';
        Object.assign(panel.style, {left:`${start - left}px`, width:`${end - start}px`, height:`${panelHeight}px`});
        actors.forEach((actor, index) => {
          const rect = headers[index];
          const label = document.createElement('div');
          label.className = 'participant-label';
          label.textContent = actor.text.textContent;
          Object.assign(label.style, {
            left:`${rect.left - start}px`, top:`${6 * scale}px`, width:`${rect.width}px`, height:`${rect.height}px`,
            background:actor.rect.getAttribute('fill'), color:actor.text.getAttribute('fill'),
            borderColor:actor.rect.getAttribute('stroke'), borderRadius:`${Number(actor.rect.getAttribute('rx') || 0) * scale}px`,
            fontSize:`${Number(actor.text.getAttribute('font-size')) * scale}px`, fontWeight:actor.text.getAttribute('font-weight') || '400'
          });
          panel.append(label);
        });
        overlay.append(panel);
        height = Math.max(height, panelHeight);
      }
      overlay.style.height = `${height}px`;
      overlay.hidden = !height;
    }
    viewport.addEventListener('scroll', schedule, {passive:true});
    new MutationObserver(schedule).observe(viewport, {childList:true, subtree:true});
    new MutationObserver(schedule).observe(dialog, {attributes:true, attributeFilter:['open']});
    addEventListener('resize', schedule);
    schedule();
  }
})();

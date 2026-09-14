(() => {
  const terms = [...document.querySelectorAll('.policy-term')];
  if (!terms.length) return;
  const tip = document.createElement('div');
  tip.id = 'policy-term-explanation';
  tip.className = 'policy-term-explanation';
  tip.setAttribute('role', 'tooltip');
  tip.setAttribute('popover', 'manual');
  tip.hidden = true;
  document.body.append(tip);
  for (const term of terms) {
    term.dataset.explanation = term.title;
    term.removeAttribute('title');
  }
  let active = null, timer;
  function hide() {
    clearTimeout(timer);
    if (tip.matches(':popover-open')) tip.hidePopover();
    active?.removeAttribute('aria-describedby');
    tip.hidden = true;
    active = null;
  }
  function position() {
    if (!active) return;
    const rect = active.getBoundingClientRect();
    if (rect.bottom <= 0 || rect.top >= innerHeight) return hide();
    const left = Math.max(12, Math.min(rect.left, innerWidth - tip.offsetWidth - 12));
    const below = rect.bottom + 8;
    const top = below + tip.offsetHeight <= innerHeight - 12 ? below : Math.max(12, rect.top - tip.offsetHeight - 8);
    tip.style.left = `${left}px`;
    tip.style.top = `${top}px`;
  }
  function show(term) {
    clearTimeout(timer);
    if (active === term) return;
    hide();
    active = term;
    tip.textContent = term.dataset.explanation;
    term.setAttribute('aria-describedby', tip.id);
    tip.hidden = false;
    if (tip.showPopover) tip.showPopover();
    position();
  }
  for (const term of terms) {
    term.addEventListener('pointerenter', () => show(term));
    term.addEventListener('focus', () => show(term));
    term.addEventListener('click', () => show(term));
    term.addEventListener('pointerleave', leave);
    term.addEventListener('blur', hide);
  }
  function leave() {
    clearTimeout(timer);
    timer = setTimeout(() => {
      if (document.activeElement !== active) hide();
    }, 180);
  }
  tip.addEventListener('pointerenter', () => clearTimeout(timer));
  tip.addEventListener('pointerleave', leave);
  document.addEventListener('click', event => {
    if (!event.target.closest('.policy-term') && !tip.contains(event.target)) hide();
  });
  document.addEventListener('keydown', event => {
    if (event.key === 'Escape' && active) {
      event.preventDefault();
      event.stopImmediatePropagation();
      hide();
    }
  }, true);
  document.addEventListener('scroll', position, true);
  addEventListener('resize', position);
  addEventListener('beforeprint', hide);
})();

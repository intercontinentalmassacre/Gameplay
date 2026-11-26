package app.gamenative.html5.host

// Fit the play surface and the HUD above it. Help text and credits below the board
// stay scrollable without making the play surface unnecessarily small.
internal const val FIT_PLAY_SURFACE_JS = """
(() => {
    const body = document.body;
    if (!body || window.__gameplayFitInstalled || (parseFloat(getComputedStyle(body).zoom) || 1) !== 1) return;
    window.__gameplayFitInstalled = true;
    let board;
    let pending = false;
    const selector = '.game-container, #game-container, [role="grid"], canvas';
    // Center the game's wrapper as a unit so controls below a canvas stay aligned.
    // Full-width layouts and overlays positioned by the engine keep their own layout.
    function centerSurface() {
        for (let box = board; box && box !== body; box = box.parentElement) {
            const parent = box.parentElement;
            if (parent.clientWidth <= box.offsetWidth + 1) continue;
            const style = getComputedStyle(box);
            if (style.position === 'absolute' || style.position === 'fixed' || style.cssFloat !== 'none') return;
            if (style.display === 'inline' && box.tagName === 'CANVAS' && parent === body) {
                box.style.display = 'block';
            } else if (style.display !== 'block') {
                continue;
            }
            box.style.marginInline = 'auto';
            return;
        }
    }
    function fit() {
        pending = false;
        if (!board || !board.isConnected) return;
        // Older Android WebViews report element bounds in pre-zoom coordinates.
        // Measure at the original scale, then apply the result before the next paint.
        body.style.zoom = '1';
        const rect = board.getBoundingClientRect();
        const bottom = rect.bottom + window.scrollY;
        if (rect.width && rect.height && bottom > 0) {
            body.style.zoom = String(Math.min(1, (innerHeight - 2) / bottom, innerWidth / rect.width));
            centerSurface();
        }
    }
    function schedule() {
        if (!pending) {
            pending = true;
            requestAnimationFrame(fit);
        }
    }
    const sizes = new ResizeObserver(schedule);
    function locate() {
        if (board && board.isConnected) return;
        board = Array.from(document.querySelectorAll(selector)).find(el =>
            el.offsetWidth > 0 && el.offsetHeight > 0);
        if (board) {
            sizes.disconnect();
            sizes.observe(board);
            schedule();
        }
    }
    new MutationObserver(locate).observe(body, { childList: true, subtree: true });
    window.addEventListener('resize', schedule);
    if (document.fonts) document.fonts.ready.then(schedule);
    locate();
})();
"""

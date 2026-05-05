// Lightweight sortable + per-column filterable table helper.
//
// Wires up any table marked `class="sortable-table"` with:
//   - clickable column headers in the `.sort-row` (asc → desc → off)
//   - text inputs in the `.filter-row` whose `data-filter-col` indexes
//     the column they filter against
//   - per-row sort keys via `data-<key>` attributes on each <tr>
//
// Markup contract:
//   <table class="sortable-table" data-count-target="#some-counter">
//     <thead>
//       <tr class="sort-row">
//         <th data-sort-key="id">ID <span class="sort-indicator"></span></th>
//         ...
//       </tr>
//       <tr class="filter-row">
//         <th><input type="text" data-filter-col="0" placeholder="filter ID"></th>
//         ...
//       </tr>
//     </thead>
//     <tbody>
//       <tr data-id="…" data-title="…" data-difficulty="…">…</tr>
//     </tbody>
//   </table>
//
// Optional `data-sort-numeric="true"` on a header marks numeric sort.
// Optional `data-sort-order="EASY,MEDIUM,HARD"` declares an enum order
// (useful for difficulty / status columns).

(function () {
  const DEFAULT_DEBOUNCE_MS = 60;

  function init(table) {
    if (table.__sortableInit) return;
    table.__sortableInit = true;
    const tbody = table.querySelector('tbody');
    if (!tbody) return;
    // Rows tagged with data-skip-sort="true" are excluded from
    // sort + filter + count. Used for drilldown / detail siblings
    // that should stay attached to their preceding primary row
    // (e.g. the contamination table's "Show prompt material"
    // expansion or the leaderboard's `.lb-drill-row` per-bug
    // breakdown). Without this filter, sorting reordered every
    // <tr> in tbody and stranded drilldowns away from their
    // parents.
    const allRows = Array.from(tbody.querySelectorAll('tr'));
    const rows = allRows.filter(r => r.getAttribute('data-skip-sort') !== 'true');
    const sortHeaders = table.querySelectorAll('.sort-row th[data-sort-key]');
    const filters = table.querySelectorAll('.filter-row input[data-filter-col]');
    const counterSel = table.dataset.countTarget;
    const counter = counterSel ? document.querySelector(counterSel) : null;

    // Per-filter state: either { kind: 'text' } (use input.value) or
    // { kind: 'multi', selected: Set<string> } populated by the
    // checklist popup we install below for low-cardinality columns.
    // Index lines up with `filters`.
    const MULTI_SELECT_THRESHOLD = 8;
    const filterStates = Array.from(filters).map(() => ({ kind: 'text' }));

    // For each filter column, count distinct cell values. If <= the
    // threshold, swap the text input out for a checklist-popup button
    // -- much faster to filter "Status = PASSED OR FAILED" than typing
    // a regex into a text box. High-cardinality columns keep the
    // existing substring filter.
    function buildMultiSelect(input, idx) {
      const colIdx = parseInt(input.getAttribute('data-filter-col'), 10);
      const distinct = new Set();
      for (const row of rows) {
        const cells = row.querySelectorAll('td');
        const txt = (cells[colIdx]?.textContent || '').trim();
        if (txt) distinct.add(txt);
      }
      // Skip if cardinality is too high (free-text column) or trivial
      // (single value -- nothing to filter on).
      if (distinct.size > MULTI_SELECT_THRESHOLD || distinct.size <= 1) return;
      const values = Array.from(distinct).sort();
      // Build the popup container: a button that, when clicked, shows
      // a small panel of checkboxes anchored below it. Click-outside
      // closes. Style is intentionally low-key to fit the existing
      // filter-row visual density.
      const wrap = document.createElement('span');
      wrap.style.cssText = 'position:relative; display:inline-block; width:100%';
      const btn = document.createElement('button');
      btn.type = 'button';
      btn.style.cssText = 'width:100%; padding:1px 4px; font-size:0.85em; ' +
        'text-align:left; background:#fff; border:1px solid #d1d5db; ' +
        'border-radius:3px; cursor:pointer; box-sizing:border-box';
      btn.textContent = 'all (' + values.length + ')';
      const panel = document.createElement('div');
      panel.style.cssText = 'display:none; position:absolute; top:100%; left:0; ' +
        'z-index:50; background:#fff; border:1px solid #d1d5db; border-radius:3px; ' +
        'box-shadow:0 2px 6px rgba(0,0,0,0.1); padding:4px 8px; min-width:8em; ' +
        'max-height:18em; overflow:auto; font-size:0.85em';
      const selected = new Set();
      function refreshLabel() {
        btn.textContent = selected.size === 0
          ? 'all (' + values.length + ')'
          : selected.size + ' selected';
      }
      const ctrlRow = document.createElement('div');
      ctrlRow.style.cssText = 'border-bottom:1px solid #e5e7eb; margin-bottom:4px; ' +
        'padding-bottom:4px; display:flex; gap:0.4em';
      const allBtn = document.createElement('button');
      allBtn.type = 'button';
      allBtn.style.cssText = 'font-size:0.8em; padding:0 4px';
      allBtn.textContent = 'all';
      const noneBtn = document.createElement('button');
      noneBtn.type = 'button';
      noneBtn.style.cssText = 'font-size:0.8em; padding:0 4px';
      noneBtn.textContent = 'none';
      ctrlRow.appendChild(allBtn);
      ctrlRow.appendChild(noneBtn);
      panel.appendChild(ctrlRow);
      const cbBoxes = [];
      for (const v of values) {
        const lab = document.createElement('label');
        lab.style.cssText = 'display:block; cursor:pointer; padding:1px 0';
        const cb = document.createElement('input');
        cb.type = 'checkbox'; cb.value = v;
        cb.style.cssText = 'margin-right:4px';
        cb.addEventListener('change', () => {
          if (cb.checked) selected.add(v); else selected.delete(v);
          refreshLabel();
          applyFilters();
        });
        lab.appendChild(cb);
        lab.appendChild(document.createTextNode(v));
        panel.appendChild(lab);
        cbBoxes.push(cb);
      }
      allBtn.addEventListener('click', () => {
        cbBoxes.forEach(cb => { cb.checked = true; selected.add(cb.value); });
        refreshLabel(); applyFilters();
      });
      noneBtn.addEventListener('click', () => {
        cbBoxes.forEach(cb => { cb.checked = false; });
        selected.clear(); refreshLabel(); applyFilters();
      });
      btn.addEventListener('click', (e) => {
        e.stopPropagation();
        panel.style.display = panel.style.display === 'block' ? 'none' : 'block';
      });
      document.addEventListener('click', (e) => {
        if (!wrap.contains(e.target)) panel.style.display = 'none';
      });
      wrap.appendChild(btn);
      wrap.appendChild(panel);
      // Replace the input in-place; preserve data-filter-col on wrap so
      // applyFilters can still resolve the column index.
      wrap.setAttribute('data-filter-col', colIdx);
      input.replaceWith(wrap);
      filterStates[idx] = { kind: 'multi', selected, colIdx };
    }
    Array.from(filters).forEach(buildMultiSelect);

    function sortValue(row, header) {
      const key = header.dataset.sortKey;
      const raw = row.dataset[key] || '';
      if (header.dataset.sortNumeric === 'true') {
        const n = parseFloat(raw.replace(/[, ]/g, ''));
        return Number.isFinite(n) ? n : Number.MAX_VALUE;
      }
      const order = header.dataset.sortOrder;
      if (order) {
        const idx = order.split(',').map(s => s.trim().toUpperCase())
                         .indexOf((raw || '').toUpperCase());
        return idx === -1 ? 99 : idx;
      }
      return (raw || '').toLowerCase();
    }

    function applyFilters() {
      // Snapshot per-filter values: text inputs read live from the
      // input.value; multi-select states are already in filterStates.
      const snapshots = Array.from(filters).map((el, i) => {
        const st = filterStates[i];
        if (st.kind === 'multi') {
          // Multi-select: 'el' has been replaced; column index is
          // captured in filterStates[i].colIdx.
          return { kind: 'multi', sel: st.selected, colIdx: st.colIdx };
        }
        return {
          kind: 'text',
          term: el.value.trim().toLowerCase(),
          colIdx: parseInt(el.getAttribute('data-filter-col'), 10)
        };
      });
      let visible = 0;
      for (const row of rows) {
        const cells = row.querySelectorAll('td');
        let match = true;
        for (const s of snapshots) {
          const txt = (cells[s.colIdx]?.textContent || '').trim();
          if (s.kind === 'multi') {
            if (s.sel.size === 0) continue;  // no selection = no filter
            if (!s.sel.has(txt)) { match = false; break; }
          } else {
            if (!s.term) continue;
            if (!txt.toLowerCase().includes(s.term)) { match = false; break; }
          }
        }
        row.classList.toggle('filtered-out', !match);
        if (match) visible++;
      }
      if (counter) {
        counter.textContent = '— ' + visible + ' of ' + rows.length + ' shown';
      }
    }

    function applySort(header, direction) {
      if (!direction) {
        rows.forEach(r => tbody.appendChild(r));
        return;
      }
      const sign = direction === 'asc' ? 1 : -1;
      const sorted = rows.slice().sort((a, b) => {
        const va = sortValue(a, header), vb = sortValue(b, header);
        if (va < vb) return -1 * sign;
        if (va > vb) return  1 * sign;
        return 0;
      });
      sorted.forEach(r => tbody.appendChild(r));
    }

    let active = { header: null, direction: null };
    sortHeaders.forEach(h => {
      h.addEventListener('click', () => {
        let nextDir;
        if (active.header !== h) nextDir = 'asc';
        else if (active.direction === 'asc') nextDir = 'desc';
        else if (active.direction === 'desc') nextDir = null;
        else nextDir = 'asc';
        sortHeaders.forEach(x => x.removeAttribute('data-sort-direction'));
        if (nextDir) h.setAttribute('data-sort-direction', nextDir);
        active = { header: h, direction: nextDir };
        applySort(h, nextDir);
      });
    });

    let timer;
    filters.forEach(input => {
      input.addEventListener('input', () => {
        clearTimeout(timer);
        timer = setTimeout(applyFilters, DEFAULT_DEBOUNCE_MS);
      });
    });

    // Optional global search box. Wire up via `data-search-target=
    // "#some-input"`. The input text is matched against the full
    // visible text of every row (across all columns) AND combined
    // with the per-column filters above.
    const searchSel = table.dataset.searchTarget;
    const searchInput = searchSel ? document.querySelector(searchSel) : null;
    let searchTerm = '';
    if (searchInput) {
      searchInput.addEventListener('input', () => {
        clearTimeout(timer);
        timer = setTimeout(() => {
          searchTerm = searchInput.value.trim().toLowerCase();
          applyFilters();
        }, DEFAULT_DEBOUNCE_MS);
      });
    }

    // Re-bind applyFilters to also honor the global search term. We
    // wrap the existing per-column logic instead of duplicating it.
    const baseApplyFilters = applyFilters;
    applyFilters = function () {
      const snapshots = Array.from(filters).map((el, i) => {
        const st = filterStates[i];
        if (st.kind === 'multi') {
          return { kind: 'multi', sel: st.selected, colIdx: st.colIdx };
        }
        return {
          kind: 'text',
          term: el.value.trim().toLowerCase(),
          colIdx: parseInt(el.getAttribute('data-filter-col'), 10)
        };
      });
      let visible = 0;
      for (const row of rows) {
        const cells = row.querySelectorAll('td');
        let match = true;
        for (const s of snapshots) {
          const txt = (cells[s.colIdx]?.textContent || '').trim();
          if (s.kind === 'multi') {
            if (s.sel.size === 0) continue;
            if (!s.sel.has(txt)) { match = false; break; }
          } else {
            if (!s.term) continue;
            if (!txt.toLowerCase().includes(s.term)) { match = false; break; }
          }
        }
        if (match && searchTerm) {
          const allText = row.textContent.toLowerCase();
          if (!allText.includes(searchTerm)) match = false;
        }
        row.classList.toggle('filtered-out', !match);
        if (match) visible++;
      }
      if (counter) {
        counter.textContent = '— ' + visible + ' of ' + rows.length + ' shown';
      }
    };

    // Optional CSV export button. Wire up via `data-csv-target=
    // "#some-button"` and `data-csv-filename="…"`. The export honors
    // the active filter / search state — only currently-visible rows
    // get written. Headers come from the .sort-row text.
    const csvSel = table.dataset.csvTarget;
    const csvBtn = csvSel ? document.querySelector(csvSel) : null;
    if (csvBtn) {
      csvBtn.addEventListener('click', () => {
        const filename = table.dataset.csvFilename || 'export.csv';
        const headers = Array.from(table.querySelectorAll('.sort-row th'))
          .map(h => h.textContent.replace(/\s+/g, ' ').trim());
        const visibleRows = rows.filter(r => !r.classList.contains('filtered-out'));
        const csvRows = [headers].concat(visibleRows.map(r =>
          Array.from(r.querySelectorAll('td')).map(td =>
            td.textContent.replace(/\s+/g, ' ').trim())));
        const csv = csvRows.map(cols => cols.map(escapeCsv).join(',')).join('\r\n');
        const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' });
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url; a.download = filename;
        document.body.appendChild(a); a.click();
        setTimeout(() => { document.body.removeChild(a); URL.revokeObjectURL(url); }, 0);
      });
    }

    applyFilters();
  }

  function escapeCsv(s) {
    if (s == null) return '';
    const needsQuotes = /[",\r\n]/.test(s);
    return needsQuotes ? '"' + s.replace(/"/g, '""') + '"' : s;
  }

  function initAll() {
    document.querySelectorAll('table.sortable-table').forEach(init);
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', initAll);
  } else {
    initAll();
  }

  // Expose for ad-hoc use (e.g. after htmx swaps).
  window.SortableTable = { init, initAll };
})();

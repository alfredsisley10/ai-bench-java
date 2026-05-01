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
    const rows = Array.from(tbody.querySelectorAll('tr'));
    const sortHeaders = table.querySelectorAll('.sort-row th[data-sort-key]');
    const filters = table.querySelectorAll('.filter-row input[data-filter-col]');
    const counterSel = table.dataset.countTarget;
    const counter = counterSel ? document.querySelector(counterSel) : null;

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
      const terms = Array.from(filters).map(i =>
        i.value.trim().toLowerCase());
      let visible = 0;
      for (const row of rows) {
        const cells = row.querySelectorAll('td');
        let match = true;
        for (let i = 0; i < terms.length; i++) {
          if (!terms[i]) continue;
          // data-filter-col is the AUTHORITATIVE cell index. Filters
          // skip the checkbox column 0, so filters[0] has
          // data-filter-col="1". Reading cells[i] (filter array index)
          // checks the wrong cell — every filter shifts left by one
          // and only "works" by coincidence when adjacent columns
          // share text. data-filter-col makes it exact.
          const colIdx = parseInt(filters[i].getAttribute('data-filter-col'), 10);
          const txt = (cells[colIdx]?.textContent || '').toLowerCase();
          if (!txt.includes(terms[i])) { match = false; break; }
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
      const terms = Array.from(filters).map(i => i.value.trim().toLowerCase());
      let visible = 0;
      for (const row of rows) {
        const cells = row.querySelectorAll('td');
        let match = true;
        for (let i = 0; i < terms.length; i++) {
          if (!terms[i]) continue;
          // data-filter-col is the AUTHORITATIVE cell index. Filters
          // skip the checkbox column 0, so filters[0] has
          // data-filter-col="1". Reading cells[i] (filter array index)
          // checks the wrong cell — every filter shifts left by one
          // and only "works" by coincidence when adjacent columns
          // share text. data-filter-col makes it exact.
          const colIdx = parseInt(filters[i].getAttribute('data-filter-col'), 10);
          const txt = (cells[colIdx]?.textContent || '').toLowerCase();
          if (!txt.includes(terms[i])) { match = false; break; }
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

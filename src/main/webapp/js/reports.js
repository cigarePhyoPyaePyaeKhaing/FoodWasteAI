const Reports = {
  wasteRecords: [],
  redistStats: {},
  recommendations: [],
  activePeriod: 'month',

  // ─── Date helpers ───────────────────────────────────────────────────────────

  _toISO(date) {
    // Returns yyyy-MM-dd string for the given Date object (local time)
    const y = date.getFullYear();
    const m = String(date.getMonth() + 1).padStart(2, '0');
    const d = String(date.getDate()).padStart(2, '0');
    return `${y}-${m}-${d}`;
  },

  _dateRangeFor(period) {
    const today = new Date();
    today.setHours(0, 0, 0, 0);
    const endDate = this._toISO(today);

    if (period === 'today') {
      return { startDate: endDate, endDate };
    }

    if (period === 'week') {
      // Monday of the current week
      const day = today.getDay(); // 0 = Sunday, 1 = Monday …
      const diff = (day === 0) ? -6 : 1 - day; // go back to Monday
      const monday = new Date(today);
      monday.setDate(today.getDate() + diff);
      return { startDate: this._toISO(monday), endDate };
    }

    if (period === 'month') {
      const firstOfMonth = new Date(today.getFullYear(), today.getMonth(), 1);
      return { startDate: this._toISO(firstOfMonth), endDate };
    }

    return null; // 'custom' — caller supplies dates
  },

  // ─── Button state ───────────────────────────────────────────────────────────

  _setActiveButton(period) {
    const ids = ['month', 'week', 'today', 'custom'];
    ids.forEach(p => {
      const btn = document.getElementById(`filter-btn-${p}`);
      if (!btn) return;
      btn.classList.toggle('btn-yellow', p === period);
      btn.classList.toggle('btn-glass',  p !== period);
    });

    // Show / hide the custom date picker row
    const picker = document.getElementById('custom-range-picker');
    if (picker) {
      picker.style.display = (period === 'custom') ? 'inline-flex' : 'none';
    }
  },

  // ─── Public period API ───────────────────────────────────────────────────────

  async setPeriod(period) {
    this.activePeriod = period;
    this._setActiveButton(period);

    if (period === 'custom') {
      // Just reveal the date picker — wait for applyCustomRange()
      return;
    }

    const range = this._dateRangeFor(period);
    await this.fetchWasteData(range.startDate, range.endDate);
    this.render();
  },

  async applyCustomRange() {
    const startInput = document.getElementById('custom-start-date');
    const endInput   = document.getElementById('custom-end-date');
    if (!startInput || !endInput) return;

    const startDate = startInput.value;
    const endDate   = endInput.value;

    if (!startDate || !endDate) {
      API.showToast('Please select both a start and end date.', 'warning');
      return;
    }
    if (startDate > endDate) {
      API.showToast('Start date must be on or before end date.', 'warning');
      return;
    }

    await this.fetchWasteData(startDate, endDate);
    this.render();
  },

  // ─── Init ────────────────────────────────────────────────────────────────────

  async init() {
    window.addEventListener('languageChanged', () => {
      this.render();
    });

    // Default to "This Month" — fetch dated waste data plus the other two
    // (redistStats and recommendations are not date-filterable from the backend,
    //  so they are always fetched unfiltered)
    const range = this._dateRangeFor('month');
    await Promise.all([
      this.fetchWasteData(range.startDate, range.endDate),
      this.fetchRedistStats(),
      this.fetchRecommendations()
    ]);
    this._setActiveButton('month');
    this.render();
  },

  // ─── Data fetching ───────────────────────────────────────────────────────────

  async fetchWasteData(startDate, endDate) {
    try {
      let res;
      if (startDate && endDate) {
        res = await API.get('/api/waste', { startDate, endDate });
      } else {
        res = await API.get('/api/waste');
      }
      this.wasteRecords = (res && Array.isArray(res.data)) ? res.data : [];
    } catch (err) {
      console.warn('Error fetching waste for reports:', err);
      this.wasteRecords = [];
    }
  },

  async fetchRedistStats() {
    try {
      const res = await API.get('/api/redistribution/stats');
      this.redistStats = (res && res.data) ? res.data : {};
    } catch (err) {
      console.warn('Error fetching redistribution stats for reports:', err);
      this.redistStats = {};
    }
  },

  async fetchRecommendations() {
    try {
      const res = await API.get('/api/recommendations');
      this.recommendations = (res && Array.isArray(res.data)) ? res.data : [];
    } catch (err) {
      console.warn('Error fetching recommendations for reports:', err);
      this.recommendations = [];
    }
  },

  // ─── Render ──────────────────────────────────────────────────────────────────

  render() {
    const totalLoss = this.wasteRecords.reduce((sum, r) => sum + Number(r.monetaryLoss || 0), 0);
    const savedKg = Number(this.redistStats.wasteReductionImpactKg || 0);

    const wasteEl    = document.getElementById('report-kpi-waste');
    const savedEl    = document.getElementById('report-kpi-saved');
    const lossEl     = document.getElementById('report-kpi-loss');

    if (wasteEl) {
      if (typeof I18n !== 'undefined' && typeof I18n.formatUnitAggregate === 'function') {
        wasteEl.textContent = I18n.formatUnitAggregate(this.wasteRecords, r => r.quantityWasted, r => r.unit, '');
      } else {
        const totalQty = this.wasteRecords.reduce((sum, r) => sum + Number(r.quantityWasted || 0), 0);
        wasteEl.textContent = totalQty.toFixed(1);
      }
    }

    if (savedEl) {
      if (this.redistStats && this.redistStats.rescuedDispatches && Array.isArray(this.redistStats.rescuedDispatches) && typeof I18n !== 'undefined') {
        savedEl.textContent = I18n.formatUnitAggregate(this.redistStats.rescuedDispatches, d => d.quantity, d => d.unit, '');
      } else if (savedKg > 0) {
        savedEl.textContent = savedKg.toFixed(1) + ' kg';
      } else {
        savedEl.textContent = '0.0';
      }
    }

    if (lossEl)     lossEl.textContent     = totalLoss.toLocaleString() + ' MMK';

    // ── Category matrix ────────────────────────────────────────────────────────
    const tbody = document.getElementById('reports-matrix-tbody');
    if (!tbody) return;

    if (this.wasteRecords.length === 0) {
      tbody.innerHTML = `
        <tr>
          <td colspan="5" style="text-align:center; padding:3rem; color:var(--text-muted);">
            <div style="font-size:2rem; margin-bottom:0.5rem;">📈</div>
            <div style="font-weight:700; color:var(--text-main); font-size:1.05rem;">${typeof I18n !== 'undefined' ? I18n.t('reports.empty.title') : 'No Waste Logs Recorded Yet'}</div>
            <div style="font-size:0.85rem; margin-top:0.25rem;">${typeof I18n !== 'undefined' ? I18n.t('reports.empty.desc') : 'Log waste events in the Waste Records section to view category analytics.'}</div>
          </td>
        </tr>
      `;
      return;
    }

    const categoryMap = {};
    const totalByUnit = {};  // sums quantityWasted per unit, e.g. { kg: 32.0, liter: 37.0 }

    for (const r of this.wasteRecords) {
      const cat  = r.foodItemName || ('Food Item #' + r.foodItemId);
      const unit = r.unit || 'units';
      if (!categoryMap[cat]) {
        categoryMap[cat] = { qty: 0, loss: 0, reason: r.reason || 'SPOILED', unit };
      }
      categoryMap[cat].qty  += Number(r.quantityWasted || 0);
      categoryMap[cat].loss += Number(r.monetaryLoss   || 0);
      if (r.unit) categoryMap[cat].unit = r.unit;

      // Accumulate per-unit total
      const qty = Number(r.quantityWasted || 0);
      totalByUnit[unit] = (totalByUnit[unit] || 0) + qty;
    }

    tbody.innerHTML = Object.keys(categoryMap).map(cat => {
      const item      = categoryMap[cat];
      const unitTotal = totalByUnit[item.unit] || 0;
      const sharePct  = unitTotal > 0 ? ((item.qty / unitTotal) * 100).toFixed(1) : '0.0';
      const reasonText = typeof I18n !== 'undefined' ? I18n.translateWasteReason(item.reason) : item.reason;
      return `
        <tr>
          <td><strong>${cat}</strong></td>
          <td>${item.qty.toFixed(1)} ${item.unit}</td>
          <td><strong style="color:var(--risk-high-text);">${item.loss.toLocaleString()} MMK</strong></td>
          <td>${sharePct}% <span style="font-size:0.72rem; color:var(--text-muted); font-weight:500;">(of ${item.unit})</span></td>
          <td>${reasonText}</td>
        </tr>
      `;
    }).join('');
  },

  // ─── CSV Export ──────────────────────────────────────────────────────────────

  exportCSV() {
    // Helper: wrap a value in quotes and escape any internal quotes/newlines
    function csvCell(value) {
      const str = (value === null || value === undefined) ? '' : String(value);
      // Escape double-quotes by doubling them
      const escaped = str.replace(/"/g, '""');
      // Always quote: guarantees commas, newlines inside values are safe
      return `"${escaped}"`;
    }

    // ── Period label & filename slug ─────────────────────────────────────────
    let periodLabel = 'This Month';
    let fileSlug    = 'ThisMonth';

    if (this.activePeriod === 'week') {
      periodLabel = 'This Week';
      fileSlug    = 'ThisWeek';
    } else if (this.activePeriod === 'today') {
      periodLabel = 'Today';
      fileSlug    = 'Today';
    } else if (this.activePeriod === 'custom') {
      const startVal = (document.getElementById('custom-start-date') || {}).value || '';
      const endVal   = (document.getElementById('custom-end-date')   || {}).value || '';
      if (startVal && endVal) {
        periodLabel = `Custom Range: ${startVal} to ${endVal}`;
        fileSlug    = `Custom_${startVal}_to_${endVal}`;
      } else {
        periodLabel = 'Custom Range';
        fileSlug    = 'Custom';
      }
    }

    // ── Today's date for filename & timestamp ────────────────────────────────
    const now       = new Date();
    const todayISO  = this._toISO(now);
    const timestamp = now.toLocaleString();

    // ── Read live KPI card values from DOM ───────────────────────────────────
    const kpiWaste    = (document.getElementById('report-kpi-waste')    || {}).textContent || '0.0';
    const kpiSaved    = (document.getElementById('report-kpi-saved')    || {}).textContent || '0.0';
    const kpiLoss     = (document.getElementById('report-kpi-loss')     || {}).textContent || '0 MMK';

    // ── Build CSV rows ───────────────────────────────────────────────────────
    const rows = [];

    // Section A: Report header
    rows.push([csvCell('FoodWaste AI - Sustainability & Financial Report')]);
    rows.push([csvCell('Period'), csvCell(periodLabel)]);
    rows.push([csvCell('Generated'), csvCell(timestamp)]);
    rows.push([]);  // blank separator

    // Section B: KPI Summary
    rows.push([csvCell('KPI Summary')]);
    rows.push([csvCell('Metric'), csvCell('Value')]);
    rows.push([csvCell('Total Food Waste'),       csvCell(kpiWaste)]);
    rows.push([csvCell('Food Saved via AI'),      csvCell(kpiSaved)]);
    rows.push([csvCell('Financial Waste Loss'),   csvCell(kpiLoss)]);
    rows.push([]);  // blank separator

    // Section C: Category Waste & Financial Loss Matrix
    rows.push([csvCell('Category Waste & Financial Loss Matrix')]);
    rows.push([
      csvCell('Food Category'),
      csvCell('Total Wasted'),
      csvCell('Monetary Loss (MMK)'),
      csvCell('Waste Share %'),
      csvCell('Primary Waste Reason')
    ]);

    if (this.wasteRecords.length === 0) {
      rows.push([csvCell('No waste logs recorded for this period')]);
    } else {
      const categoryMap  = {};
      const totalByUnit  = {};  // sums quantityWasted per unit

      for (const r of this.wasteRecords) {
        const cat  = r.foodItemName || ('Food Item #' + r.foodItemId);
        const unit = r.unit || 'units';
        if (!categoryMap[cat]) {
          categoryMap[cat] = { qty: 0, loss: 0, reason: r.reason || 'SPOILED', unit };
        }
        categoryMap[cat].qty  += Number(r.quantityWasted || 0);
        categoryMap[cat].loss += Number(r.monetaryLoss   || 0);
        if (r.unit) categoryMap[cat].unit = r.unit;

        const qty = Number(r.quantityWasted || 0);
        totalByUnit[unit] = (totalByUnit[unit] || 0) + qty;
      }

      for (const cat of Object.keys(categoryMap)) {
        const item      = categoryMap[cat];
        const unitTotal = totalByUnit[item.unit] || 0;
        const sharePct  = unitTotal > 0 ? ((item.qty / unitTotal) * 100).toFixed(1) : '0.0';
        const reason    = typeof I18n !== 'undefined' ? I18n.translateWasteReason(item.reason) : item.reason;
        rows.push([
          csvCell(cat),
          csvCell(`${item.qty.toFixed(1)} ${item.unit}`),
          csvCell(item.loss.toLocaleString()),
          csvCell(`${sharePct}% (of ${item.unit})`),
          csvCell(reason)
        ]);
      }
    }

    // ── Assemble CSV string ──────────────────────────────────────────────────
    const csvString = rows.map(row => row.join(',')).join('\r\n');

    // ── Trigger download ─────────────────────────────────────────────────────
    const blob     = new Blob(['\uFEFF' + csvString], { type: 'text/csv;charset=utf-8;' });
    const url      = URL.createObjectURL(blob);
    const filename = `FoodWasteAI_Report_${fileSlug}_${todayISO}.csv`;

    const link = document.createElement('a');
    link.href     = url;
    link.download = filename;
    link.style.display = 'none';
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
    setTimeout(() => URL.revokeObjectURL(url), 5000);

    API.showToast(`✅ Report exported: ${filename}`, 'success');
  }
};

document.addEventListener('DOMContentLoaded', () => {
  Reports.init();
});
const Reports = {
  wasteRecords: [],
  redistStats: {},
  recommendations: [],

  async init() {
    window.addEventListener('languageChanged', () => {
      this.render();
    });

    await Promise.all([
      this.fetchWasteData(),
      this.fetchRedistStats(),
      this.fetchRecommendations()
    ]);
    this.render();
  },

  async fetchWasteData() {
    try {
      const res = await API.get('/api/waste');
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

  render() {
    const totalLoss = this.wasteRecords.reduce((sum, r) => sum + Number(r.monetaryLoss || 0), 0);
    const savedKg = Number(this.redistStats.wasteReductionImpactKg || 0);

    const totalRecs = this.recommendations.length;
    const acceptedRecs = this.recommendations.filter(r => r.status === 'ACCEPTED').length;
    const adoptionRate = totalRecs > 0 ? Math.round((acceptedRecs / totalRecs) * 100) : 0;

    const wasteEl = document.getElementById('report-kpi-waste');
    const savedEl = document.getElementById('report-kpi-saved');
    const lossEl = document.getElementById('report-kpi-loss');
    const adoptionEl = document.getElementById('report-kpi-adoption');

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

    if (lossEl) lossEl.textContent = totalLoss.toLocaleString() + ' MMK';
    if (adoptionEl) adoptionEl.textContent = adoptionRate + '%';

    // Group by category or reason
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
    const totalWasteQty = this.wasteRecords.reduce((sum, r) => sum + Number(r.quantityWasted || 0), 0);

    for (const r of this.wasteRecords) {
      const cat = r.foodItemName || ('Food Item #' + r.foodItemId);
      if (!categoryMap[cat]) {
        categoryMap[cat] = { qty: 0, loss: 0, reason: r.reason || 'SPOILED', unit: r.unit || 'units' };
      }
      categoryMap[cat].qty += Number(r.quantityWasted || 0);
      categoryMap[cat].loss += Number(r.monetaryLoss || 0);
      if (r.unit) categoryMap[cat].unit = r.unit;
    }

    tbody.innerHTML = Object.keys(categoryMap).map(cat => {
      const item = categoryMap[cat];
      const sharePct = totalWasteQty > 0 ? ((item.qty / totalWasteQty) * 100).toFixed(1) : '0.0';
      const reasonText = typeof I18n !== 'undefined' ? I18n.translateWasteReason(item.reason) : item.reason;
      return `
        <tr>
          <td><strong>${cat}</strong></td>
          <td>${item.qty.toFixed(1)} ${item.unit}</td>
          <td><strong style="color:var(--risk-high-text);">${item.loss.toLocaleString()} MMK</strong></td>
          <td>${sharePct}%</td>
          <td>${reasonText}</td>
        </tr>
      `;
    }).join('');
  }
};

document.addEventListener('DOMContentLoaded', () => {
  Reports.init();
});

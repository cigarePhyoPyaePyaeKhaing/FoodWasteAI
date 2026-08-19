/**
 * FoodWaste AI - Reports Controller
 * Live dynamic reporting from MySQL inventory and waste records
 */
const Reports = {
  wasteRecords: [],
  redistStats: {},
  recommendations: [],

  async init() {
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
    const totalWasteKg = this.wasteRecords.reduce((sum, r) => sum + Number(r.quantityWasted || 0), 0);
    const totalLoss = this.wasteRecords.reduce((sum, r) => sum + Number(r.monetaryLoss || 0), 0);
    const savedKg = Number(this.redistStats.wasteReductionImpactKg || 0);

    const totalRecs = this.recommendations.length;
    const acceptedRecs = this.recommendations.filter(r => r.status === 'ACCEPTED').length;
    const adoptionRate = totalRecs > 0 ? Math.round((acceptedRecs / totalRecs) * 100) : 0;

    const wasteEl = document.getElementById('report-kpi-waste');
    const savedEl = document.getElementById('report-kpi-saved');
    const lossEl = document.getElementById('report-kpi-loss');
    const adoptionEl = document.getElementById('report-kpi-adoption');

    if (wasteEl) wasteEl.textContent = totalWasteKg.toFixed(1) + ' kg';
    if (savedEl) savedEl.textContent = savedKg.toFixed(1) + ' kg';
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
            <div style="font-weight:700; color:var(--text-main); font-size:1.05rem;">No Waste Logs Recorded Yet</div>
            <div style="font-size:0.85rem; margin-top:0.25rem;">Log waste events in the Waste Records section to view category analytics.</div>
          </td>
        </tr>
      `;
      return;
    }

    const categoryMap = {};
    for (const r of this.wasteRecords) {
      const cat = r.foodItemName || ('Food Item #' + r.foodItemId);
      if (!categoryMap[cat]) {
        categoryMap[cat] = { qty: 0, loss: 0, reason: r.reason || 'SPOILED' };
      }
      categoryMap[cat].qty += Number(r.quantityWasted || 0);
      categoryMap[cat].loss += Number(r.monetaryLoss || 0);
    }

    tbody.innerHTML = Object.keys(categoryMap).map(cat => {
      const item = categoryMap[cat];
      const sharePct = totalWasteKg > 0 ? ((item.qty / totalWasteKg) * 100).toFixed(1) : '0.0';
      return `
        <tr>
          <td><strong>${cat}</strong></td>
          <td>${item.qty.toFixed(1)} kg</td>
          <td><strong style="color:var(--risk-high-text);">${item.loss.toLocaleString()} MMK</strong></td>
          <td>${sharePct}%</td>
          <td>${item.reason}</td>
        </tr>
      `;
    }).join('');
  }
};

document.addEventListener('DOMContentLoaded', () => {
  Reports.init();
});

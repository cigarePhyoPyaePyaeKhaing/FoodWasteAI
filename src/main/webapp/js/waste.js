/**
 * FoodWaste AI - Waste Records Controller
 * Connected with /api/waste and /api/inventory REST endpoints
 */
const Waste = {
  records: [],
  foodItems: [],
  loading: false,

  async init() {
    await Promise.all([
      this.fetchFoodItems(),
      this.fetchWaste()
    ]);
  },

  async fetchFoodItems() {
    try {
      const res = await API.get('/api/inventory');
      this.foodItems = (res && res.data) ? res.data : [];
      this.populateFoodSelect();
    } catch (err) {
      console.warn('Error fetching food items for waste dropdown:', err);
    }
  },

  populateFoodSelect() {
    const select = document.getElementById('waste-food-id');
    if (!select) return;

    if (this.foodItems.length === 0) {
      select.innerHTML = `<option value="">No food items in inventory</option>`;
      return;
    }

    select.innerHTML = this.foodItems.map(f => {
      const price = Number(f.pricePerUnit || 0).toLocaleString();
      const qty = Number(f.quantity || 0).toFixed(1);
      return `<option value="${f.id}" data-price="${f.pricePerUnit}" data-unit="${f.unit || 'kg'}">${f.name} (Stock: ${qty} ${f.unit || 'kg'} @ ${price} MMK)</option>`;
    }).join('');
  },

  async fetchWaste() {
    this.loading = true;
    this.renderLoading();
    try {
      const res = await API.get('/api/waste');
      this.records = (res && res.data) ? res.data : [];
    } catch (err) {
      console.warn('API fetch waste error:', err);
      API.showToast('Using local waste view', 'info');
    } finally {
      this.loading = false;
      this.render();
      this.updateKpis();
    }
  },

  renderLoading() {
    const tbody = document.getElementById('waste-tbody');
    if (!tbody) return;
    tbody.innerHTML = `
      <tr>
        <td colspan="7" style="text-align:center; padding:3rem; color:var(--text-muted);">
          <div style="font-size:1.5rem; animation: spin 1s linear infinite; display:inline-block;">🍃</div>
          <div style="margin-top:0.5rem; font-weight:600;">Loading waste incident logs...</div>
        </td>
      </tr>
    `;
  },

  render() {
    const tbody = document.getElementById('waste-tbody');
    if (!tbody) return;

    if (this.loading) {
      this.renderLoading();
      return;
    }

    if (this.records.length === 0) {
      tbody.innerHTML = `
        <tr>
          <td colspan="7" style="text-align:center; padding:3rem; color:var(--text-muted);">
            <div style="font-size:2rem; margin-bottom:0.5rem;">🎉</div>
            <div style="font-weight:700; color:var(--text-main); font-size:1.05rem;">No Food Waste Recorded</div>
            <div style="font-size:0.85rem; margin-top:0.25rem;">Great kitchen efficiency! Click "+ Log Food Waste" if an incident occurs.</div>
          </td>
        </tr>
      `;
      return;
    }

    tbody.innerHTML = this.records.map(r => {
      let badgeClass = 'badge-urgent';
      if (r.reason === 'UNSOLD') badgeClass = 'badge-important';
      else if (r.reason === 'PREPARATION_WASTE') badgeClass = 'badge-optimization';

      const qtyFmt = Number(r.quantityWasted || 0).toFixed(2) + ' kg';
      const lossFmt = Number(r.monetaryLoss || 0).toLocaleString() + ' MMK';
      const dateFmt = r.wasteDate ? r.wasteDate.replace('T', ' ').substring(0, 16) : 'Recently';

      return `
        <tr>
          <td>${dateFmt}</td>
          <td><strong>${r.foodItemName || ('Food Item #' + r.foodItemId)}</strong></td>
          <td><strong style="color:var(--risk-high-text); font-size:1rem;">${qtyFmt}</strong></td>
          <td><span class="badge-bubble ${badgeClass}">${r.reason}</span></td>
          <td><strong style="color:var(--risk-high-text);">${lossFmt}</strong></td>
          <td style="color:var(--text-muted); font-size:0.85rem;">${r.notes || '-'}</td>
          <td style="text-align:right;">
            <button class="btn-bubble btn-glass-subtle btn-sm-bubble" style="color:var(--risk-high-text);" onclick="Waste.deleteWaste(${r.id})">🗑️</button>
          </td>
        </tr>
      `;
    }).join('');
  },

  updateKpis() {
    const totalLoss = this.records.reduce((sum, r) => sum + Number(r.monetaryLoss || 0), 0);
    const totalWasteKg = this.records.reduce((sum, r) => sum + Number(r.quantityWasted || 0), 0);
    const co2Kg = totalWasteKg * 2.5;

    const lossEl = document.getElementById('kpi-waste-loss');
    if (lossEl) lossEl.textContent = totalLoss.toLocaleString() + ' MMK';

    const wasteEl = document.getElementById('kpi-waste-kg');
    if (wasteEl) wasteEl.textContent = totalWasteKg.toFixed(1) + ' kg';

    const co2El = document.getElementById('kpi-waste-co2');
    if (co2El) co2El.textContent = co2Kg.toFixed(1) + ' kg CO₂e';
  },

  openModal() {
    this.fetchFoodItems();
    const form = document.getElementById('waste-form');
    if (form) form.reset();
    const modal = document.getElementById('log-waste-modal');
    if (modal) modal.classList.add('active');
  },

  closeModal() {
    const modal = document.getElementById('log-waste-modal');
    if (modal) modal.classList.remove('active');
  },

  async saveWaste(e) {
    e.preventDefault();
    const foodSelect = document.getElementById('waste-food-id');
    const foodItemId = parseInt(foodSelect.value);
    const quantityWasted = parseFloat(document.getElementById('waste-qty').value);
    const reason = document.getElementById('waste-reason').value;
    const notes = document.getElementById('waste-notes').value.trim() || 'Kitchen log entry';

    if (!foodItemId || isNaN(quantityWasted) || quantityWasted <= 0) {
      API.showToast('Please select a food item and enter a valid quantity wasted', 'warning');
      return;
    }

    const payload = {
      foodItemId,
      quantityWasted,
      reason,
      notes
    };

    try {
      await API.post('/api/waste', payload);
      API.showToast(`Logged ${quantityWasted} kg waste and adjusted stock!`, 'warning');
      this.closeModal();
      await this.fetchWaste();
    } catch (err) {
      console.error('Error logging waste:', err);
      API.showToast('Failed to log waste: ' + err.message, 'error');
    }
  },

  async deleteWaste(id) {
    if (!confirm('Are you sure you want to delete this waste record?')) return;

    try {
      await API.delete(`/api/waste/${id}`);
      API.showToast('Waste record deleted', 'info');
      await this.fetchWaste();
    } catch (err) {
      console.error('Error deleting waste:', err);
      API.showToast('Failed to delete waste record: ' + err.message, 'error');
    }
  }
};

document.addEventListener('DOMContentLoaded', () => {
  Waste.init();
});

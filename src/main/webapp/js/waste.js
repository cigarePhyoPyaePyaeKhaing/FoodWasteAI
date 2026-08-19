const Waste = {
  records: [],
  foodItems: [],
  loading: false,

  async init() {
    await Promise.all([
      this.fetchFoodItems(),
      this.fetchWaste()
    ]);

    window.addEventListener('languageChanged', () => {
      this.render();
      this.updateKpis();
      this.populateFoodSelect();
    });
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

    const isMm = typeof I18n !== 'undefined' && I18n.getLanguage() === 'mm';

    if (this.foodItems.length === 0) {
      select.innerHTML = `<option value="">${isMm ? 'ကုန်ပစ္စည်းစာရင်း မရှိသေးပါ' : 'No food items in inventory'}</option>`;
      return;
    }

    select.innerHTML = this.foodItems.map(f => {
      const price = Number(f.pricePerUnit || 0).toLocaleString();
      const qty = Number(f.quantity || 0).toFixed(1);
      const stockLabel = isMm ? 'လက်ကျန်' : 'Stock';
      return `<option value="${f.id}" data-price="${f.pricePerUnit}" data-unit="${f.unit || 'kg'}">${f.name} (${stockLabel}: ${qty} ${f.unit || 'kg'} @ ${price} MMK)</option>`;
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
    const isMm = typeof I18n !== 'undefined' && I18n.getLanguage() === 'mm';
    tbody.innerHTML = `
      <tr>
        <td colspan="7" style="text-align:center; padding:3rem; color:var(--text-muted);">
          <div style="font-size:1.5rem; animation: spin 1s linear infinite; display:inline-block;">🍃</div>
          <div style="margin-top:0.5rem; font-weight:600;">${isMm ? 'အလေအလွင့် မှတ်တမ်းများကို ရယူနေပါသည်...' : 'Loading waste incident logs...'}</div>
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

    const isMm = typeof I18n !== 'undefined' && I18n.getLanguage() === 'mm';

    if (this.records.length === 0) {
      tbody.innerHTML = `
        <tr>
          <td colspan="7" style="text-align:center; padding:3rem; color:var(--text-muted);">
            <div style="font-size:2rem; margin-bottom:0.5rem;">🎉</div>
            <div style="font-weight:700; color:var(--text-main); font-size:1.05rem;">${typeof I18n !== 'undefined' ? I18n.t('waste.empty.title') : 'No Food Waste Recorded'}</div>
            <div style="font-size:0.85rem; margin-top:0.25rem;">${typeof I18n !== 'undefined' ? I18n.t('waste.empty.desc') : 'Great kitchen efficiency! Click "+ Log Food Waste" if an incident occurs.'}</div>
          </td>
        </tr>
      `;
      return;
    }

    tbody.innerHTML = this.records.map(r => {
      let badgeClass = 'badge-urgent';
      if (r.reason === 'UNSOLD') badgeClass = 'badge-important';
      else if (r.reason === 'PREPARATION_WASTE') badgeClass = 'badge-optimization';

      const reasonText = typeof I18n !== 'undefined' ? I18n.translateWasteReason(r.reason) : r.reason;
      const qtyFmt = Number(r.quantityWasted || 0).toFixed(2) + ' kg';
      const lossFmt = Number(r.monetaryLoss || 0).toLocaleString() + ' MMK';
      const dateFmt = r.wasteDate ? r.wasteDate.replace('T', ' ').substring(0, 16) : (isMm ? 'မကြာသေးမီက' : 'Recently');

      return `
        <tr>
          <td>${dateFmt}</td>
          <td><strong>${r.foodItemName || ('Food Item #' + r.foodItemId)}</strong></td>
          <td><strong style="color:var(--risk-high-text); font-size:1rem;">${qtyFmt}</strong></td>
          <td><span class="badge-bubble ${badgeClass}">${reasonText}</span></td>
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
    const isMm = typeof I18n !== 'undefined' && I18n.getLanguage() === 'mm';
    const foodSelect = document.getElementById('waste-food-id');
    const foodItemId = parseInt(foodSelect.value);
    const quantityWasted = parseFloat(document.getElementById('waste-qty').value);
    const reason = document.getElementById('waste-reason').value;
    const notes = document.getElementById('waste-notes').value.trim() || (isMm ? 'မီးဖိုချောင် အလေအလွင့် စာရင်း' : 'Kitchen log entry');

    if (!foodItemId || isNaN(quantityWasted) || quantityWasted <= 0) {
      API.showToast(isMm ? 'အစားအစာနှင့် အလေအလွင့် ပမာဏကို မှန်ကန်စွာ ထည့်သွင်းပါ' : 'Please select a food item and enter a valid quantity wasted', 'warning');
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
      API.showToast(isMm ? `${quantityWasted} kg အလေအလွင့် စာရင်းသွင်းပြီး ကုန်ပစ္စည်းလက်ကျန်ကို ချိန်ညှိပြီးပါပြီ!` : `Logged ${quantityWasted} kg waste and adjusted stock!`, 'warning');
      this.closeModal();
      await this.fetchWaste();
    } catch (err) {
      console.error('Error logging waste:', err);
      API.showToast(isMm ? ('အလေအလွင့် စာရင်းသွင်းရန် မအောင်မြင်ပါ: ' + err.message) : ('Failed to log waste: ' + err.message), 'error');
    }
  },

  async deleteWaste(id) {
    const isMm = typeof I18n !== 'undefined' && I18n.getLanguage() === 'mm';
    if (!confirm(isMm ? 'ဤအလေအလွင့် မှတ်တမ်းကို ဖျက်ရန် သေချာပါသလား?' : 'Are you sure you want to delete this waste record?')) return;

    try {
      await API.delete(`/api/waste/${id}`);
      API.showToast(isMm ? 'အလေအလွင့် မှတ်တမ်း ဖျက်ပြီးပါပြီ' : 'Waste record deleted', 'info');
      await this.fetchWaste();
    } catch (err) {
      console.error('Error deleting waste:', err);
      API.showToast(isMm ? ('ဖျက်ရန် မအောင်မြင်ပါ: ' + err.message) : ('Failed to delete waste record: ' + err.message), 'error');
    }
  }
};

document.addEventListener('DOMContentLoaded', () => {
  Waste.init();
});

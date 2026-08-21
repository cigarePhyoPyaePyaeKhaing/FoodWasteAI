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
      this.onFoodItemChanged();
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
      const indicator = document.getElementById('waste-stock-indicator');
      if (indicator) indicator.style.display = 'none';
      return;
    }

    select.innerHTML = this.foodItems.map(f => {
      const price = Number(f.pricePerUnit || 0).toLocaleString();
      const qty = Number(f.quantity || 0).toFixed(2);
      const unit = f.unit || 'kg';
      const stockLabel = isMm ? 'လက်ကျန်' : 'Stock';
      return `<option value="${f.id}" data-price="${f.pricePerUnit || 0}" data-stock="${f.quantity || 0}" data-unit="${unit}" data-name="${f.name}">${f.name} (${stockLabel}: ${qty} ${unit} @ ${price} MMK)</option>`;
    }).join('');

    this.onFoodItemChanged();
  },

  onFoodItemChanged() {
    const select = document.getElementById('waste-food-id');
    const indicator = document.getElementById('waste-stock-indicator');
    const stockText = document.getElementById('waste-available-stock-text');
    const unitAddon = document.getElementById('waste-unit-addon');

    if (!select || select.selectedIndex < 0) {
      if (indicator) indicator.style.display = 'none';
      return;
    }

    const selectedOpt = select.options[select.selectedIndex];
    if (!selectedOpt || !selectedOpt.value) {
      if (indicator) indicator.style.display = 'none';
      return;
    }

    const stock = parseFloat(selectedOpt.getAttribute('data-stock') || '0');
    const unit = selectedOpt.getAttribute('data-unit') || 'kg';

    if (indicator && stockText) {
      indicator.style.display = 'block';
      stockText.textContent = `${stock.toFixed(2)} ${unit}`;
    }

    if (unitAddon) {
      unitAddon.textContent = unit;
    }

    this.onQuantityInput();
  },

  onQuantityInput() {
    const select = document.getElementById('waste-food-id');
    const qtyInput = document.getElementById('waste-qty');
    const errorEl = document.getElementById('waste-qty-error');
    const submitBtn = document.getElementById('waste-submit-btn');

    if (!qtyInput || !select || select.selectedIndex < 0) return;

    const selectedOpt = select.options[select.selectedIndex];
    if (!selectedOpt || !selectedOpt.value) return;

    const availableStock = parseFloat(selectedOpt.getAttribute('data-stock') || '0');
    const unit = selectedOpt.getAttribute('data-unit') || 'kg';
    const requestedQty = parseFloat(qtyInput.value);
    const isMm = typeof I18n !== 'undefined' && I18n.getLanguage() === 'mm';

    if (!isNaN(requestedQty) && requestedQty > availableStock) {
      if (errorEl) {
        errorEl.style.display = 'block';
        errorEl.textContent = isMm
          ? `လက်ကျန်မလုံလောက်ပါ: ${requestedQty} ${unit} လျှော့ချရန် တောင်းဆိုထားသော်လည်း လက်ကျန် ${availableStock.toFixed(2)} ${unit} သာ ရှိပါသည်`
          : `Insufficient stock: ${requestedQty} ${unit} requested, but only ${availableStock.toFixed(2)} ${unit} available.`;
      }
      if (submitBtn) submitBtn.disabled = true;
    } else {
      if (errorEl) {
        errorEl.style.display = 'none';
        errorEl.textContent = '';
      }
      if (submitBtn) submitBtn.disabled = false;
    }
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
      const unit = r.unit || 'kg';
      const qtyFmt = Number(r.quantityWasted || 0).toFixed(2) + ' ' + unit;
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
    const totalWasteUnits = this.records.reduce((sum, r) => sum + Number(r.quantityWasted || 0), 0);
    const co2Kg = totalWasteUnits * 2.5;

    const lossEl = document.getElementById('kpi-waste-loss');
    if (lossEl) lossEl.textContent = totalLoss.toLocaleString() + ' MMK';

    const wasteEl = document.getElementById('kpi-waste-kg');
    if (wasteEl) {
      if (typeof I18n !== 'undefined' && typeof I18n.formatUnitAggregate === 'function') {
        wasteEl.textContent = I18n.formatUnitAggregate(this.records, r => r.quantityWasted, r => r.unit, '');
      } else {
        wasteEl.textContent = totalWasteUnits.toFixed(1);
      }
    }

    const co2El = document.getElementById('kpi-waste-co2');
    if (co2El) co2El.textContent = co2Kg.toFixed(1) + ' kg CO₂e';
  },

  openModal() {
    this.fetchFoodItems();
    const form = document.getElementById('waste-form');
    if (form) form.reset();
    const modal = document.getElementById('log-waste-modal');
    if (modal) modal.classList.add('active');
    setTimeout(() => {
      this.onFoodItemChanged();
    }, 100);
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

    const selectedOpt = foodSelect.options[foodSelect.selectedIndex];
    const availableStock = selectedOpt ? parseFloat(selectedOpt.getAttribute('data-stock') || '0') : 0;
    const unit = selectedOpt ? (selectedOpt.getAttribute('data-unit') || 'kg') : 'kg';

    if (quantityWasted > availableStock) {
      API.showToast(isMm
        ? `လက်ကျန်မလုံလောက်ပါ: လက်ကျန် ${availableStock.toFixed(2)} ${unit} သာ ရှိပါသည်`
        : `Insufficient stock: only ${availableStock.toFixed(2)} ${unit} available in inventory.`, 'error');
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
      API.showToast(isMm ? `${quantityWasted.toFixed(2)} ${unit} အလေအလွင့် စာရင်းသွင်းပြီး ကုန်ပစ္စည်းလက်ကျန်ကို ချိန်ညှိပြီးပါပြီ!` : `Logged ${quantityWasted.toFixed(2)} ${unit} waste and adjusted stock!`, 'warning');
      this.closeModal();
      await Promise.all([
        this.fetchWaste(),
        this.fetchFoodItems()
      ]);
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
      await Promise.all([
        this.fetchWaste(),
        this.fetchFoodItems()
      ]);
    } catch (err) {
      console.error('Error deleting waste:', err);
      API.showToast(isMm ? ('ဖျက်ရန် မအောင်မြင်ပါ: ' + err.message) : ('Failed to delete waste record: ' + err.message), 'error');
    }
  }
};

document.addEventListener('DOMContentLoaded', () => {
  Waste.init();
});

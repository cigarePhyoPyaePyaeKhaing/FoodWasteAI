const Sales = {
  sales: [],
  foodItems: [],
  loading: false,

  async init() {
    await Promise.all([
      this.fetchFoodItems(),
      this.fetchSales()
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
      console.warn('Error fetching food items for sales dropdown:', err);
    }
  },

  populateFoodSelect() {
    const select = document.getElementById('sale-food-id');
    if (!select) return;

    const isMm = typeof I18n !== 'undefined' && I18n.getLanguage() === 'mm';

    if (this.foodItems.length === 0) {
      select.innerHTML = `<option value="">${isMm ? 'ကုန်ပစ္စည်းစာရင်း မရှိသေးပါ' : 'No food items in inventory'}</option>`;
      this.onFoodItemChanged();
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
    const select = document.getElementById('sale-food-id');
    const indicator = document.getElementById('sale-stock-indicator');
    const stockText = document.getElementById('sale-available-stock-text');
    const unitAddon = document.getElementById('sale-unit-addon');
    if (!select) return;

    const selectedOption = select.options[select.selectedIndex];
    if (!selectedOption || !selectedOption.value) {
      if (indicator) indicator.style.display = 'none';
      if (unitAddon) unitAddon.textContent = '';
      return;
    }

    const stock = parseFloat(selectedOption.getAttribute('data-stock')) || 0;
    const unit = selectedOption.getAttribute('data-unit') || 'kg';

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
    const select = document.getElementById('sale-food-id');
    const qtyInput = document.getElementById('sale-qty');
    const errorEl = document.getElementById('sale-qty-error');
    const submitBtn = document.getElementById('sale-submit-btn');
    if (!select || !qtyInput) return;

    const isMm = typeof I18n !== 'undefined' && I18n.getLanguage() === 'mm';
    const selectedOption = select.options[select.selectedIndex];
    if (!selectedOption || !selectedOption.value) {
      if (errorEl) errorEl.style.display = 'none';
      return;
    }

    const availableStock = parseFloat(selectedOption.getAttribute('data-stock')) || 0;
    const unit = selectedOption.getAttribute('data-unit') || 'kg';
    const valStr = qtyInput.value.trim();
    const qty = parseFloat(valStr);

    if (valStr === '' || isNaN(qty)) {
      if (errorEl) errorEl.style.display = 'none';
      qtyInput.style.borderColor = '';
      if (submitBtn) submitBtn.disabled = false;
      return;
    }

    if (qty <= 0) {
      if (errorEl) {
        errorEl.textContent = isMm ? 'ပမာဏသည် သုညထက် ကြီးရပါမည်' : 'Quantity must be greater than 0';
        errorEl.style.display = 'block';
      }
      qtyInput.style.borderColor = '#ef4444';
      if (submitBtn) submitBtn.disabled = true;
      return;
    }

    if (qty > availableStock) {
      if (errorEl) {
        errorEl.textContent = isMm
          ? `လက်ကျန် ${availableStock.toFixed(2)} ${unit} သာ ရရှိနိုင်ပါသည်`
          : `Only ${availableStock.toFixed(2)} ${unit} is currently available.`;
        errorEl.style.display = 'block';
      }
      qtyInput.style.borderColor = '#ef4444';
      if (submitBtn) submitBtn.disabled = true;
      return;
    }

    // Valid quantity
    if (errorEl) errorEl.style.display = 'none';
    qtyInput.style.borderColor = '';
    if (submitBtn) submitBtn.disabled = false;
  },

  async fetchSales() {
    this.loading = true;
    this.renderLoading();
    try {
      const res = await API.get('/api/sales');
      this.sales = (res && res.data) ? res.data : [];
    } catch (err) {
      console.warn('API fetch sales error:', err);
      API.showToast('Using local sales view', 'info');
    } finally {
      this.loading = false;
      this.render();
      this.updateKpis();
    }
  },

  renderLoading() {
    const tbody = document.getElementById('sales-tbody');
    if (!tbody) return;
    const isMm = typeof I18n !== 'undefined' && I18n.getLanguage() === 'mm';
    tbody.innerHTML = `
      <tr>
        <td colspan="7" style="text-align:center; padding:3rem; color:var(--text-muted);">
          <div style="font-size:1.5rem; animation: spin 1s linear infinite; display:inline-block;">🍃</div>
          <div style="margin-top:0.5rem; font-weight:600;">${isMm ? 'ရောင်းချမှု မှတ်တမ်းများကို ရယူနေပါသည်...' : 'Loading customer sales records...'}</div>
        </td>
      </tr>
    `;
  },

  render() {
    const tbody = document.getElementById('sales-tbody');
    if (!tbody) return;

    if (this.loading) {
      this.renderLoading();
      return;
    }

    const isMm = typeof I18n !== 'undefined' && I18n.getLanguage() === 'mm';

    if (this.sales.length === 0) {
      tbody.innerHTML = `
        <tr>
          <td colspan="7" style="text-align:center; padding:3rem; color:var(--text-muted);">
            <div style="font-size:2rem; margin-bottom:0.5rem;">💰</div>
            <div style="font-weight:700; color:var(--text-main); font-size:1.05rem;">${typeof I18n !== 'undefined' ? I18n.t('sales.empty.title') : 'No Sales Recorded Today'}</div>
            <div style="font-size:0.85rem; margin-top:0.25rem;">${typeof I18n !== 'undefined' ? I18n.t('sales.empty.desc') : 'Click "+ Record Sale" to log customer dish purchases.'}</div>
          </td>
        </tr>
      `;
      return;
    }

    tbody.innerHTML = this.sales.map(s => {
      const unitStr = s.unit ? s.unit : 'kg';
      const qtyFmt = Number(s.quantitySold || 0).toFixed(2) + ' ' + unitStr;
      const priceFmt = Number(s.unitPrice || 0).toLocaleString() + ' MMK';
      const totalFmt = Number(s.totalAmount || 0).toLocaleString() + ' MMK';
      const diners = s.customerCount || 1;
      const dinersText = `${diners} ${isMm ? 'ဦး' : 'Diners'}`;
      const dateFmt = s.saleDate ? s.saleDate.replace('T', ' ').substring(0, 16) : (isMm ? 'ယနေ့' : 'Today');

      return `
        <tr>
          <td>${dateFmt}</td>
          <td><strong>${s.foodItemName || ('Food Item #' + s.foodItemId)}</strong></td>
          <td><strong style="color:var(--text-main);">${qtyFmt}</strong></td>
          <td>${priceFmt}</td>
          <td><strong style="color:var(--accent-yellow-dark);">${totalFmt}</strong></td>
          <td><span class="badge-bubble badge-optimization">${dinersText}</span></td>
          <td style="text-align:right;">
            <button class="btn-bubble btn-glass-subtle btn-sm-bubble" style="color:var(--risk-high-text);" onclick="Sales.deleteSale(${s.id})">🗑️</button>
          </td>
        </tr>
      `;
    }).join('');
  },

  updateKpis() {
    const isMm = typeof I18n !== 'undefined' && I18n.getLanguage() === 'mm';
    const totalRev = this.sales.reduce((sum, s) => sum + Number(s.totalAmount || 0), 0);
    const totalVol = this.sales.reduce((sum, s) => sum + Number(s.quantitySold || 0), 0);
    const totalDiners = this.sales.reduce((sum, s) => sum + Number(s.customerCount || 0), 0);

    const revEl = document.getElementById('kpi-sales-revenue');
    if (revEl) revEl.textContent = totalRev.toLocaleString() + ' MMK';

    const volEl = document.getElementById('kpi-sales-volume');
    if (volEl) volEl.textContent = totalVol.toFixed(1) + ' kg';

    const dinersEl = document.getElementById('kpi-sales-diners');
    if (dinersEl) dinersEl.textContent = totalDiners + (isMm ? ' ဦး' : ' Diners');
  },

  openModal() {
    this.fetchFoodItems();
    const form = document.getElementById('sale-form');
    if (form) form.reset();
    const errorEl = document.getElementById('sale-qty-error');
    if (errorEl) errorEl.style.display = 'none';
    const qtyInput = document.getElementById('sale-qty');
    if (qtyInput) qtyInput.style.borderColor = '';
    const submitBtn = document.getElementById('sale-submit-btn');
    if (submitBtn) submitBtn.disabled = false;

    const modal = document.getElementById('record-sale-modal');
    if (modal) modal.classList.add('active');
  },

  closeModal() {
    const modal = document.getElementById('record-sale-modal');
    if (modal) modal.classList.remove('active');
  },

  async saveSale(e) {
    e.preventDefault();
    const isMm = typeof I18n !== 'undefined' && I18n.getLanguage() === 'mm';
    const foodSelect = document.getElementById('sale-food-id');
    const foodItemId = parseInt(foodSelect.value);
    const quantitySold = parseFloat(document.getElementById('sale-qty').value);
    const customerCount = parseInt(document.getElementById('sale-customers').value) || 1;

    if (!foodItemId || isNaN(quantitySold) || quantitySold <= 0) {
      API.showToast(isMm ? 'အစားအစာနှင့် ပမာဏကို မှန်ကန်စွာ ရွေးချယ်ပါ' : 'Please select a food item and enter a valid quantity', 'warning');
      return;
    }

    const selectedOption = foodSelect.options[foodSelect.selectedIndex];
    const availableStock = parseFloat(selectedOption.getAttribute('data-stock')) || 0;
    const unit = selectedOption.getAttribute('data-unit') || 'kg';
    const unitPrice = parseFloat(selectedOption.getAttribute('data-price')) || 5000;
    const foodName = selectedOption.getAttribute('data-name') || selectedOption.text.split('(')[0].trim();

    if (quantitySold > availableStock) {
      const msg = isMm
        ? `လက်ကျန် ${availableStock.toFixed(2)} ${unit} သာ ရရှိနိုင်ပါသည်`
        : `Insufficient stock. Only ${availableStock.toFixed(2)} ${unit} available.`;
      API.showToast(msg, 'error');
      return;
    }

    const payload = {
      foodItemId,
      quantitySold,
      unitPrice,
      customerCount
    };

    try {
      await API.post('/api/sales', payload);
      API.showToast(isMm ? 'ရောင်းချမှု မှတ်တမ်းတင်ပြီး လက်ကျန်စာရင်း လျှော့ချပြီးပါပြီ!' : `Recorded sale: ${quantitySold} ${unit} of ${foodName}!`, 'success');
      this.closeModal();
      await Promise.all([
        this.fetchSales(),
        this.fetchFoodItems()
      ]);
    } catch (err) {
      console.error('Error saving sale:', err);
      const errMsg = err && err.message ? err.message : 'Unknown error';
      API.showToast(isMm ? ('ရောင်းချမှု မှတ်တမ်းတင်ရန် မအောင်မြင်ပါ: ' + errMsg) : ('Failed to record sale: ' + errMsg), 'error');
    }
  },

  async deleteSale(id) {
    const isMm = typeof I18n !== 'undefined' && I18n.getLanguage() === 'mm';
    if (!confirm(isMm ? 'ဤရောင်းချမှု မှတ်တမ်းကို ဖျက်ရန် သေချာပါသလား?' : 'Are you sure you want to delete this sales record?')) return;

    try {
      await API.delete(`/api/sales/${id}`);
      API.showToast(isMm ? 'ရောင်းချမှု မှတ်တမ်း ဖျက်ပြီးပါပြီ' : 'Sale record deleted', 'info');
      await Promise.all([
        this.fetchSales(),
        this.fetchFoodItems()
      ]);
    } catch (err) {
      console.error('Error deleting sale:', err);
      API.showToast(isMm ? ('ဖျက်ရန် မအောင်မြင်ပါ: ' + err.message) : ('Failed to delete sale: ' + err.message), 'error');
    }
  }
};

document.addEventListener('DOMContentLoaded', () => {
  Sales.init();
});

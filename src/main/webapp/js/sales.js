const Sales = {
  sales: [],
  foodItems: [],
  loading: false,
  submitting: false,
  lastSubmitTime: 0,
  currentSubmissionToken: null,

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

    const form = document.getElementById('sale-form');
    if (form) {
      form.addEventListener('keydown', (e) => {
        if (e.key === 'Enter' && this.submitting) {
          e.preventDefault();
          e.stopPropagation();
        }
      });
    }

    const submitBtn = document.getElementById('sale-submit-btn');
    if (submitBtn) {
      submitBtn.addEventListener('click', (e) => {
        if (this.submitting) {
          e.preventDefault();
          e.stopImmediatePropagation();
        }
      });
    }
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

    if (!this.foodItems || this.foodItems.length === 0) {
      select.innerHTML = `<option value="">${isMm ? 'ကုန်ပစ္စည်းစာရင်း မရှိသေးပါ' : 'No food items in inventory'}</option>`;
      this.onFoodItemChanged();
      return;
    }

    // Group items by normalized product name (case-insensitive, trimmed)
    const groups = new Map();
    const todayStr = new Date().toISOString().slice(0, 10);

    const isBatchSellable = (item) => {
      const remQty = Number(item.remainingQuantity !== undefined ? item.remainingQuantity : (item.quantity || 0));
      if (remQty <= 0) return false;
      if ((item.status || '').toUpperCase() === 'EXPIRED') return false;
      if (item.expiryDate && String(item.expiryDate).slice(0, 10) < todayStr) return false;
      return true;
    };

    this.foodItems.forEach(item => {
      const rawName = (item.name || '').trim();
      if (!rawName) return;
      const normKey = rawName.toLowerCase();
      if (!groups.has(normKey)) {
        groups.set(normKey, []);
      }
      groups.get(normKey).push(item);
    });

    if (groups.size === 0) {
      select.innerHTML = `<option value="">${isMm ? 'ကုန်ပစ္စည်းစာရင်း မရှိသေးပါ' : 'No food items in inventory'}</option>`;
      this.onFoodItemChanged();
      return;
    }

    // For each normalized product name, select the optimal batch according to existing stock/FIFO rules:
    // 1. Sellable batches (non-expired, stock > 0) prioritized over exhausted/expired
    // 2. Earliest expiring batch first (FEFO rotation rule)
    // 3. Earliest batch ID first (FIFO)
    const uniqueProducts = Array.from(groups.values()).map(batchList => {
      batchList.sort((a, b) => {
        const sellableA = isBatchSellable(a);
        const sellableB = isBatchSellable(b);
        if (sellableA !== sellableB) return sellableA ? -1 : 1;

        const expA = a.expiryDate ? String(a.expiryDate).slice(0, 10) : '9999-12-31';
        const expB = b.expiryDate ? String(b.expiryDate).slice(0, 10) : '9999-12-31';
        const expCmp = expA.localeCompare(expB);
        if (expCmp !== 0) return expCmp;

        return (Number(a.id) || 0) - (Number(b.id) || 0);
      });
      return batchList[0];
    });

    select.innerHTML = uniqueProducts.map(f => {
      const price = Number(f.pricePerUnit || 0).toLocaleString();
      const remQty = Number(f.remainingQuantity !== undefined ? f.remainingQuantity : (f.quantity || 0));
      const qty = remQty.toFixed(2);
      const unit = f.unit || 'kg';
      const stockLabel = isMm ? 'လက်ကျန်' : 'Stock';
      const cleanName = (f.name || '').trim();
      return `<option value="${f.id}" data-price="${f.pricePerUnit || 0}" data-stock="${remQty}" data-unit="${unit}" data-name="${cleanName}">${cleanName} (${stockLabel}: ${qty} ${unit} @ ${price} MMK)</option>`;
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
      if (submitBtn && !this.submitting) submitBtn.disabled = false;
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
    if (submitBtn && !this.submitting) submitBtn.disabled = false;
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
    const totalDiners = this.sales.reduce((sum, s) => sum + Number(s.customerCount || 0), 0);

    const revEl = document.getElementById('kpi-sales-revenue');
    if (revEl) revEl.textContent = totalRev.toLocaleString() + ' MMK';

    const volEl = document.getElementById('kpi-sales-volume');
    if (volEl) {
      if (typeof I18n !== 'undefined' && typeof I18n.formatUnitAggregate === 'function') {
        volEl.textContent = I18n.formatUnitAggregate(this.sales, s => s.quantitySold, s => s.unit, '');
      } else {
        const totalVol = this.sales.reduce((sum, s) => sum + Number(s.quantitySold || 0), 0);
        volEl.textContent = totalVol.toFixed(1);
      }
    }

    const dinersEl = document.getElementById('kpi-sales-diners');
    if (dinersEl) dinersEl.textContent = totalDiners + (isMm ? ' ဦး' : ' Diners');
  },

  openModal() {
    this.currentSubmissionToken = 'sale_' + Date.now() + '_' + Math.random().toString(36).substring(2, 9);
    this.submitting = false;
    this.fetchFoodItems();
    const form = document.getElementById('sale-form');
    if (form) {
      form.reset();
      form.style.pointerEvents = '';
    }
    const errorEl = document.getElementById('sale-qty-error');
    if (errorEl) errorEl.style.display = 'none';
    const qtyInput = document.getElementById('sale-qty');
    if (qtyInput) qtyInput.style.borderColor = '';
    const submitBtn = document.getElementById('sale-submit-btn');
    if (submitBtn) {
      submitBtn.disabled = false;
      submitBtn.style.pointerEvents = '';
      submitBtn.style.opacity = '';
      submitBtn.style.cursor = '';
      const isMm = typeof I18n !== 'undefined' && I18n.getLanguage() === 'mm';
      submitBtn.textContent = isMm ? 'အရောင်းမှတ်တမ်းတင်ပြီး လက်ကျန်စာရင်း လျှော့ချမည်' : 'Record Sale & Update Stock';
    }

    const modal = document.getElementById('record-sale-modal');
    if (modal) modal.classList.add('active');
  },

  closeModal() {
    this.submitting = false;
    const modal = document.getElementById('record-sale-modal');
    if (modal) modal.classList.remove('active');
    const form = document.getElementById('sale-form');
    if (form) form.style.pointerEvents = '';
    const submitBtn = document.getElementById('sale-submit-btn');
    if (submitBtn) {
      submitBtn.disabled = false;
      submitBtn.style.pointerEvents = '';
      submitBtn.style.opacity = '';
      submitBtn.style.cursor = '';
    }
  },

  async saveSale(e) {
    if (e) {
      e.preventDefault();
      e.stopPropagation();
    }

    // 1. Guard against duplicate / rapid concurrent submissions
    if (this.submitting) {
      console.warn('[Sales] Submission already in progress. Ignoring duplicate trigger.');
      return;
    }

    const now = Date.now();
    if (now - this.lastSubmitTime < 1500) {
      console.warn('[Sales] Rapid submission throttled (<1500ms).');
      return;
    }

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

    // 2. Lock submission state immediately and apply visual loading feedback
    this.submitting = true;
    this.lastSubmitTime = now;

    const submitBtn = document.getElementById('sale-submit-btn');
    const form = document.getElementById('sale-form');
    let origBtnText = '';

    if (submitBtn) {
      origBtnText = submitBtn.textContent;
      submitBtn.disabled = true;
      submitBtn.style.pointerEvents = 'none';
      submitBtn.style.opacity = '0.65';
      submitBtn.style.cursor = 'not-allowed';
      submitBtn.innerHTML = `
        <span style="display:inline-block; animation: spin 1s linear infinite; margin-right:0.4rem;">⏳</span>
        ${isMm ? 'အရောင်းမှတ်တမ်းတင်နေပါသည်...' : 'Recording Sale...'}
      `;
    }

    if (form) {
      form.style.pointerEvents = 'none';
    }

    const payload = {
      foodItemId,
      quantitySold,
      unitPrice,
      customerCount,
      clientRequestId: this.currentSubmissionToken || ('sale_' + Date.now() + '_' + Math.random().toString(36).substring(2, 9))
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
    } finally {
      this.submitting = false;
      if (submitBtn) {
        submitBtn.disabled = false;
        submitBtn.style.pointerEvents = '';
        submitBtn.style.opacity = '';
        submitBtn.style.cursor = '';
        submitBtn.textContent = origBtnText || (isMm ? 'အရောင်းမှတ်တမ်းတင်ပြီး လက်ကျန်စာရင်း လျှော့ချမည်' : 'Record Sale & Update Stock');
      }
      if (form) {
        form.style.pointerEvents = '';
      }
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

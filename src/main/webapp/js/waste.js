const Waste = {
  records: [],
  foodItems: [],
  loading: false,
  submitting: false,
  lastSubmitTime: 0,
  currentSubmissionToken: null,

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

    const form = document.getElementById('waste-form');
    if (form) {
      form.addEventListener('keydown', (e) => {
        if (e.key === 'Enter' && this.submitting) {
          e.preventDefault();
          e.stopPropagation();
        }
      });
    }

    const submitBtn = document.getElementById('waste-submit-btn');
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
      const remQty = Number(f.remainingQuantity !== undefined ? f.remainingQuantity : (f.quantity || 0));
      const qty = remQty.toFixed(2);
      const unit = f.unit || 'kg';
      const stockLabel = isMm ? 'လက်ကျန်' : 'Stock';
      return `<option value="${f.id}" data-price="${f.pricePerUnit || 0}" data-stock="${remQty}" data-unit="${unit}" data-name="${f.name}">${f.name} (${stockLabel}: ${qty} ${unit} @ ${price} MMK)</option>`;
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
      if (submitBtn && !this.submitting) submitBtn.disabled = false;
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

  escapeHtml(str) {
    if (!str) return '';
    return String(str)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#39;');
  },

  formatDateTime(isoStr) {
    if (!isoStr) return { date: '-', time: '' };
    const clean = isoStr.replace('T', ' ').trim();
    const parts = clean.split(' ');
    const date = parts[0] || '-';
    let time = parts[1] ? parts[1].substring(0, 5) : '';
    return { date, time };
  },

  getReasonBadgeInfo(reason) {
    const r = (reason || '').toUpperCase().trim();
    let badgeClass = 'badge-urgent';
    if (r === 'OVERPRODUCTION') badgeClass = 'badge-important';
    else if (r === 'UNSOLD') badgeClass = 'badge-optimization';
    else if (r === 'PREPARATION_WASTE' || r === 'PREPARATION') badgeClass = 'badge-glass-subtle';
    else if (r === 'EXPIRED') badgeClass = 'badge-urgent';
    else if (r === 'SPOILED') badgeClass = 'badge-urgent';
    else if (r === 'DAMAGED') badgeClass = 'badge-important';
    else badgeClass = 'badge-glass-subtle';

    const label = typeof I18n !== 'undefined' ? I18n.translateWasteReason(r) : r;
    return { badgeClass, label };
  },

  getSourceText(notes) {
    if (typeof I18n !== 'undefined' && typeof I18n.translateWasteSource === 'function') {
      return I18n.translateWasteSource(notes);
    }
    return notes || '-';
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
      const dt = this.formatDateTime(r.wasteDate);
      const foodName = r.foodItemName || ('Food Item #' + r.foodItemId);
      const unit = r.unit || 'kg';
      const qtyNum = Number(r.quantityWasted || 0);
      const qtyFmt = (unit.toLowerCase().includes('piece') && qtyNum % 1 === 0 ? Math.round(qtyNum) : qtyNum.toFixed(2)) + ' ' + unit;
      const lossFmt = Number(r.monetaryLoss || 0).toLocaleString() + ' MMK';
      const reasonInfo = this.getReasonBadgeInfo(r.reason);
      const sourceText = this.getSourceText(r.notes);

      return `
        <tr class="waste-row">
          <td class="col-date" data-label="${isMm ? 'ရက်စွဲနှင့် အချိန်' : 'Date & Time'}">
            <div class="waste-datetime-box">
              <span class="waste-date-text">${this.escapeHtml(dt.date)}</span>
              ${dt.time ? `<span class="waste-time-text">${this.escapeHtml(dt.time)}</span>` : ''}
            </div>
          </td>
          <td class="col-item" data-label="${isMm ? 'အစားအစာ / ကုန်ကြမ်း' : 'Food Item'}">
            <div class="waste-food-box">
              <strong class="waste-food-name">${this.escapeHtml(foodName)}</strong>
            </div>
          </td>
          <td class="col-qty" data-label="${isMm ? 'အလေအလွင့် ပမာဏ' : 'Quantity'}">
            <span class="waste-qty-badge">${this.escapeHtml(qtyFmt)}</span>
          </td>
          <td class="col-reason" data-label="${isMm ? 'အကြောင်းရင်း' : 'Reason'}">
            <span class="badge-bubble ${reasonInfo.badgeClass} waste-reason-pill" title="${this.escapeHtml(reasonInfo.label)}">
              ${this.escapeHtml(reasonInfo.label)}
            </span>
          </td>
          <td class="col-loss" data-label="${isMm ? 'ငွေကြေးဆုံးရှုံးမှု' : 'Financial Loss'}">
            <strong class="waste-loss-text">${this.escapeHtml(lossFmt)}</strong>
          </td>
          <td class="col-source" data-label="${isMm ? 'မှတ်တမ်းရင်းမြစ်' : 'Source'}">
            <span class="waste-source-text" title="${this.escapeHtml(sourceText)}">${this.escapeHtml(sourceText)}</span>
          </td>
          <td class="col-action" style="text-align:center;" data-label="${isMm ? 'လုပ်ဆောင်ချက်' : 'Action'}">
            <button class="btn-bubble btn-glass-subtle waste-delete-action-btn" onclick="Waste.deleteWaste(${r.id})" title="${isMm ? 'ဖျက်မည်' : 'Delete'}">🗑️</button>
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
    this.currentSubmissionToken = 'waste_' + Date.now() + '_' + Math.random().toString(36).substring(2, 9);
    this.submitting = false;
    this.fetchFoodItems();
    const form = document.getElementById('waste-form');
    if (form) {
      form.reset();
      form.style.pointerEvents = '';
    }
    const errorEl = document.getElementById('waste-qty-error');
    if (errorEl) errorEl.style.display = 'none';
    const qtyInput = document.getElementById('waste-qty');
    if (qtyInput) qtyInput.style.borderColor = '';
    const submitBtn = document.getElementById('waste-submit-btn');
    if (submitBtn) {
      submitBtn.disabled = false;
      submitBtn.style.pointerEvents = '';
      submitBtn.style.opacity = '';
      submitBtn.style.cursor = '';
      const isMm = typeof I18n !== 'undefined' && I18n.getLanguage() === 'mm';
      submitBtn.textContent = isMm ? 'အလေအလွင့် မှတ်တမ်းတင်မည်' : 'Save Waste Log';
    }
    const modal = document.getElementById('log-waste-modal');
    if (modal) modal.classList.add('active');
    setTimeout(() => {
      this.onFoodItemChanged();
    }, 100);
  },

  closeModal() {
    this.submitting = false;
    const modal = document.getElementById('log-waste-modal');
    if (modal) modal.classList.remove('active');
    const form = document.getElementById('waste-form');
    if (form) form.style.pointerEvents = '';
    const submitBtn = document.getElementById('waste-submit-btn');
    if (submitBtn) {
      submitBtn.disabled = false;
      submitBtn.style.pointerEvents = '';
      submitBtn.style.opacity = '';
      submitBtn.style.cursor = '';
    }
  },

  async saveWaste(e) {
    if (e) {
      e.preventDefault();
      e.stopPropagation();
    }

    // 1. Guard against duplicate / rapid concurrent submissions
    if (this.submitting) {
      console.warn('[Waste] Submission already in progress. Ignoring duplicate trigger.');
      return;
    }

    const now = Date.now();
    if (now - this.lastSubmitTime < 1500) {
      console.warn('[Waste] Rapid submission throttled (<1500ms).');
      return;
    }

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

    // 2. Lock submission state immediately and apply visual loading feedback
    this.submitting = true;
    this.lastSubmitTime = now;

    const submitBtn = document.getElementById('waste-submit-btn');
    const form = document.getElementById('waste-form');
    let origBtnText = '';

    if (submitBtn) {
      origBtnText = submitBtn.textContent;
      submitBtn.disabled = true;
      submitBtn.style.pointerEvents = 'none';
      submitBtn.style.opacity = '0.65';
      submitBtn.style.cursor = 'not-allowed';
      submitBtn.innerHTML = `
        <span style="display:inline-block; animation: spin 1s linear infinite; margin-right:0.4rem;">⏳</span>
        ${isMm ? 'အလေအလွင့် မှတ်တမ်းတင်နေပါသည်...' : 'Saving Waste Log...'}
      `;
    }

    if (form) {
      form.style.pointerEvents = 'none';
    }

    const payload = {
      foodItemId,
      quantityWasted,
      reason,
      notes,
      clientRequestId: this.currentSubmissionToken || ('waste_' + Date.now() + '_' + Math.random().toString(36).substring(2, 9))
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
    } finally {
      this.submitting = false;
      if (submitBtn) {
        submitBtn.disabled = false;
        submitBtn.style.pointerEvents = '';
        submitBtn.style.opacity = '';
        submitBtn.style.cursor = '';
        submitBtn.textContent = origBtnText || (isMm ? 'အလေအလွင့် မှတ်တမ်းတင်မည်' : 'Save Waste Log');
      }
      if (form) {
        form.style.pointerEvents = '';
      }
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

const Inventory = {
  items: [],
  editingId: null,
  disposalItem: null,
  loading: false,
  submittingDisposal: false,

  async init() {
    this.bindEvents();
    await this.fetchItems();

    // Check if opened with ?expiredReview=true
    const params = new URLSearchParams(window.location.search);
    if (params.get('expiredReview') === 'true') {
      const firstExpired = this.items.find(i => {
        const eff = i.status || i.expiryStatus;
        return (eff === 'EXPIRED' || (i.expiryDaysRemaining !== undefined && i.expiryDaysRemaining < 0)) && Number(i.quantity || 0) > 0;
      });
      if (firstExpired) {
        this.openDisposalModal(firstExpired.id);
      }
    }

    window.addEventListener('languageChanged', () => {
      this.render();
    });
  },

  bindEvents() {
    const searchInput = document.getElementById('inv-search-input');
    const catFilter = document.getElementById('category-filter');
    const statusFilter = document.getElementById('status-filter');

    if (searchInput) {
      searchInput.addEventListener('input', () => this.render());
    }
    if (catFilter) {
      catFilter.addEventListener('change', () => this.render());
    }
    if (statusFilter) {
      statusFilter.addEventListener('change', () => this.render());
    }

    const summaryModal = document.getElementById('inventory-summary-modal');
    if (summaryModal) {
      summaryModal.addEventListener('click', (e) => {
        if (e.target === summaryModal) this.closeSummaryModal();
      });
    }

    const headerSummaryBtn = document.getElementById('btn-inventory-summary');
    if (headerSummaryBtn) {
      headerSummaryBtn.onclick = (e) => {
        if (e) {
          e.preventDefault();
          e.stopPropagation();
        }
        this.openSummaryModal();
      };
    }
  },

  async fetchItems() {
    this.loading = true;
    this.renderLoading();
    try {
      const res = await API.get('/api/inventory');
      this.items = (res && res.data) ? res.data : [];
    } catch (err) {
      console.warn('API fetch fallback:', err);
      API.showToast('Using local inventory view', 'info');
    } finally {
      this.loading = false;
      this.render();
    }
  },

  renderLoading() {
    const tbody = document.getElementById('inventory-tbody');
    if (!tbody) return;
    const isMm = typeof I18n !== 'undefined' && I18n.getLanguage() === 'mm';
    tbody.innerHTML = `
      <tr>
        <td colspan="7" style="text-align:center; padding:3rem; color:var(--text-muted);">
          <div style="font-size:1.5rem; animation: spin 1s linear infinite; display:inline-block;">🍃</div>
          <div style="margin-top:0.5rem; font-weight:600;">${isMm ? 'ကုန်ပစ္စည်းစာရင်းများကို ရယူနေပါသည်...' : 'Loading fresh inventory records...'}</div>
        </td>
      </tr>
    `;
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

  render() {
    const tbody = document.getElementById('inventory-tbody');
    if (!tbody) return;

    if (this.loading) {
      this.renderLoading();
      return;
    }

    const isMm = typeof I18n !== 'undefined' && I18n.getLanguage() === 'mm';
    const query = (document.getElementById('inv-search-input')?.value || '').toLowerCase();
    const cat = document.getElementById('category-filter')?.value || '';
    const status = document.getElementById('status-filter')?.value || '';

    const filtered = this.items.filter(item => {
      const matchQuery = (item.name || '').toLowerCase().includes(query) || (item.category || '').toLowerCase().includes(query);
      const matchCat = !cat || (item.category && item.category.trim().toLowerCase() === cat.trim().toLowerCase());
      const effectiveStatus = (item.expiryDaysRemaining === 0) ? 'SAME_DAY_EXPIRY' : (item.displayStatus || item.display_status || item.expiryStatus || item.status || 'OK');
      const matchStatus = !status || effectiveStatus === status || item.status === status || item.expiryStatus === status || (status === 'SAME_DAY_EXPIRY' && item.expiryDaysRemaining === 0);
      return matchQuery && matchCat && matchStatus;
    });

    if (filtered.length === 0) {
      tbody.innerHTML = `
        <tr>
          <td colspan="8" style="text-align:center; padding:3rem; color:var(--text-muted);">
            <div style="font-size:2rem; margin-bottom:0.5rem;">📦</div>
            <div style="font-weight:700; color:var(--text-main); font-size:1.05rem;">${typeof I18n !== 'undefined' ? I18n.t('inv.empty.title') : 'No Food Items Found'}</div>
            <div style="font-size:0.85rem; margin-top:0.25rem;">${typeof I18n !== 'undefined' ? I18n.t('inv.empty.desc') : 'Try adjusting your search criteria or click "+ Add Food Item" to add new inventory.'}</div>
          </td>
        </tr>
      `;
      return;
    }

    tbody.innerHTML = filtered.map(item => {
      const effectiveStatus = item.displayStatus || item.display_status || item.expiryStatus || item.status || 'OK';
      const days = item.expiryDaysRemaining !== undefined ? item.expiryDaysRemaining : 999;
      const totalStock = Number(item.totalQuantity != null ? item.totalQuantity : (item.quantity || 0));
      const remainingStock = Number(item.remainingQuantity != null ? item.remainingQuantity : (item.quantity || 0));

      let badgeClass = 'badge-risk-low';
      let statusText = typeof I18n !== 'undefined' ? I18n.translateStatus(effectiveStatus) : effectiveStatus;
      let secondaryBadge = '';

      if (remainingStock <= 0 || effectiveStatus === 'OUT_OF_STOCK') {
        badgeClass = 'badge-risk-low';
        statusText = isMm ? 'လက်ကျန်မရှိ' : 'Out of Stock';
        secondaryBadge = '';
      } else if (effectiveStatus === 'EXPIRED' || days < 0) {
        badgeClass = 'badge-risk-high';
        statusText = isMm ? 'သက်တမ်းကုန်' : 'EXPIRED';
        if (remainingStock > 0) {
          secondaryBadge = `<button class="btn-bubble btn-yellow btn-sm-bubble" style="font-size:0.72rem; padding:0.2rem 0.5rem; margin-top:0.25rem;" onclick="Inventory.openDisposalModal(${item.id})">⚠️ ${isMm ? 'စွန့်ပစ်မှု သုံးသပ်ရန်' : 'Review Disposal'}</button>`;
        }
      } else if (days === 0 || effectiveStatus === 'SAME_DAY_EXPIRY' || item.expiryStatus === 'SAME_DAY_EXPIRY') {
        badgeClass = 'badge-risk-high';
        statusText = isMm ? 'ယနေ့ သက်တမ်းကုန်' : 'Expires Today';
      } else if (days <= 3 && days > 0 && remainingStock > 0) {
        badgeClass = 'badge-risk-medium';
        statusText = isMm ? 'သက်တမ်းကုန်ရန်နီး' : 'Near Expiry';
        secondaryBadge = `<span class="badge-bubble" style="background:rgba(220,38,38,0.12); color:#DC2626; font-size:0.68rem; margin-top:0.2rem; display:inline-block;">${isMm ? 'ဦးစားပေး လှူဒါန်းရန်' : 'Priority Redistribution'}</span>`;
      } else if (days <= 7 && days > 3 && remainingStock > 0) {
        badgeClass = 'badge-risk-medium';
        secondaryBadge = `<span class="badge-bubble" style="background:rgba(14,165,233,0.12); color:#0284C7; font-size:0.68rem; margin-top:0.2rem; display:inline-block;">${isMm ? 'ပြန်လည်လှူဒါန်းရန် စဉ်းစားပါ' : 'Consider Redistribution'}</span>`;
      }

      const catText = typeof I18n !== 'undefined' ? I18n.translateFoodCategory(item.category) : item.category;
      const editBtnText = typeof I18n !== 'undefined' ? I18n.t('action.edit') : 'Edit';

      const priceFmt = Number(item.pricePerUnit || 0).toLocaleString() + ' MMK';
      const totalQtyFmt = totalStock.toFixed(2) + ' ' + (item.unit || 'kg');
      const remainingQtyFmt = remainingStock.toFixed(2) + ' ' + (item.unit || 'kg');
      const expiryFmt = item.expiryDate || 'N/A';

      return `
        <tr>
          <td>
            <a href="javascript:void(0)" onclick="Inventory.openHistoryModal(${item.id})" style="text-decoration:none; color:inherit; display:inline-block;" title="${isMm ? 'သိုလှောင်မှု မှတ်တမ်းကြည့်ရန် နှိပ်ပါ' : 'Click to view stock history'}">
              <strong style="color:var(--accent-primary, #2563EB); cursor:pointer; display:inline-flex; align-items:center; gap:0.35rem;">
                <span>${this.escapeHtml(item.name)}</span>
                <span style="font-size:0.8rem; opacity:0.8;">📋</span>
              </strong>
              <div style="font-size:0.75rem; color:var(--text-muted);">ID #${item.id} &bull; <span style="text-decoration:underline; cursor:pointer;">${isMm ? 'မှတ်တမ်း' : 'History'}</span></div>
            </a>
          </td>
          <td><span class="badge-bubble badge-optimization">${catText}</span></td>
          <td><span style="font-size:0.95rem; font-weight:600; color:var(--text-main);">${totalQtyFmt}</span></td>
          <td><strong style="font-size:1rem; color:var(--accent-yellow-dark);">${remainingQtyFmt}</strong></td>
          <td>${priceFmt}</td>
          <td><strong>${expiryFmt}</strong></td>
          <td>
            <span class="badge-bubble ${badgeClass}">${statusText}</span>
            ${secondaryBadge ? `<div>${secondaryBadge}</div>` : ''}
          </td>
          <td style="text-align:right; white-space:nowrap;">
            <button class="btn-bubble btn-glass-subtle btn-sm-bubble" title="${isMm ? 'မှတ်တမ်းကြည့်ရန်' : 'View History'}" onclick="Inventory.openHistoryModal(${item.id})" style="margin-right:0.25rem;">📋</button>
            <button class="btn-bubble btn-glass-subtle btn-sm-bubble" onclick="Inventory.openEditModal(${item.id})">${editBtnText}</button>
            <button class="btn-bubble btn-glass-subtle btn-sm-bubble" style="color:var(--risk-high-text); margin-left:0.25rem;" onclick="Inventory.deleteItem(${item.id})">🗑️</button>
          </td>
        </tr>
      `;
    }).join('');
  },

  openModal() {
    this.editingId = null;
    const isMm = typeof I18n !== 'undefined' && I18n.getLanguage() === 'mm';
    document.getElementById('modal-food-title').textContent = isMm ? '+ ကုန်ပစ္စည်းအသစ် ထည့်သွင်းခြင်း' : '+ Add New Food Item';
    document.getElementById('inventory-form').reset();
    const catSelect = document.getElementById('new-food-cat');
    if (catSelect) catSelect.value = 'Poultry';
    const unitSelect = document.getElementById('new-food-unit');
    if (unitSelect) unitSelect.value = 'kg';
    const modal = document.getElementById('add-item-modal');
    if (modal) modal.classList.add('active');
  },

  openEditModal(id) {
    const item = this.items.find(i => i.id === id);
    if (!item) return;

    this.editingId = id;
    const isMm = typeof I18n !== 'undefined' && I18n.getLanguage() === 'mm';
    document.getElementById('modal-food-title').textContent = isMm ? ('✏️ ကုန်ပစ္စည်း ပြင်ဆင်ခြင်း #' + id) : ('✏️ Edit Food Item #' + id);
    document.getElementById('new-food-name').value = item.name || '';

    // Robust case-insensitive selection for Category
    const catSelect = document.getElementById('new-food-cat');
    if (catSelect) {
      const itemCat = (item.category || '').trim().toLowerCase();
      let matched = false;
      for (let i = 0; i < catSelect.options.length; i++) {
        if (catSelect.options[i].value.toLowerCase() === itemCat) {
          catSelect.selectedIndex = i;
          matched = true;
          break;
        }
      }
      if (!matched && item.category) {
        const opt = document.createElement('option');
        opt.value = item.category;
        opt.textContent = item.category;
        catSelect.appendChild(opt);
        catSelect.value = item.category;
      }
    }

    // Robust case-insensitive selection for Unit
    const unitSelect = document.getElementById('new-food-unit');
    if (unitSelect) {
      const itemUnit = (item.unit || 'kg').trim().toLowerCase();
      let matched = false;
      for (let i = 0; i < unitSelect.options.length; i++) {
        if (unitSelect.options[i].value.toLowerCase() === itemUnit) {
          unitSelect.selectedIndex = i;
          matched = true;
          break;
        }
      }
      if (!matched && item.unit) {
        const opt = document.createElement('option');
        opt.value = item.unit;
        opt.textContent = item.unit;
        unitSelect.appendChild(opt);
        unitSelect.value = item.unit;
      }
    }

    document.getElementById('new-food-qty').value = item.remainingQuantity != null ? item.remainingQuantity : (item.quantity || '');
    document.getElementById('new-food-price').value = item.pricePerUnit || '';
    document.getElementById('new-food-expiry').value = item.expiryDate || '';

    const modal = document.getElementById('add-item-modal');
    if (modal) modal.classList.add('active');
  },

  closeModal() {
    this.editingId = null;
    const modal = document.getElementById('add-item-modal');
    if (modal) modal.classList.remove('active');
  },

  async saveItem(e) {
    e.preventDefault();
    if (this._isSaving) return;

    const submitBtn = e.target ? e.target.querySelector('button[type="submit"]') : null;
    const isMm = typeof I18n !== 'undefined' && I18n.getLanguage() === 'mm';
    const origBtnText = submitBtn ? submitBtn.textContent : '';

    const name = document.getElementById('new-food-name').value.trim();
    const category = document.getElementById('new-food-cat').value;
    const quantity = parseFloat(document.getElementById('new-food-qty').value);
    const unit = document.getElementById('new-food-unit').value.trim();
    const pricePerUnit = parseFloat(document.getElementById('new-food-price').value);
    const expiryDate = document.getElementById('new-food-expiry').value;

    if (!name || isNaN(quantity) || isNaN(pricePerUnit) || !expiryDate) {
      API.showToast(isMm ? 'လိုအပ်သော အချက်အလက်များကို မှန်ကန်စွာ ဖြည့်သွင်းပါ' : 'Please fill out all required fields correctly', 'warning');
      return;
    }

    if (quantity <= 0) {
      API.showToast(isMm ? 'အရေအတွက်သည် သုညထက် ကြီးရပါမည်' : 'Quantity must be greater than zero', 'warning');
      return;
    }

    this._isSaving = true;
    if (submitBtn) {
      submitBtn.disabled = true;
      submitBtn.textContent = isMm ? 'သိမ်းဆည်းနေသည်...' : 'Saving...';
    }

    const payload = {
      name,
      category,
      quantity,
      unit,
      pricePerUnit,
      expiryDate
    };

    try {
      if (this.editingId) {
        payload.id = this.editingId;
        await API.put(`/api/inventory/${this.editingId}`, payload);
        API.showToast(isMm ? `'${name}' ကို ပြင်ဆင်သိမ်းဆည်းပြီးပါပြီ!` : `Updated '${name}' in inventory!`, 'success');
      } else {
        const res = await API.post('/api/inventory', payload);
        const itemRes = res && res.data;
        const wasMerged = itemRes && this.items.some(i => i.id === itemRes.id);
        if (wasMerged) {
          API.showToast(isMm ? `'${name}' သိုလှောင်လက်ကျန်ကို ပေါင်းစည်းထည့်သွင်းပြီးပါပြီ (+${quantity} ${unit})!` : `Merged stock addition for '${name}' (+${quantity} ${unit})!`, 'success');
        } else {
          API.showToast(isMm ? `'${name}' ကို စာရင်းသို့ ထည့်သွင်းပြီးပါပြီ!` : `Added '${name}' to inventory!`, 'success');
        }
      }
      this.closeModal();
      await this.fetchItems();
    } catch (err) {
      console.error('Error saving item:', err);
      API.showToast(isMm ? ('သိမ်းဆည်းမှု မအောင်မြင်ပါ: ' + err.message) : ('Failed to save item: ' + err.message), 'error');
    } finally {
      this._isSaving = false;
      if (submitBtn) {
        submitBtn.disabled = false;
        submitBtn.textContent = origBtnText;
      }
    }
  },

  async openHistoryModal(id) {
    const numId = Number(id);
    if (!numId || isNaN(numId)) return;
    const item = this.items.find(i => i.id === numId);
    if (!item) return;

    // Explicitly ensure header summary modal is closed
    const summaryModal = document.getElementById('inventory-summary-modal');
    if (summaryModal) summaryModal.classList.remove('active');

    const modal = document.getElementById('stock-history-modal');
    if (!modal) return;

    const isMm = typeof I18n !== 'undefined' && I18n.getLanguage() === 'mm';

    const titleEl = document.getElementById('history-modal-title');
    if (titleEl) titleEl.textContent = isMm ? '📋 သိုလှောင်မှု ထပ်တိုးမှတ်တမ်း' : '📋 Stock Addition History';
    
    const idEl = document.getElementById('history-item-id-tag');
    if (idEl) idEl.textContent = `Item ID #${item.id}`;

    const prodEl = document.getElementById('history-product-name');
    if (prodEl) prodEl.textContent = item.name || 'Food Item';

    const totalStock = Number(item.totalQuantity != null ? item.totalQuantity : (item.quantity || 0));
    const remainingStock = Number(item.remainingQuantity != null ? item.remainingQuantity : (item.quantity || 0));

    const totalQtyEl = document.getElementById('history-total-qty');
    if (totalQtyEl) totalQtyEl.textContent = `${totalStock.toFixed(2)} ${item.unit || 'kg'}`;

    const currentQtyEl = document.getElementById('history-current-qty');
    if (currentQtyEl) currentQtyEl.textContent = `${remainingStock.toFixed(2)} ${item.unit || 'kg'}`;

    const unitPriceEl = document.getElementById('history-unit-price');
    if (unitPriceEl) unitPriceEl.textContent = `${Number(item.pricePerUnit || 0).toLocaleString()} MMK`;

    const expiryEl = document.getElementById('history-expiry-date');
    if (expiryEl) expiryEl.textContent = item.expiryDate || 'N/A';

    const catEl = document.getElementById('history-category');
    if (catEl) catEl.textContent = typeof I18n !== 'undefined' ? I18n.translateFoodCategory(item.category) : (item.category || '--');

    const listContainer = document.getElementById('history-list-container');
    const countTag = document.getElementById('history-entry-count');
    if (listContainer) {
      listContainer.innerHTML = `
        <div style="text-align:center; padding:1.5rem; color:var(--text-muted);">
          <div style="font-size:1.2rem; animation: spin 1s linear infinite; display:inline-block;">🍃</div>
          <div style="font-size:0.8rem; margin-top:0.35rem;">${isMm ? 'မှတ်တမ်းများ ရယူနေပါသည်...' : 'Loading stock history...'}</div>
        </div>
      `;
    }
    if (countTag) {
      countTag.textContent = '...';
    }

    modal.classList.add('active');

    try {
      const res = await API.get(`/api/inventory/${id}/history`);
      const transactions = (res && Array.isArray(res.data)) ? res.data : [];
      this.renderHistoryList(transactions, item.unit || 'kg', isMm);
    } catch (err) {
      console.warn('Failed to fetch stock history:', err);
      this.renderHistoryList([], item.unit || 'kg', isMm);
    }
  },

  closeHistoryModal() {
    const modal = document.getElementById('stock-history-modal');
    if (modal) modal.classList.remove('active');
  },

  renderHistoryList(transactions, defaultUnit, isMm) {
    const listContainer = document.getElementById('history-list-container');
    const countTag = document.getElementById('history-entry-count');
    if (!listContainer) return;

    // Filter for stock additions / purchases / manual counts
    const stockIns = transactions.filter(t => {
      const type = (t.transactionType || '').toUpperCase();
      return type === 'PURCHASE' || type === 'STOCK_IN' || type === 'MANUAL_COUNT';
    });

    if (countTag) {
      countTag.textContent = isMm ? `${stockIns.length} ကြိမ် ထည့်သွင်းထားသည်` : `${stockIns.length} stock addition event${stockIns.length !== 1 ? 's' : ''}`;
    }

    if (stockIns.length === 0) {
      listContainer.innerHTML = `
        <div style="text-align:center; padding:2rem 1rem; color:var(--text-muted); background:rgba(0,0,0,0.02); border-radius:10px; border:1px dashed var(--glass-border-subtle);">
          <div style="font-size:1.6rem; margin-bottom:0.4rem;">📜</div>
          <div style="font-weight:600; font-size:0.9rem; color:var(--text-main);">
            ${isMm ? 'ဤကုန်ပစ္စည်းအတွက် သိုလှောင်မှု ထပ်တိုးမှတ်တမ်း မရှိသေးပါ' : 'No stock addition history is available for this earlier record.'}
          </div>
          <div style="font-size:0.78rem; margin-top:0.25rem;">
            ${isMm ? 'စနစ်စတင်ချိန်မှ မှတ်တမ်းတင်ထားသော အချက်အလက်များသာ ပေါ်ပါမည်' : 'Historical stock-in transactions are tracked for additions made in the system.'}
          </div>
        </div>
      `;
      return;
    }

    listContainer.innerHTML = stockIns.map((tx, idx) => {
      const isInitial = idx === stockIns.length - 1 || (tx.notes && tx.notes.toLowerCase().includes('initial'));
      const title = isInitial 
        ? (isMm ? 'စတင် သိုလှောင်မှု' : 'Initial Stock')
        : (isMm ? 'သိုလှောင်မှု ထပ်တိုး' : 'Stock Addition');

      const qty = Number(tx.quantity || 0).toFixed(2);
      const unit = tx.unit || defaultUnit;
      let dateStr = tx.createdAt ? tx.createdAt.replace('T', ' ').substring(0, 16) : 'N/A';

      return `
        <div style="background:rgba(255,255,255,0.7); border:1px solid var(--glass-border-subtle); border-radius:10px; padding:0.75rem 1rem; display:flex; justify-content:space-between; align-items:center;">
          <div>
            <div style="font-weight:700; font-size:0.9rem; color:var(--text-main); display:flex; align-items:center; gap:0.4rem;">
              <span>${isInitial ? '📦' : '📥'}</span>
              <span>${title}</span>
            </div>
            <div style="font-size:0.75rem; color:var(--text-muted); margin-top:2px;">
              🕒 ${dateStr} ${tx.createdByName ? `&bull; ${this.escapeHtml(tx.createdByName)}` : ''}
            </div>
            ${tx.notes ? `<div style="font-size:0.75rem; color:var(--text-body); margin-top:2px;">${this.escapeHtml(tx.notes)}</div>` : ''}
          </div>
          <div style="text-align:right;">
            <span style="font-size:1.05rem; font-weight:800; color:#16A34A; background:rgba(22,163,74,0.1); padding:0.25rem 0.6rem; border-radius:var(--radius-pill);">
              +${qty} ${unit}
            </span>
          </div>
        </div>
      `;
    }).join('');
  },

  async deleteItem(id) {
    const item = this.items.find(i => i.id === id);
    const name = item ? item.name : 'this item';
    const isMm = typeof I18n !== 'undefined' && I18n.getLanguage() === 'mm';
    if (!confirm(isMm ? `'${name}' ကို ကုန်ပစ္စည်းစာရင်းမှ ဖျက်ရန် သေချာပါသလား?` : `Are you sure you want to delete '${name}' from inventory?`)) {
      return;
    }

    try {
      await API.delete(`/api/inventory/${id}`);
      API.showToast(isMm ? `'${name}' ကို ဖျက်ပြီးပါပြီ` : `Deleted '${name}'`, 'info');
      await this.fetchItems();
    } catch (err) {
      console.error('Error deleting item:', err);
      API.showToast(isMm ? ('ဖျက်ရန် မအောင်မြင်ပါ: ' + err.message) : ('Failed to delete item: ' + err.message), 'error');
    }
  },

  openDisposalModal(id) {
    const item = this.items.find(i => i.id === id);
    if (!item) return;

    this.disposalItem = item;
    const isMm = typeof I18n !== 'undefined' && I18n.getLanguage() === 'mm';

    const foodNameEl = document.getElementById('disposal-food-name');
    const promptEl = document.getElementById('disposal-prompt-text');
    const qtyInput = document.getElementById('disposal-qty');

    if (foodNameEl) foodNameEl.textContent = item.name;
    if (promptEl) {
      const qtyStr = `${Number(item.quantity || 0).toFixed(1)} ${item.unit || 'kg'}`;
      promptEl.textContent = isMm ?
        `${item.name} သည် သက်တမ်းကုန်ဆုံးသွားပြီး ${qtyStr} ကျန်ရှိနေပါသည်။ မည်သို့ဆောင်ရွက်လိုပါသလဲ?` :
        `${item.name} has expired with ${qtyStr} remaining. How would you like to handle it?`;
    }
    if (qtyInput) {
      qtyInput.value = item.quantity || 0;
      qtyInput.max = item.quantity || 0;
    }

    this.updateDisposalFinancialLoss();

    const modal = document.getElementById('disposal-modal');
    if (modal) modal.classList.add('active');
  },

  closeDisposalModal() {
    this.submittingDisposal = false;
    this.disposalItem = null;
    const modal = document.getElementById('disposal-modal');
    if (modal) {
      modal.classList.remove('active');
      modal.style.pointerEvents = '';
    }
  },

  updateDisposalFinancialLoss() {
    if (!this.disposalItem) return;
    const qtyInput = document.getElementById('disposal-qty');
    const lossEl = document.getElementById('disposal-financial-loss');
    const qty = parseFloat(qtyInput?.value) || 0;
    const price = Number(this.disposalItem.pricePerUnit || 0);
    const loss = qty * price;
    if (lossEl) {
      lossEl.textContent = Math.round(loss).toLocaleString() + ' MMK';
    }
  },

  async confirmRecordAsWaste() {
    if (this.submittingDisposal) {
      console.warn('[Inventory] Disposal submission already in progress.');
      return;
    }
    if (!this.disposalItem) return;
    const item = this.disposalItem;
    const isMm = typeof I18n !== 'undefined' && I18n.getLanguage() === 'mm';
    const qtyInput = document.getElementById('disposal-qty');
    const qty = parseFloat(qtyInput?.value) || 0;

    if (qty <= 0) {
      API.showToast(isMm ? 'စွန့်ပစ်မည့် ပမာဏကို ထည့်သွင်းပါ' : 'Please enter a valid disposal quantity', 'warning');
      return;
    }

    this.submittingDisposal = true;
    const modal = document.getElementById('disposal-modal');
    if (modal) modal.style.pointerEvents = 'none';

    const payload = {
      foodItemId: item.id,
      quantityWasted: qty,
      unit: item.unit || 'kg',
      reason: 'EXPIRED',
      notes: 'Recorded via Expired Item Disposal Review',
      clientRequestId: 'disposal_waste_' + item.id + '_' + Date.now() + '_' + Math.random().toString(36).substring(2, 9)
    };

    try {
      await API.post('/api/waste', payload);
      API.showToast(isMm ? `'${item.name}' အား စွန့်ပစ်ပစ္စည်းအဖြစ် အောင်မြင်စွာ မှတ်တမ်းတင်ပြီးပါပြီ` : `Logged ${qty} ${item.unit} '${item.name}' as waste!`, 'success');
      this.closeDisposalModal();
      await this.fetchItems();
    } catch (err) {
      console.error('Error logging disposal waste:', err);
      API.showToast(isMm ? ('မှတ်တမ်းတင်ရန် မအောင်မြင်ပါ: ' + err.message) : ('Failed to record waste: ' + err.message), 'error');
    } finally {
      this.submittingDisposal = false;
      if (modal) modal.style.pointerEvents = '';
    }
  },

  async confirmAlreadyDisposed() {
    if (this.submittingDisposal) {
      console.warn('[Inventory] Disposal submission already in progress.');
      return;
    }
    if (!this.disposalItem) return;
    const item = this.disposalItem;
    const isMm = typeof I18n !== 'undefined' && I18n.getLanguage() === 'mm';
    const confirmPrompt = isMm ?
      `'${item.name}' အား စွန့်ပစ်ပြီးဖြစ်ကြောင်း အတည်ပြုပါသလား? ၎င်းသည် ကုန်ပစ္စည်းလက်ကျန်ကို နုတ်ယူပြီး စာရင်းစစ်မှတ်တမ်း ရေးသွင်းပါမည်။` :
      `Confirm that '${item.name}' was already discarded? This will clear remaining stock and log the disposal.`;

    if (!confirm(confirmPrompt)) {
      return;
    }

    this.submittingDisposal = true;
    const modal = document.getElementById('disposal-modal');
    if (modal) modal.style.pointerEvents = 'none';

    const payload = {
      foodItemId: item.id,
      quantityWasted: item.quantity,
      unit: item.unit || 'kg',
      reason: 'EXPIRED',
      notes: 'Confirmed as already disposed',
      clientRequestId: 'disposal_already_' + item.id + '_' + Date.now() + '_' + Math.random().toString(36).substring(2, 9)
    };

    try {
      await API.post('/api/waste', payload);
      API.showToast(isMm ? `'${item.name}' အား စွန့်ပစ်ပြီးအဖြစ် စာရင်းပြုစုပြီးပါပြီ` : `Recorded '${item.name}' as already disposed!`, 'success');
      this.closeDisposalModal();
      await this.fetchItems();
    } catch (err) {
      console.error('Error confirming already disposed:', err);
      API.showToast(isMm ? ('ဆောင်ရွက်ရန် မအောင်မြင်ပါ: ' + err.message) : ('Failed to record disposal: ' + err.message), 'error');
    } finally {
      this.submittingDisposal = false;
      if (modal) modal.style.pointerEvents = '';
    }
  },

  reviewLater() {
    const isMm = typeof I18n !== 'undefined' && I18n.getLanguage() === 'mm';
    API.showToast(isMm ? 'နောက်မှ ပြန်လည်ကြည့်ရှုရန် ထားရှိပါသည်' : 'Disposal review postponed for later', 'info');
    this.closeDisposalModal();
  },

  async openSummaryModal() {
    // Header summary is strictly separated from item history; ensure item history modal is closed
    const stockHistoryModal = document.getElementById('stock-history-modal');
    if (stockHistoryModal) stockHistoryModal.classList.remove('active');

    const modal = document.getElementById('inventory-summary-modal');
    if (!modal) return;
    modal.classList.add('active');
    document.body.style.overflow = 'hidden';

    this.renderSummaryModalLoading();

    try {
      // Dynamic refresh: fetch live real records from backend every time popup opens
      const [salesRes, redistRes, invRes] = await Promise.all([
        API.get('/api/sales'),
        API.get('/api/redistribution'),
        API.get('/api/inventory')
      ]);

      const sales = (salesRes && Array.isArray(salesRes.data)) ? salesRes.data : [];
      const dispatches = (redistRes && Array.isArray(redistRes.data)) ? redistRes.data : [];
      const inventory = (invRes && Array.isArray(invRes.data)) ? invRes.data : (this.items || []);

      this.renderSummaryModalContent(sales, dispatches, inventory);
    } catch (err) {
      console.error('Failed to load sales & donations summary:', err);
      this.renderSummaryModalError(err);
    }
  },

  closeSummaryModal() {
    const modal = document.getElementById('inventory-summary-modal');
    if (modal) {
      modal.classList.remove('active');
      document.body.style.overflow = '';
    }
  },

  renderSummaryModalLoading() {
    const body = document.getElementById('summary-modal-body');
    if (!body) return;
    const isMm = typeof I18n !== 'undefined' && I18n.getLanguage() === 'mm';

    body.innerHTML = `
      <div style="text-align:center; padding:2.5rem 1rem; color:var(--text-muted);">
        <div style="font-size:1.5rem; animation: spin 1s linear infinite; display:inline-block;">🍃</div>
        <div style="font-size:0.85rem; margin-top:0.5rem; font-weight:600; color:var(--text-main);">
          ${isMm ? 'အရောင်းနှင့် လှူဒါန်းမှု မှတ်တမ်းများကို ရယူနေပါသည်...' : 'Loading sales & donation records...'}
        </div>
      </div>
    `;
  },

  renderSummaryModalError(err) {
    const body = document.getElementById('summary-modal-body');
    if (!body) return;
    const isMm = typeof I18n !== 'undefined' && I18n.getLanguage() === 'mm';

    body.innerHTML = `
      <div style="text-align:center; padding:2rem 1rem; color:var(--risk-high-text, #EF4444);">
        <div style="font-size:1.8rem; margin-bottom:0.4rem;">⚠️</div>
        <div style="font-weight:700; font-size:0.9rem;">
          ${isMm ? 'ဒေတာ ရယူခြင်း မအောင်မြင်ပါ' : 'Failed to load summary data'}
        </div>
        <div style="font-size:0.75rem; color:var(--text-muted); margin-top:0.25rem;">
          ${err ? (err.message || String(err)) : ''}
        </div>
      </div>
    `;
  },

  renderSummaryModalContent(sales, dispatches, inventory) {
    const body = document.getElementById('summary-modal-body');
    if (!body) return;

    const isMm = typeof I18n !== 'undefined' && I18n.getLanguage() === 'mm';

    // Map inventory items by numeric ID to preserve precise relationships
    const invMap = new Map();
    (inventory || []).forEach(item => {
      if (item && item.id != null) {
        invMap.set(Number(item.id), item);
      }
    });

    // 1. Process Sales records:
    // Units total map: unit -> totalQty
    const soldUnitsMap = new Map();
    // Item breakdown map: foodItemId -> { foodItemId, name, unit, qty }
    const soldItemsMap = new Map();

    (sales || []).forEach(sale => {
      const foodId = sale.foodItemId != null ? Number(sale.foodItemId) : null;
      const invItem = foodId != null ? invMap.get(foodId) : null;

      const rawQty = Number(sale.quantitySold !== undefined ? sale.quantitySold : (sale.quantity || 0));
      if (isNaN(rawQty) || rawQty <= 0) return;

      const unit = (sale.unit || (invItem && invItem.unit) || 'kg').trim();

      // Sum same units
      soldUnitsMap.set(unit, (soldUnitsMap.get(unit) || 0) + rawQty);

      // Keep items correctly separated by foodItemId
      const key = foodId != null ? `id_${foodId}` : `name_${(sale.foodItemName || 'unknown').trim().toLowerCase()}`;
      const name = (invItem && invItem.name) || sale.foodItemName || (foodId ? `Item #${foodId}` : 'Food Item');

      if (soldItemsMap.has(key)) {
        soldItemsMap.get(key).qty += rawQty;
      } else {
        soldItemsMap.set(key, {
          foodItemId: foodId,
          name: name,
          unit: unit,
          qty: rawQty
        });
      }
    });

    // 2. Process Donation / Redistribution records:
    // Units total map: unit -> totalQty
    const donatedUnitsMap = new Map();
    // Item breakdown map: foodItemId -> { foodItemId, name, unit, qty }
    const donatedItemsMap = new Map();

    (dispatches || []).forEach(dispatch => {
      // Exclude cancelled dispatches
      const status = (dispatch.status || '').toUpperCase();
      if (status === 'CANCELLED') return;

      const foodId = dispatch.foodItemId != null ? Number(dispatch.foodItemId) : null;
      const invItem = foodId != null ? invMap.get(foodId) : null;

      const rawQty = Number(dispatch.quantity !== undefined ? dispatch.quantity : 0);
      if (isNaN(rawQty) || rawQty <= 0) return;

      const unit = (dispatch.unit || (invItem && invItem.unit) || 'kg').trim();

      // Sum same units
      donatedUnitsMap.set(unit, (donatedUnitsMap.get(unit) || 0) + rawQty);

      // Keep items correctly separated by foodItemId
      const key = foodId != null ? `id_${foodId}` : `name_${(dispatch.foodItemName || 'unknown').trim().toLowerCase()}`;
      const name = (invItem && invItem.name) || dispatch.foodItemName || (foodId ? `Item #${foodId}` : 'Food Item');

      if (donatedItemsMap.has(key)) {
        donatedItemsMap.get(key).qty += rawQty;
      } else {
        donatedItemsMap.set(key, {
          foodItemId: foodId,
          name: name,
          unit: unit,
          qty: rawQty
        });
      }
    });

    const formatQty = (qty, unit) => {
      const isInt = Number.isInteger(qty) || qty % 1 === 0;
      const numStr = isInt ? Math.round(qty).toString() : Number(qty.toFixed(2)).toString();
      return `${numStr} ${unit}`;
    };

    // Render Sold totals by unit
    let soldTotalsHtml = '';
    if (soldUnitsMap.size === 0) {
      soldTotalsHtml = `<span style="font-size:0.85rem; color:var(--text-muted);">${isMm ? 'အရောင်းမှတ်တမ်း မရှိသေးပါ' : 'No sales recorded'}</span>`;
    } else {
      soldTotalsHtml = Array.from(soldUnitsMap.entries()).map(([unit, qty]) => {
        return `<span style="font-size:1.05rem; font-weight:800; color:var(--accent-primary, #2563EB); background:rgba(37,99,235,0.08); padding:0.25rem 0.65rem; border-radius:var(--radius-pill); border:1px solid rgba(37,99,235,0.15);">${formatQty(qty, unit)}</span>`;
      }).join('');
    }

    // Render Donated totals by unit
    let donatedTotalsHtml = '';
    if (donatedUnitsMap.size === 0) {
      donatedTotalsHtml = `<span style="font-size:0.85rem; color:var(--text-muted);">${isMm ? 'လှူဒါန်းမှု မှတ်တမ်း မရှိသေးပါ' : 'No donations recorded'}</span>`;
    } else {
      donatedTotalsHtml = Array.from(donatedUnitsMap.entries()).map(([unit, qty]) => {
        return `<span style="font-size:1.05rem; font-weight:800; color:#059669; background:rgba(5,150,105,0.08); padding:0.25rem 0.65rem; border-radius:var(--radius-pill); border:1px solid rgba(5,150,105,0.15);">${formatQty(qty, unit)}</span>`;
      }).join('');
    }

    // Render Sold item breakdown
    let soldItemsHtml = '';
    if (soldItemsMap.size === 0) {
      soldItemsHtml = `<div style="font-size:0.8rem; color:var(--text-muted); padding:0.25rem 0;">${isMm ? 'အသေးစိတ် မရှိပါ' : 'No details available'}</div>`;
    } else {
      const nameCounts = new Map();
      soldItemsMap.forEach(i => nameCounts.set(i.name, (nameCounts.get(i.name) || 0) + 1));

      soldItemsHtml = Array.from(soldItemsMap.values()).map(item => {
        const isDuplicate = (nameCounts.get(item.name) || 0) > 1;
        const label = (isDuplicate && item.foodItemId) ? `${item.name} (ID #${item.foodItemId})` : item.name;
        return `
          <div style="display:flex; justify-content:space-between; align-items:center; padding:0.3rem 0; border-bottom:1px solid rgba(0,0,0,0.04);">
            <span style="color:var(--text-main); font-weight:600;">${label}</span>
            <span style="color:var(--accent-primary, #2563EB); font-weight:700;">${formatQty(item.qty, item.unit)}</span>
          </div>
        `;
      }).join('');
    }

    // Render Donated item breakdown
    let donatedItemsHtml = '';
    if (donatedItemsMap.size === 0) {
      donatedItemsHtml = `<div style="font-size:0.8rem; color:var(--text-muted); padding:0.25rem 0;">${isMm ? 'အသေးစိတ် မရှိပါ' : 'No details available'}</div>`;
    } else {
      const nameCounts = new Map();
      donatedItemsMap.forEach(i => nameCounts.set(i.name, (nameCounts.get(i.name) || 0) + 1));

      donatedItemsHtml = Array.from(donatedItemsMap.values()).map(item => {
        const isDuplicate = (nameCounts.get(item.name) || 0) > 1;
        const label = (isDuplicate && item.foodItemId) ? `${item.name} (ID #${item.foodItemId})` : item.name;
        return `
          <div style="display:flex; justify-content:space-between; align-items:center; padding:0.3rem 0; border-bottom:1px solid rgba(0,0,0,0.04);">
            <span style="color:var(--text-main); font-weight:600;">${label}</span>
            <span style="color:#059669; font-weight:700;">${formatQty(item.qty, item.unit)}</span>
          </div>
        `;
      }).join('');
    }

    body.innerHTML = `
      <div style="display:flex; flex-direction:column; gap:1.15rem;">
        <!-- Sold Section -->
        <div style="background:rgba(0,0,0,0.02); border:1px solid var(--glass-border-subtle); border-radius:12px; padding:0.95rem 1rem;">
          <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:0.55rem;">
            <div style="font-weight:800; font-size:0.95rem; color:var(--text-main); display:flex; align-items:center; gap:0.4rem;">
              <span>💰</span>
              <span>${isMm ? 'ရောင်းချထားသော ပမာဏ' : 'Sold'}</span>
            </div>
            <span style="font-size:0.75rem; color:var(--text-muted);">${isMm ? 'ရောင်းချပြီး စုစုပေါင်း' : 'Total Quantity Sold'}</span>
          </div>

          <div style="display:flex; flex-wrap:wrap; gap:0.45rem; margin-bottom:0.75rem;">
            ${soldTotalsHtml}
          </div>

          <div style="border-top:1px dashed var(--glass-border-subtle); padding-top:0.55rem;">
            <div style="font-size:0.75rem; font-weight:700; color:var(--text-muted); margin-bottom:0.35rem; text-transform:uppercase; letter-spacing:0.5px;">
              ${isMm ? 'ပစ္စည်းအလိုက် အသေးစိတ်' : 'Item Breakdown'}
            </div>
            <div style="max-height:130px; overflow-y:auto; display:flex; flex-direction:column; gap:0.25rem; font-size:0.82rem;">
              ${soldItemsHtml}
            </div>
          </div>
        </div>

        <!-- Donated Section -->
        <div style="background:rgba(0,0,0,0.02); border:1px solid var(--glass-border-subtle); border-radius:12px; padding:0.95rem 1rem;">
          <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:0.55rem;">
            <div style="font-weight:800; font-size:0.95rem; color:var(--text-main); display:flex; align-items:center; gap:0.4rem;">
              <span>🤝</span>
              <span>${isMm ? 'လှူဒါန်းထားသော ပမာဏ' : 'Donated'}</span>
            </div>
            <span style="font-size:0.75rem; color:var(--text-muted);">${isMm ? 'ပြန်လည်ခွဲဝေ လှူဒါန်းပြီး စုစုပေါင်း' : 'Total Quantity Donated'}</span>
          </div>

          <div style="display:flex; flex-wrap:wrap; gap:0.45rem; margin-bottom:0.75rem;">
            ${donatedTotalsHtml}
          </div>

          <div style="border-top:1px dashed var(--glass-border-subtle); padding-top:0.55rem;">
            <div style="font-size:0.75rem; font-weight:700; color:var(--text-muted); margin-bottom:0.35rem; text-transform:uppercase; letter-spacing:0.5px;">
              ${isMm ? 'ပစ္စည်းအလိုက် အသေးစိတ်' : 'Item Breakdown'}
            </div>
            <div style="max-height:130px; overflow-y:auto; display:flex; flex-direction:column; gap:0.25rem; font-size:0.82rem;">
              ${donatedItemsHtml}
            </div>
          </div>
        </div>
      </div>
    `;
  }
};

window.Inventory = Inventory;
window.openInventorySummaryModal = () => Inventory.openSummaryModal();
window.closeInventorySummaryModal = () => Inventory.closeSummaryModal();

document.addEventListener('DOMContentLoaded', () => {
  Inventory.init();
});

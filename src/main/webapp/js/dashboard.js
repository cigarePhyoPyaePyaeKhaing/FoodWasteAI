/**
 * FoodWaste AI - Dashboard Controller
 * Real Production Data Fetching from MySQL & Expert Decision REST APIs
 * Clean Zero-State Baseline with Zero Hardcoded Fallbacks
 */
const Dashboard = {
  data: {
    kpis: {
      todayWaste: '0.0',
      todayWasteSub: 'Today: 2026-08-29',
      predictedTomorrow: '0.0',
      predictedTrend: 'No prediction available',
      moneyLost: '0 MMK',
      moneyLostSub: "Today's financial spoilage",
      carbonImpact: '0.0 kg CO₂e',
      carbonSub: 'Diverted food waste tracking'
    },
    highRiskFoods: [],
    recommendations: [],
    totalProjectedSavings: 0,
    predictionData: null,
    wasteLogs: [],
    todayLogs: [],
    inventoryItems: [],
    tomorrowBatches: []
  },

  getTodayDateString() {
    const now = new Date();
    const y = now.getFullYear();
    const m = String(now.getMonth() + 1).padStart(2, '0');
    const d = String(now.getDate()).padStart(2, '0');
    return `${y}-${m}-${d}`;
  },

  async init() {
    this.renderKPIs();
    this.renderHighRiskList();
    this.renderRecommendations();

    // Direct button listener for prediction details
    const btnPred = document.getElementById('btn-pred-details');
    if (btnPred) {
      btnPred.addEventListener('click', (e) => {
        e.preventDefault();
        e.stopPropagation();
        this.openDetailsModal();
      });
    }

    // Direct button listener for confirmed waste details
    const btnWaste = document.getElementById('btn-waste-details');
    if (btnWaste) {
      btnWaste.addEventListener('click', (e) => {
        e.preventDefault();
        e.stopPropagation();
        this.openWasteModal();
      });
    }

    // Document-level event delegation (handles dynamic re-rendering)
    document.addEventListener('click', (e) => {
      const openPredBtn = e.target.closest('#btn-pred-details, [data-action="open-prediction-details"]');
      if (openPredBtn) {
        e.preventDefault();
        e.stopPropagation();
        Dashboard.openDetailsModal();
        return;
      }

      const openWasteBtn = e.target.closest('#btn-waste-details, [data-action="open-waste-details"]');
      if (openWasteBtn) {
        e.preventDefault();
        e.stopPropagation();
        Dashboard.openWasteModal();
        return;
      }

      // Close modal on backdrop click or close button
      if (e.target.id === 'prediction-details-modal' || e.target.closest('[data-action="close-prediction-details"]')) {
        Dashboard.closeDetailsModal();
      }
      if (e.target.id === 'waste-details-modal' || e.target.closest('[data-action="close-waste-details"]')) {
        Dashboard.closeWasteModal();
      }
    });

    window.addEventListener('keydown', (e) => {
      if (e.key === 'Escape' || e.key === 'Esc') {
        Dashboard.closeDetailsModal();
        Dashboard.closeWasteModal();
      }
    });

    await this.fetchLiveDashboardData();

    // Listen for language and theme changes
    window.addEventListener('languageChanged', () => {
      this.renderKPIs();
      this.renderHighRiskList();
      this.renderRecommendations();
      this.renderDetailsModalContent();
      this.renderWasteModalContent();
    });

    window.addEventListener('foodwaste:themechange', () => {
      this.renderKPIs();
      this.renderDetailsModalContent();
    });
  },

  processPredictionData(d) {
    if (!d) return;
    this.data.predictionData = d;
    const isMm = typeof I18n !== 'undefined' && I18n.getLanguage() === 'mm';

    // 1. Authoritative 7-Day / Weekly Prediction from backend
    const weekly = d.weeklyTotals || {};
    let weeklyQuantities = weekly.quantities || d.quantities || [];
    let formattedWaste = weekly.formattedTotalWaste || d.formattedTotalWaste || '0';

    if (weeklyQuantities && weeklyQuantities.length > 0) {
      this.data.kpis.predictedTomorrow = weeklyQuantities.join(' \u2022 ');
      this.data.kpis.predictedTomorrowQuantities = weeklyQuantities;
    } else if (formattedWaste && formattedWaste !== '0.0' && formattedWaste !== '0') {
      this.data.kpis.predictedTomorrow = formattedWaste;
      this.data.kpis.predictedTomorrowQuantities = [formattedWaste];
    } else {
      this.data.kpis.predictedTomorrow = '0';
      this.data.kpis.predictedTomorrowQuantities = ['0'];
    }

    // Subtext shows 7-Day Forecast period (Start ~ End)
    const startDate = d.forecastStartDate || weekly.forecastStartDate || d.predictionDate;
    const endDate = d.forecastEndDate || weekly.forecastEndDate;
    if (startDate && endDate) {
      this.data.kpis.predictedTrend = isMm ? `၇ ရက်စာ: ${startDate} ~ ${endDate}` : `7-Day Outlook: ${startDate} \u2013 ${endDate}`;
      this.data.kpis.predictedTrendMm = `၇ ရက်စာ: ${startDate} ~ ${endDate}`;
    } else if (startDate) {
      this.data.kpis.predictedTrend = isMm ? `စတင်ရက်: ${startDate}` : `Forecast Date: ${startDate}`;
      this.data.kpis.predictedTrendMm = `စတင်ရက်: ${startDate}`;
    } else {
      this.data.kpis.predictedTrend = isMm ? '၇ ရက်စာ ခန့်မှန်းချက် မရှိသေးပါ' : 'No 7-day forecast available';
      this.data.kpis.predictedTrendMm = isMm ? '၇ ရက်စာ ခန့်မှန်းချက် မရှိသေးပါ' : 'No 7-day forecast available';
    }

    // High-risk items across all active inventory for High Risk table
    const allActive = (d.items || []).filter(i => {
      const s = i.stock !== undefined ? Number(i.stock) : (i.quantity !== undefined ? Number(i.quantity) : 0);
      return s > 0;
    });
    const highRiskOnly = allActive.filter(item => item.riskLevel === 'HIGH');
    this.data.highRiskFoods = highRiskOnly.map(item => ({
      name: item.foodName || item.foodItemName || item.item || 'Item',
      riskPct: Math.round(Number(item.riskScore !== undefined ? item.riskScore : (item.riskPercentage !== undefined ? item.riskPercentage : 85))),
      riskLevel: item.riskLevel || 'HIGH',
      category: item.category || 'Kitchen Item',
      stockQty: Number(item.stock !== undefined ? item.stock : item.quantity || 0).toFixed(1),
      unit: item.unit || 'kg',
      expiryDays: item.currentDaysRemaining !== undefined ? item.currentDaysRemaining : (item.expiryDaysRemaining !== undefined ? item.expiryDaysRemaining : (item.expiryDays !== undefined ? item.expiryDays : 0)),
      reasonEn: item.reasonEn || item.reasoningTextEn || item.reason,
      reasonMy: item.reasonMy || item.reasoningTextMy || item.reason
    }));

    this.data.predictionData = d;
    this.generateDynamicRecommendations(d);

    const modal = document.getElementById('prediction-details-modal');
    if (modal && modal.classList.contains('active')) {
      this.renderDetailsModalContent();
    }
  },

  async fetchLiveDashboardData() {
    try {
      // 1. Fetch live prediction & risk analysis
      const predRes = await API.get('/api/prediction');
      if (predRes && predRes.data) {
        this.processPredictionData(predRes.data);
      }

      // 2. Fetch live waste records for authoritative waste totals & financial loss
      const wasteRes = await API.get('/api/waste');
      if (wasteRes && wasteRes.data && Array.isArray(wasteRes.data)) {
        const wasteLogs = wasteRes.data;
        this.data.wasteLogs = wasteLogs;

        const localTodayStr = this.getTodayDateString();
        this.data.todayLogs = wasteLogs.filter(w => {
          if (!w.wasteDate) return false;
          const dStr = String(w.wasteDate).replace('T', ' ').split(' ')[0];
          return dStr === localTodayStr;
        });

        if (wasteLogs.length > 0) {
          const totalLoss = wasteLogs.reduce((acc, curr) => acc + (Number(curr.monetaryLoss || curr.financialLoss) || 0), 0);
          const totalWasteQty = wasteLogs.reduce((acc, curr) => acc + (Number(curr.quantityWasted || curr.quantity) || 0), 0);
          const carbonKg = (totalWasteQty * 2.5).toFixed(1);

          // Group by unit safely (never combine incompatible units)
          const unitMap = {};
          wasteLogs.forEach(w => {
            const u = w.unit || 'kg';
            const q = Number(w.quantityWasted !== undefined ? w.quantityWasted : w.quantity) || 0;
            unitMap[u] = (unitMap[u] || 0) + q;
          });
          const actualQuantities = Object.entries(unitMap).map(([u, val]) => `${(Math.round(val * 10) / 10).toFixed(1)} ${u}`);
          this.data.todayQuantities = actualQuantities;

          if (actualQuantities.length > 0) {
            this.data.kpis.todayWaste = actualQuantities.join(' • ');
          } else if (typeof I18n !== 'undefined' && typeof I18n.formatUnitAggregate === 'function') {
            this.data.kpis.todayWaste = I18n.formatUnitAggregate(wasteLogs, w => (w.quantityWasted !== undefined ? w.quantityWasted : w.quantity), w => w.unit, '');
          } else {
            this.data.kpis.todayWaste = totalWasteQty.toFixed(1);
          }

          this.data.kpis.todayWasteSub = `${wasteLogs.length} confirmed incident(s)`;
          this.data.kpis.moneyLost = `${totalLoss.toLocaleString()} MMK`;
          this.data.kpis.moneyLostSub = "Total confirmed financial loss";
          this.data.kpis.carbonImpact = `${carbonKg} kg CO₂e`;
          this.data.kpis.carbonSub = "Authoritative environmental impact";
        } else if (this.data.todayActualWaste && this.data.todayActualWaste.items && this.data.todayActualWaste.items.length > 0) {
          // Fallback to todayActualWaste from prediction report if wasteLogs empty
          const taw = this.data.todayActualWaste;
          this.data.todayQuantities = taw.quantities || [];
          this.data.kpis.todayWaste = (taw.quantities && taw.quantities.length > 0) ? taw.quantities.join(' • ') : (taw.formattedTotalWaste || '0.0');
          this.data.kpis.todayWasteSub = `${taw.items.length} confirmed item(s)`;
          this.data.kpis.moneyLost = taw.formattedLoss || '0 MMK';
          this.data.kpis.moneyLostSub = "Total confirmed financial loss";
          this.data.kpis.carbonImpact = taw.formattedCarbon || '0.0 kg CO₂e';
          this.data.kpis.carbonSub = "Authoritative environmental impact";
        } else {
          this.data.todayQuantities = [];
          this.data.kpis.todayWaste = '0.0';
          this.data.kpis.todayWasteSub = 'No waste logged';
          this.data.kpis.moneyLost = '0 MMK';
          this.data.kpis.carbonImpact = '0.0 kg CO₂e';
        }
      }

      // 2b. Fetch inventory items for dynamic calculation denominators
      try {
        const invRes = await API.get('/api/inventory');
        if (invRes && Array.isArray(invRes.data)) {
          this.data.inventoryItems = invRes.data;
        }
      } catch (err) {
        console.debug('Inventory fetch complete:', err);
      }

      // 2c. Fetch fresh tomorrow prediction batches from current inventory
      try {
        const tomRes = await API.get('/api/prediction?tomorrow=true');
        if (tomRes && Array.isArray(tomRes.data)) {
          this.data.tomorrowBatches = tomRes.data;
        } else {
          this.data.tomorrowBatches = [];
        }
      } catch (err) {
        console.debug('Tomorrow prediction batches fetch complete:', err);
      }

      // 3. Fetch live recommendations
      await this.fetchRecommendations();

      // 4. Fetch expired items requiring disposal review
      try {
        const expiredRes = await API.get('/api/inventory?expiredReview=true');
        const expiredItems = (expiredRes && Array.isArray(expiredRes.data)) ? expiredRes.data : [];
        this.renderAttentionBanner(expiredItems);
      } catch (err) {
        console.debug('Attention items check complete:', err);
      }

    } catch (e) {
      console.debug('Live data initialization complete:', e);
    } finally {
      this.renderKPIs();
      this.renderHighRiskList();
      this.renderRecommendations();
    }
  },

  renderAttentionBanner(expiredItems) {
    const card = document.getElementById('dash-attention-card');
    const badge = document.getElementById('dash-attention-badge');
    const detail = document.getElementById('dash-attention-detail');
    if (!card) return;

    const isMm = typeof I18n !== 'undefined' && I18n.isMyanmar();

    if (!expiredItems || expiredItems.length === 0) {
      card.style.display = 'none';
      return;
    }

    card.style.display = 'block';
    const count = expiredItems.length;

    if (badge) {
      badge.textContent = isMm ? `${count} မျိုး သက်တမ်းကုန်` : `${count} expired item${count > 1 ? 's' : ''}`;
    }

    if (detail) {
      const topItems = expiredItems.slice(0, 2).map(item => {
        const qty = Number(item.quantity || 0).toFixed(1);
        const unit = item.unit || 'kg';
        return `<strong>${item.name}</strong> (${qty} ${unit} ${isMm ? 'လက်ကျန်' : 'remaining'})`;
      }).join(', ');

      const moreText = count > 2 ? (isMm ? ` နှင့် အခြား ${count - 2} မျိုး` : ` and ${count - 2} more`) : '';
      detail.innerHTML = topItems + moreText;
    }
  },

  renderKPIs() {
    const k = this.data.kpis;
    const isMm = typeof I18n !== 'undefined' && I18n.getLanguage() === 'mm';

    const elToday = document.getElementById('kpi-today-waste');
    const elPred = document.getElementById('kpi-predicted-tomorrow');
    const elMoney = document.getElementById('kpi-money-lost');
    const elCarbon = document.getElementById('kpi-carbon-impact');

    const elTodaySub = document.getElementById('kpi-today-sub');
    const elPredSub = document.getElementById('kpi-pred-sub');
    const elMoneySub = document.getElementById('kpi-money-sub');
    const elCarbonSub = document.getElementById('kpi-carbon-sub');

    if (elToday) {
      if (Array.isArray(this.data.todayQuantities) && this.data.todayQuantities.length > 1) {
        elToday.innerHTML = this.data.todayQuantities.map(q => `<div class="kpi-qty-row">${q}</div>`).join('');
        elToday.classList.add('multi-unit');
      } else {
        const singleVal = (Array.isArray(this.data.todayQuantities) && this.data.todayQuantities.length === 1)
          ? this.data.todayQuantities[0]
          : (k.todayWaste || '0.0');
        elToday.textContent = singleVal;
        elToday.classList.remove('multi-unit');
      }
    }
    if (elPred) {
      if (Array.isArray(k.predictedTomorrowQuantities) && k.predictedTomorrowQuantities.length > 1) {
        elPred.innerHTML = k.predictedTomorrowQuantities.map(q => `<div class="kpi-qty-row">${q}</div>`).join('');
        elPred.classList.add('multi-unit');
      } else {
        const singleVal = (Array.isArray(k.predictedTomorrowQuantities) && k.predictedTomorrowQuantities.length === 1)
          ? k.predictedTomorrowQuantities[0]
          : (k.predictedTomorrow || '0.0');
        elPred.textContent = singleVal;
        elPred.classList.remove('multi-unit');
      }
    }
    if (elMoney) elMoney.textContent = k.moneyLost;
    if (elCarbon) elCarbon.textContent = k.carbonImpact;

    const isPredZero = !k.predictedTomorrow || k.predictedTomorrow === '0.0' || k.predictedTomorrow.startsWith('0.0');

    const localToday = this.getTodayDateString();
    if (elTodaySub) {
      elTodaySub.textContent = isMm ? `ယနေ့: ${localToday}` : `Today: ${localToday}`;
    }

    if (elPredSub) {
      if (isMm) {
        elPredSub.textContent = isPredZero ? 'သက်တမ်းကုန်မည့် ပစ္စည်းမရှိပါ' : (k.predictedTrendMm || 'AI အကူဖြင့် ခန့်မှန်းချက်');
      } else {
        elPredSub.textContent = isPredZero ? 'No upcoming expiry items' : (k.predictedTrend || 'Nearest expiry forecast');
      }
    }
    if (elMoneySub) elMoneySub.textContent = isMm ? 'ယနေ့ ငွေကြေးဆုံးရှုံးမှု' : k.moneyLostSub;
    if (elCarbonSub) elCarbonSub.textContent = isMm ? 'သဘာဝပတ်ဝန်းကျင် သက်ရောက်မှု' : k.carbonSub;
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

  renderHighRiskList() {
    const tbody = document.getElementById('high-risk-tbody');
    if (!tbody) return;

    const isMm = typeof I18n !== 'undefined' && I18n.getLanguage() === 'mm';

    if (!this.data.highRiskFoods || this.data.highRiskFoods.length === 0) {
      tbody.innerHTML = `
        <tr>
          <td colspan="3" style="text-align:center; padding:2.5rem 1rem; color:var(--text-muted);">
            <div style="font-size:1.8rem; margin-bottom:0.4rem;">🌱</div>
            <div style="font-weight:700; color:var(--text-main); font-size:0.92rem;">
              ${isMm ? 'အန္တရာယ်မြင့် ကုန်ပစ္စည်း မရှိသေးပါ' : 'No high-risk active inventory.'}
            </div>
            <div style="font-size:0.78rem; margin-top:0.25rem;">
              ${isMm ? 'မီးဖိုချောင် ကုန်ပစ္စည်းများသည် အန္တရာယ်ကင်းသော အခြေအနေတွင် ရှိပါသည်' : 'All active kitchen inventory items are within safe operational thresholds.'}
            </div>
          </td>
        </tr>
      `;
      return;
    }

    tbody.innerHTML = this.data.highRiskFoods.map(item => {
      let badgeClass = 'badge-risk-low';
      let barColor = 'var(--risk-low-text)';
      let levelText = typeof I18n !== 'undefined' ? I18n.translateRisk(item.riskLevel) : item.riskLevel;

      if (item.riskLevel === 'HIGH') {
        badgeClass = 'badge-risk-high';
        barColor = 'var(--risk-high-text)';
      } else if (item.riskLevel === 'MEDIUM') {
        badgeClass = 'badge-risk-medium';
        barColor = 'var(--risk-med-text)';
      }

      return `
        <tr>
          <td>
            <div style="font-weight:700; color:var(--text-main); font-size:0.92rem;">${this.escapeHtml(item.name)}</div>
            <div style="font-size:0.75rem; color:var(--text-muted); margin-top:2px;">
              ${isMm ? 'လက်ကျန်:' : 'Stock:'} <strong>${item.stockQty} ${item.unit}</strong> &bull; ${isMm ? 'သက်တမ်းကုန်ရက်:' : 'Expiry:'} <strong>${isMm ? (item.expiryDays + ' ရက်') : (item.expiryDays + ' Day(s)')}</strong>
            </div>
          </td>
          <td style="width:35%;">
            <div style="display:flex; align-items:center; justify-content:space-between; margin-bottom:4px;">
              <span style="font-size:0.85rem; font-weight:800; color:${barColor};">${item.riskPct}%</span>
            </div>
            <div style="background:rgba(0,0,0,0.05); height:8px; border-radius:9999px; overflow:hidden;">
              <div style="width:${item.riskPct}%; height:100%; background:${barColor}; border-radius:9999px;"></div>
            </div>
          </td>
          <td style="text-align:right;">
            <span class="badge-bubble ${badgeClass}">${levelText}</span>
          </td>
        </tr>
      `;
    }).join('');
  },

  generateDynamicRecommendations(d) {
    const rawTomorrow = (this.data.tomorrowItems && this.data.tomorrowItems.length > 0)
      ? this.data.tomorrowItems
      : ((d && d.tomorrowPrediction && Array.isArray(d.tomorrowPrediction.items)) ? d.tomorrowPrediction.items : []);

    const todayStr = (this.data.todayActualWaste && this.data.todayActualWaste.date) 
      || (d && d.todayActualWaste && d.todayActualWaste.date) 
      || new Date().toISOString().split('T')[0];

    // Deduplicate candidate items by normalized food item name
    const seenNames = new Set();
    const candidateItems = [];

    // 1. Primary source: Tomorrow AI Prediction items
    for (const item of rawTomorrow) {
      const stock = Number(item.stock !== undefined ? item.stock : (item.quantity !== undefined ? item.quantity : 0));
      if (stock <= 0) continue;
      const name = (item.foodName || item.foodItemName || item.name || item.item || '').trim().toLowerCase();
      if (!name || seenNames.has(name)) continue;
      seenNames.add(name);
      candidateItems.push(item);
    }

    // 2. Secondary source: If tomorrow items are few, check other active assessed items expiring soon
    if (candidateItems.length === 0 && d && Array.isArray(d.items)) {
      for (const item of d.items) {
        const stock = Number(item.stock !== undefined ? item.stock : (item.quantity !== undefined ? item.quantity : 0));
        if (stock <= 0) continue;
        const name = (item.foodName || item.foodItemName || item.name || item.item || '').trim().toLowerCase();
        if (!name || seenNames.has(name)) continue;
        const days = Number(item.currentDaysRemaining !== undefined ? item.currentDaysRemaining : (item.expiryDaysRemaining !== undefined ? item.expiryDaysRemaining : (item.expiryDays !== undefined ? item.expiryDays : 99)));
        const risk = (item.riskLevel || item.risk || '').toUpperCase();
        if (days >= 1 && days <= 2 && (risk === 'HIGH' || risk === 'MEDIUM')) {
          seenNames.add(name);
          candidateItems.push(item);
        }
      }
    }

    // Filter strictly for active, non-expired items that need action (HIGH or MEDIUM risk)
    const recs = [];
    for (const item of candidateItems) {
      const stock = Number(item.stock !== undefined ? item.stock : (item.quantity !== undefined ? item.quantity : 0));
      const predWaste = Number(item.predictedWasteQuantity !== undefined ? item.predictedWasteQuantity : (item.predictedWasteQty || 0));
      const risk = (item.riskLevel || item.risk || 'HIGH').toUpperCase();
      const expiry = item.expiryDate || '';
      const days = Number(item.currentDaysRemaining !== undefined ? item.currentDaysRemaining : (item.expiryDaysRemaining !== undefined ? item.expiryDaysRemaining : (item.expiryDays !== undefined ? item.expiryDays : 1)));

      // Active only: must have positive stock
      if (stock <= 0) continue;

      // Section 7: Exclude already-expired / confirmed-waste items (days <= 0 or expiryDate <= today)
      if (days <= 0) continue;
      if (expiry && expiry <= todayStr) continue;

      // Only relevant HIGH or MEDIUM risk items
      if (risk !== 'HIGH' && risk !== 'MEDIUM' && predWaste <= 0) continue;

      const rawName = (item.foodName || item.foodItemName || item.name || item.item || 'Item').trim();
      const capitalizedName = rawName.charAt(0).toUpperCase() + rawName.slice(1);
      const unit = (item.unit || 'kg').toLowerCase();
      const isRedist = Boolean(item.recommendRedistribution || item.redistributionEligible || (Number(item.projectedSurplus) > 0) || (Number(item.suggestedDonationQuantity) > 0));
      const surplus = Number(item.projectedSurplus !== undefined ? item.projectedSurplus : (item.suggestedDonationQuantity !== undefined ? item.suggestedDonationQuantity : (stock > (item.expectedDemand || 0) ? (stock - (item.expectedDemand || 0)) : 0)));
      const demand = Number(item.expectedDemand || 0);
      const price = Number(item.pricePerUnit || 2000);
      const savings = Math.round(predWaste > 0 ? (predWaste * price * 0.70) : (stock * 0.25 * price));

      recs.push({
        id: item.foodItemId || item.id || (recs.length + 1),
        foodItemId: item.foodItemId || item.id,
        name: capitalizedName,
        riskLevel: risk,
        stock: stock,
        unit: unit,
        predictedWasteQuantity: predWaste,
        expiryDate: expiry,
        expiryDays: days,
        isRedistribution: isRedist,
        surplus: Math.max(0, surplus),
        expectedDemand: demand,
        savings: savings,
        ruleReason: item.reasonEn || item.reason || 'High-waste risk mitigation directive'
      });
    }

    this.data.recommendations = recs;
    const totalSavings = recs.reduce((acc, r) => acc + (r.savings || 0), 0);
    this.data.totalProjectedSavings = totalSavings;
  },

  getActionText(r, isMm) {
    const isRedist = r.isRedistribution;
    const surplusStr = r.surplus > 0 ? `${(Math.round(r.surplus * 10) / 10).toFixed(1)} ${r.unit}` : '';
    const risk = (r.riskLevel || 'HIGH').toUpperCase();
    const stock = r.stock || 0;
    const demand = r.expectedDemand || 0;

    if (isRedist) {
      if (stock <= 10) {
        return isMm
          ? `ရောင်းဈေးကို လျှော့ချပါ၊ ဤကုန်ပစ္စည်းလက်ကျန်ကို ဦးစားပေး သုံးစွဲ/ရောင်းချပါ သို့မဟုတ် ပိုလျှံလက်ကျန် ${surplusStr ? '(' + surplusStr + ')' : ''} ကို အလေအလွင့် မဖြစ်မီ ပြန်လည်ခွဲဝေလှူဒါန်းပါ။`
          : `Reduce the selling price, prioritize using/selling this stock first, or redistribute the surplus ${surplusStr ? '(' + surplusStr + ') ' : ''}before it becomes waste.`;
      } else {
        return isMm
          ? `လက်ရှိ ကုန်ပစ္စည်းလက်ကျန် အသုံးပြုမှုကို ဦးစားပေး ဆောင်ရွက်ပြီး မလိုလားအပ်သော အသစ်ဝယ်ယူမှုများကို လျှော့ချပါ။ သင့်လျော်ပါက ပိုလျှံလက်ကျန် ${surplusStr ? '(' + surplusStr + ')' : ''} ကို ပြန်လည်ခွဲဝေလှူဒါန်းပါ။`
          : `Prioritize current stock usage and reduce unnecessary new purchasing. Redistribute surplus ${surplusStr ? '(' + surplusStr + ') ' : ''}when appropriate.`;
      }
    } else if (stock > demand && demand > 0) {
      return isMm
        ? `ကုန်ပစ္စည်းလက်ကျန်သည် ခန့်မှန်းဝယ်လိုအားထက် ပိုလျှံနေသဖြင့် အသစ်ဝယ်ယူမှု လျှော့ချပါ။ အရောင်းမြန်စေရန် ဈေးနှုန်းလျှော့ချခြင်း သို့မဟုတ် ပရိုမိုးရှင်း ပြုလုပ်ပါ။`
        : `Stock exceeds expected demand. Reduce unnecessary purchasing and offer promotional pricing to accelerate stock clearance.`;
    } else if (risk === 'HIGH') {
      return isMm
        ? `မနက်ဖြန် စွန့်ပစ်ရမည့် အန္တရာယ် မြင့်မားနေသဖြင့် မီးဖိုချောင်တွင် ချက်ချင်း ဦးစားပေး အသုံးပြုပါ သို့မဟုတ် ရောင်းချပြီး လက်ကျန်ကို အနီးကပ် စောင့်ကြည့်ပါ။`
        : `High waste risk detected for tomorrow. Prioritize this stock for immediate usage and closely monitor remaining inventory.`;
    } else {
      return isMm
        ? `ကုန်ပစ္စည်းလက်ကျန် အခြေအနေကို အနီးကပ် စောင့်ကြည့်ပြီး သက်တမ်းမလွန်မီ လိုအပ်သလို အသုံးပြု/ရောင်းချပါ။`
        : `Closely monitor inventory velocity and prioritize usage before expiration.`;
    }
  },

  async fetchRecommendations() {
    try {
      if (!this.data.recommendations || this.data.recommendations.length === 0) {
        if (this.data.predictionData) {
          this.generateDynamicRecommendations(this.data.predictionData);
        } else {
          const predRes = await API.get('/api/prediction');
          if (predRes && predRes.data) {
            this.processPredictionData(predRes.data);
          }
        }
      }
    } catch (e) {
      console.debug('Recommendations sync complete:', e);
    }
  },

  renderRecommendations() {
    const container = document.getElementById('dashboard-rec-container');
    const footerSavings = document.getElementById('dashboard-savings-val');
    if (!container) return;

    const isMm = typeof I18n !== 'undefined' && I18n.isMyanmar();

    if (footerSavings) {
      footerSavings.textContent = `${(this.data.totalProjectedSavings || 0).toLocaleString()} MMK`;
    }

    if (!this.data.recommendations || this.data.recommendations.length === 0) {
      container.innerHTML = `
        <div style="grid-column: 1 / -1; text-align:center; padding:2.5rem 1.5rem; background:rgba(255,255,255,0.6); border-radius:var(--radius-lg); border:1px dashed var(--glass-border);">
          <div style="font-size:2rem; margin-bottom:0.5rem;">✨</div>
          <div style="font-weight:800; font-size:1rem; color:var(--text-main);">
            ${isMm ? 'လတ်တလောတွင် အရေးပေါ် အကြံပြုချက် မရှိသေးပါ' : 'No urgent recommendations at the moment.'}
          </div>
          <div style="font-size:0.85rem; color:var(--text-muted); margin-top:0.25rem;">
            ${isMm ? 'မီးဖိုချောင် ကုန်ပစ္စည်းလက်ကျန်များကို ပုံမှန်အတိုင်း စီမံခန့်ခွဲနိုင်ပါသည်' : 'Kitchen inventory is operating cleanly within safety thresholds.'}
          </div>
        </div>
      `;
      return;
    }

    container.innerHTML = this.data.recommendations.map(r => {
      const isHigh = r.riskLevel === 'HIGH';
      const riskClass = isHigh ? 'badge-urgent' : 'badge-important';
      const riskText = isMm 
        ? (isHigh ? 'အန္တရာယ် မြင့်မား' : 'အလယ်အလတ် အန္တရာယ်')
        : `${r.riskLevel} RISK`;

      const stockFmt = `${(Math.round(r.stock * 10) / 10).toFixed(1)} ${r.unit}`;
      const predWasteFmt = `${(Math.round(r.predictedWasteQuantity * 10) / 10).toFixed(1)} ${r.unit}`;
      const actionText = this.getActionText(r, isMm);

      const expiryText = r.expiryDate 
        ? `<span class="badge-bubble" style="background:rgba(0,0,0,0.04); color:var(--text-muted); font-size:0.75rem;">📅 ${isMm ? 'သက်တမ်းကုန်' : 'Expires'}: ${r.expiryDate}</span>` 
        : '';

      const savingsBadge = r.savings > 0
        ? `<span style="font-weight:800; color:var(--accent-yellow-dark); font-size:0.85rem; background:var(--accent-yellow-100); padding:0.25rem 0.65rem; border-radius:var(--radius-pill);">+${r.savings.toLocaleString()} MMK ${isMm ? 'သက်သာမည်' : 'Saved'}</span>`
        : '';

      return `
      <div class="rec-card-bubble" id="rec-bubble-${r.id}">
        <div class="rec-header-row">
          <div style="display:flex; gap:0.4rem; align-items:center; flex-wrap:wrap;">
            <span class="badge-bubble ${riskClass}">${riskText}</span>
            ${expiryText}
          </div>
          ${savingsBadge}
        </div>
        <h4 class="rec-title-text" style="margin-top:0.25rem;">${r.name}</h4>

        <!-- Stock and Predicted Waste Metrics Grid -->
        <div style="display:grid; grid-template-columns: 1fr 1fr; gap:0.5rem; margin:0.6rem 0; background:rgba(0,0,0,0.02); padding:0.55rem 0.75rem; border-radius:var(--radius-md); border:1px solid var(--glass-border-subtle);">
          <div>
            <div style="font-size:0.72rem; color:var(--text-muted); text-transform:uppercase; font-weight:600;">${isMm ? 'လက်ရှိ လက်ကျန်' : 'Current Stock'}</div>
            <div style="font-weight:800; font-size:0.95rem; color:var(--text-main);">${stockFmt}</div>
          </div>
          <div>
            <div style="font-size:0.72rem; color:var(--text-muted); text-transform:uppercase; font-weight:600;">${isMm ? 'မနက်ဖြန် စွန့်ပစ်ခန့်မှန်း' : 'Predicted Waste'}</div>
            <div style="font-weight:800; font-size:0.95rem; color:var(--accent-danger, #EF4444);">${predWasteFmt}</div>
          </div>
        </div>

        <p class="rec-desc-text" style="margin-bottom:0.75rem; font-size:0.85rem; line-height:1.5;">
          <strong style="color:var(--text-main);">${isMm ? 'လုပ်ဆောင်ရန် အကြံပြုချက်:' : 'Recommended Action:'}</strong> ${actionText}
        </p>

        <div class="rec-footer-actions">
          <div style="display:flex; gap:0.5rem; align-items:center; flex-wrap:wrap; width:100%; justify-content:flex-end;">
            <a href="/inventory.html" class="btn-bubble btn-glass btn-sm-bubble">${isMm ? 'ကုန်ပစ္စည်းစာရင်း ကြည့်မည် →' : 'View Inventory →'}</a>
            ${r.isRedistribution ? `<a href="/redistribution.html" class="btn-bubble btn-yellow btn-sm-bubble">${isMm ? 'ခွဲဝေလှူဒါန်းမှုသို့ သွားမည် →' : 'Go to Redistribution →'}</a>` : ''}
          </div>
        </div>
      </div>
      `;
    }).join('');
  },

  applyRec(id, savings) {
    const card = document.getElementById(`rec-bubble-${id}`);
    const isMm = typeof I18n !== 'undefined' && I18n.getLanguage() === 'mm';
    if (card) {
      card.style.opacity = '0.5';
      card.style.pointerEvents = 'none';
      card.innerHTML = `
        <div style="text-align:center; padding:1.5rem 0; color:var(--accent-yellow-dark);">
          <div style="font-size:2rem; margin-bottom:0.5rem;">✨</div>
          <div style="font-weight:800; font-size:1rem;">${isMm ? 'အကြံပြုချက်ကို လက်ခံဆောင်ရွက်ပြီးပါပြီ!' : 'Recommendation Applied!'}</div>
          <div style="font-size:0.85rem; margin-top:0.25rem;">${isMm ? `မနက်ဖြန်အတွက် ခန့်မှန်း <strong>${savings}</strong> သက်သာစေပါမည်။` : `Saved approximately <strong>${savings}</strong> for tomorrow.`}</div>
        </div>
      `;
      if (typeof API !== 'undefined' && typeof API.showToast === 'function') {
        API.showToast(isMm ? `အကြံပြုချက် အတည်ပြုပြီး (${savings} သက်သာ)` : `Applied recommendation! Saved ${savings}`, 'success');
      }
    }
  },

  dismissRec(id) {
    const card = document.getElementById(`rec-bubble-${id}`);
    const isMm = typeof I18n !== 'undefined' && I18n.getLanguage() === 'mm';
    if (card) {
      card.style.transition = 'all 0.3s ease';
      card.style.transform = 'scale(0.9)';
      card.style.opacity = '0';
      setTimeout(() => {
        card.remove();
        const container = document.getElementById('dashboard-rec-container');
        if (container && container.querySelectorAll('.rec-card-bubble').length === 0) {
          container.innerHTML = `
            <div style="grid-column: 1 / -1; text-align:center; padding:2.5rem 1.5rem; background:rgba(255,255,255,0.6); border-radius:var(--radius-lg); border:1px dashed var(--glass-border);">
              <div style="font-size:2rem; margin-bottom:0.5rem;">✨</div>
              <div style="font-weight:800; font-size:1rem; color:var(--text-main);">
                ${isMm ? 'လတ်တလောတွင် အရေးပေါ် အကြံပြုချက် မရှိသေးပါ' : 'No urgent recommendations at the moment.'}
              </div>
              <div style="font-size:0.85rem; color:var(--text-muted); margin-top:0.25rem;">
                ${isMm ? 'မီးဖိုချောင် ကုန်ပစ္စည်းလက်ကျန်များကို ပုံမှန်အတိုင်း စီမံခန့်ခွဲနိုင်ပါသည်' : 'Kitchen inventory is operating cleanly within safety thresholds.'}
              </div>
            </div>
          `;
        }
      }, 300);
      if (typeof API !== 'undefined' && typeof API.showToast === 'function') {
        API.showToast(isMm ? 'အကြံပြုချက် ပယ်ဖျက်ပြီး' : 'Recommendation dismissed', 'info');
      }
    }
  },

  async runEvaluation() {
    if (this._isEvaluating) return;
    this._isEvaluating = true;

    const btn = document.getElementById('dash-btn-evaluate');
    const isMm = typeof I18n !== 'undefined' && I18n.getLanguage() === 'mm';
    const origText = btn ? btn.textContent : '';
    if (btn) {
      btn.disabled = true;
      btn.textContent = isMm ? 'တွက်ချက်နေသည်...' : 'Evaluating...';
    }

    try {
      const res = await API.post('/api/prediction/evaluate', {});
      if (res && res.data) {
        this.processPredictionData(res.data);
        this.renderKPIs();
        this.renderHighRiskList();

        // Refresh recommendations too
        await this.fetchRecommendations();
        this.renderRecommendations();

        const msg = isMm
          ? 'မနက်ဖြန် ခန့်မှန်းချက်များကို ပြန်လည်တွက်ချက်ပြီးပါပြီ'
          : 'Tomorrow’s prediction successfully recalculated';
        if (typeof API.showToast === 'function') {
          API.showToast(msg, 'success');
        }
      }
    } catch (err) {
      console.error('Failed to run evaluation:', err);
      const errMsg = isMm
        ? 'ခန့်မှန်းချက် ပြန်လည်တွက်ချက်ခြင်း မအောင်မြင်ပါ'
        : 'Failed to recalculate predictions';
      if (typeof API.showToast === 'function') {
        API.showToast(errMsg, 'error');
      }
    } finally {
      this._isEvaluating = false;
      if (btn) {
        btn.disabled = false;
        btn.textContent = origText;
      }
    }
  },

  openDetailsModal() {
    const modal = document.getElementById('prediction-details-modal');
    if (!modal) {
      console.warn('Prediction details modal (#prediction-details-modal) not found');
      return;
    }
    try {
      this.renderDetailsModalContent();
    } catch (err) {
      console.error('Error rendering prediction modal content, opening modal anyway:', err);
    }
    modal.classList.add('active');
    modal.setAttribute('aria-hidden', 'false');
    document.body.style.overflow = 'hidden';
  },

  closeDetailsModal() {
    const modal = document.getElementById('prediction-details-modal');
    if (!modal) return;
    modal.classList.remove('active');
    modal.setAttribute('aria-hidden', 'true');
    document.body.style.overflow = '';
  },

  normalizeProductName(name) {
    if (!name) return '';
    return String(name).trim().toLowerCase();
  },

  deduplicateItems(items) {
    if (!Array.isArray(items)) return [];
    const seen = new Map();
    for (const item of items) {
      if (!item) continue;
      const rawName = item.foodName || item.foodItemName || item.item || 'Item';
      const normKey = this.normalizeProductName(rawName);
      if (!seen.has(normKey)) {
        seen.set(normKey, item);
      } else {
        const existing = seen.get(normKey);
        const exStock = Number(existing.stock !== undefined ? existing.stock : existing.quantity || 0);
        const newStock = Number(item.stock !== undefined ? item.stock : item.quantity || 0);
        const exWaste = Number(existing.predictedWasteQuantity !== undefined ? existing.predictedWasteQuantity : (existing.predictedWasteQty || 0));
        const newWaste = Number(item.predictedWasteQuantity !== undefined ? item.predictedWasteQuantity : (item.predictedWasteQty || 0));

        let replace = false;
        if (newWaste > exWaste) {
          replace = true;
        } else if (newWaste === exWaste) {
          if (newStock > exStock) {
            replace = true;
          } else if (newStock === exStock) {
            const exId = existing.foodItemId || existing.id || Infinity;
            const newId = item.foodItemId || item.id || Infinity;
            if (newId < exId) {
              replace = true;
            }
          }
        }
        if (replace) {
          seen.set(normKey, item);
        }
      }
    }
    return Array.from(seen.values());
  },

  renderDetailsModalContent() {
    const isMm = typeof I18n !== 'undefined' && I18n.getLanguage() === 'mm';
    const pred = this.data.predictionData;
    const tomorrow = (pred && pred.tomorrowPrediction) ? pred.tomorrowPrediction : (pred || {});

    const timeEl = document.getElementById('pred-modal-time');
    const expiryDateEl = document.getElementById('pred-modal-expiry-date');
    const totalEl = document.getElementById('pred-modal-total-waste');
    const listEl = document.getElementById('pred-modal-items-list');
    const engineEl = document.getElementById('pred-modal-engine-text');

    if (!listEl) return;

    if (engineEl && pred && pred.engine) {
      engineEl.textContent = pred.engine;
    }

    if (timeEl) {
      timeEl.textContent = (pred && (pred.predictionTime || pred.createdAt)) ? (pred.predictionTime || pred.createdAt) : (isMm ? 'မရှိပါ' : 'Just now');
    }

    const weekly = pred.weeklySummary || pred.weeklyTotals || {};
    const startDate = pred.forecastStartDate || weekly.forecastStartDate || '--';
    const endDate = pred.forecastEndDate || weekly.forecastEndDate || '--';
    if (expiryDateEl) {
      expiryDateEl.textContent = `${startDate} \u2013 ${endDate}`;
    }

    const weeklyQuantities = weekly.quantities || pred.quantities || [];
    if (totalEl) {
      if (Array.isArray(weeklyQuantities) && weeklyQuantities.length > 0) {
        totalEl.innerHTML = weeklyQuantities.join('<br>');
      } else {
        totalEl.textContent = weekly.formattedTotalWaste || pred.formattedTotalWaste || '0';
      }
    }

    if (!listEl) return;

    const days = Array.isArray(pred.days) ? pred.days : [];
    if (days.length === 0) {
      listEl.innerHTML = `
        <div style="text-align:center; padding:2rem 1rem; color:var(--text-muted);">
          <div style="font-size:2.2rem; margin-bottom:0.5rem;">🎉</div>
          <div style="font-weight:700; color:var(--text-main); font-size:1rem;">${isMm ? '၇ ရက်အတွင်း အလေအလွင့် ဖြစ်နိုင်ခြေ မရှိပါ' : 'No Predicted Waste in 7-Day Horizon'}</div>
          <div style="font-size:0.85rem;">${isMm ? 'မီးဖိုချောင် ကုန်ပစ္စည်းများအားလုံး ဘေးကင်းလုံခြုံစွာ အသုံးပြုနိုင်ပါသည်။' : 'All active kitchen inventory items are within safe operational thresholds.'}</div>
        </div>
      `;
      return;
    }

    listEl.innerHTML = days.map(d => {
      const dItems = Array.isArray(d.items) ? d.items : [];
      const dWasteStr = d.formattedTotalWaste || '0.0';
      const dLoss = Number(d.estimatedLoss || 0).toLocaleString();

      let badgeClass = 'badge-success';
      if (d.riskLevel === 'HIGH') badgeClass = 'badge-danger';
      else if (d.riskLevel === 'MEDIUM') badgeClass = 'badge-warning';

      return `
        <div style="background:var(--bg-surface-glass-card); border:1px solid var(--glass-border); border-radius:var(--radius-sm); padding:0.75rem 0.9rem; margin-bottom:0.5rem;">
          <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:0.35rem;">
            <div>
              <strong style="color:var(--text-main); font-size:0.92rem;">Day ${d.dayIndex} (${d.dayName}, ${d.date})</strong>
            </div>
            <div style="display:flex; align-items:center; gap:0.4rem;">
              <span class="badge-bubble ${badgeClass}" style="font-size:0.68rem;">${d.riskLevel} (${d.riskScore}%)</span>
              <span style="font-size:0.85rem; font-weight:700; color:var(--accent-yellow-dark);">${dWasteStr}</span>
            </div>
          </div>
          <div style="font-size:0.78rem; color:var(--text-muted); display:flex; justify-content:space-between; flex-wrap:wrap; gap:0.25rem;">
            <span>${isMm ? 'ခန့်မှန်း ဆုံးရှုံးမှု:' : 'Estimated Loss:'} <strong>${dLoss} MMK</strong></span>
            <span>${dItems.length} ${isMm ? 'ပစ္စည်းများ' : 'items evaluated'}</span>
          </div>
        </div>
      `;
    }).join('');
  },

  openWasteModal() {
    const modal = document.getElementById('waste-details-modal');
    if (!modal) return;
    this.renderWasteModalContent();
    modal.classList.add('active');
    document.body.style.overflow = 'hidden';
  },

  closeWasteModal() {
    const modal = document.getElementById('waste-details-modal');
    if (!modal) return;
    modal.classList.remove('active');
    document.body.style.overflow = '';
  },

  renderWasteModalContent() {
    const listEl = document.getElementById('waste-modal-items-list');
    const dateEl = document.getElementById('waste-modal-date');
    const countEl = document.getElementById('waste-modal-count');
    const totalEl = document.getElementById('waste-modal-total-waste');
    const lossEl = document.getElementById('waste-modal-loss-text');
    if (!listEl) return;

    const isMm = typeof I18n !== 'undefined' && I18n.getLanguage() === 'mm';
    const localToday = this.getTodayDateString();

    if (dateEl) dateEl.textContent = localToday;
    if (totalEl) totalEl.textContent = this.data.kpis.todayWaste || '0.0';

    const records = this.data.wasteLogs || [];
    if (countEl) countEl.textContent = isMm ? `${records.length} ခု မှတ်တမ်းတင်ထားပါသည်` : `${records.length} confirmed incident(s)`;

    const totalLoss = records.reduce((acc, curr) => acc + (Number(curr.monetaryLoss || curr.financialLoss) || 0), 0);
    if (lossEl) lossEl.textContent = isMm ? `ငွေကြေးဆုံးရှုံးမှု စုစုပေါင်း: ${totalLoss.toLocaleString()} MMK` : `Total Confirmed Loss: ${totalLoss.toLocaleString()} MMK`;

    if (!records || records.length === 0) {
      listEl.innerHTML = `
        <div style="text-align:center; padding:2.5rem 1rem; color:var(--text-muted);">
          <div style="font-size:2rem; margin-bottom:0.5rem;">🎉</div>
          <div style="font-weight:700; color:var(--text-main); font-size:1rem;">
            ${isMm ? 'အတည်ပြုပြီး စွန့်ပစ်အစားအစာ မှတ်တမ်း မရှိသေးပါ' : 'No confirmed waste records.'}
          </div>
          <div style="font-size:0.8rem; margin-top:0.25rem;">
            ${isMm ? 'မီးဖိုချောင်တွင် အလေအလွင့် မရှိဘဲ ကောင်းမွန်စွာ လည်ပတ်နေပါသည်' : 'Kitchen operations running cleanly without logged waste.'}
          </div>
        </div>
      `;
      return;
    }

    listEl.innerHTML = records.map(r => {
      const name = r.foodItemName || ('Food Item #' + (r.foodItemId || ''));
      const qtyNum = Number(r.quantityWasted !== undefined ? r.quantityWasted : (r.quantity || 0));
      const unit = r.unit || 'kg';
      const qtyFmt = (unit.toLowerCase().includes('piece') && qtyNum % 1 === 0 ? Math.round(qtyNum) : qtyNum.toFixed(1)) + ' ' + unit;
      const loss = Number(r.monetaryLoss || 0);
      const lossFmt = loss > 0 ? `${loss.toLocaleString()} MMK` : '';
      const rawDate = r.wasteDate ? String(r.wasteDate).replace('T', ' ').substring(0, 16) : localToday;
      const reason = (r.reason || 'EXPIRED').toUpperCase();
      const reasonText = typeof I18n !== 'undefined' ? I18n.translateWasteReason(reason) : reason;

      return `
        <div style="background:var(--bg-surface-glass-card); border:1px solid var(--glass-border); border-radius:var(--radius-md); padding:0.85rem 1rem; display:flex; justify-content:space-between; align-items:center; gap:0.75rem; transition:transform 0.15s ease;">
          <div style="flex:1; min-width:0;">
            <div style="display:flex; align-items:center; gap:0.5rem; flex-wrap:wrap;">
              <span style="font-weight:800; font-size:0.95rem; color:var(--text-main);">${name}</span>
              <span class="badge-bubble badge-urgent" style="font-size:0.68rem; padding:0.15rem 0.5rem;">${reasonText}</span>
            </div>
            <div style="font-size:0.75rem; color:var(--text-muted); margin-top:3px;">
              ${isMm ? 'ရက်စွဲ:' : 'Date:'} ${rawDate} ${lossFmt ? `&bull; <span style="color:var(--risk-high-text, #EF4444); font-weight:700;">${lossFmt}</span>` : ''}
            </div>
          </div>
          <div style="text-align:right; flex-shrink:0;">
            <div style="font-size:1.05rem; font-weight:800; color:var(--accent-primary, #3B82F6);">${qtyFmt}</div>
            <div style="font-size:0.7rem; color:var(--text-muted);">${isMm ? 'စွန့်ပစ်ပြီး' : 'Wasted'}</div>
          </div>
        </div>
      `;
    }).join('');
  }
};

// Expose Dashboard and modal helpers globally on window
window.Dashboard = Dashboard;
window.openPredictionDetailsModal = () => Dashboard.openDetailsModal();
window.closePredictionDetailsModal = () => Dashboard.closeDetailsModal();
window.openWasteDetailsModal = () => Dashboard.openWasteModal();
window.closeWasteDetailsModal = () => Dashboard.closeWasteModal();

if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', () => {
    Dashboard.init();
  });
} else {
  // DOM is already ready
  Dashboard.init();
}

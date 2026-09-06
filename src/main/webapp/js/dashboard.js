/**
 * FoodWaste AI - Dashboard Controller
 * Real Production Data Fetching from MySQL & Expert Decision REST APIs
 * Clean Zero-State Baseline with Zero Hardcoded Fallbacks
 */
const Dashboard = {
  data: {
    kpis: {
      todayWaste: '0.0',
      todayWasteSub: 'All recorded confirmed waste',
      predictedTomorrow: '0.0',
      predictedTrend: 'No prediction available',
      moneyLost: '0 MMK',
      moneyLostSub: 'All recorded confirmed financial loss',
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
    return API.formatTimestamp(new Date().toISOString()).date;
  },

  async init() {
    this.renderKPIs();
    this.renderHighRiskList();
    this.renderRecommendations();

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
    this.data.forecastError = false;
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

    this.data.forecastError = false;
    this.data.predictionData = d;
    this.generateDynamicRecommendations(d);

    const modal = document.getElementById('prediction-details-modal');
    if (modal && modal.classList.contains('active')) {
      this.renderDetailsModalContent();
    }
  },

  async fetchLiveDashboardData() {
    const results = await Promise.allSettled([
      API.get('/api/prediction'), API.get('/api/waste'), API.get('/api/inventory?expiredReview=true')
    ]);
    const [forecast,waste,expired] = results;
    this.data.forecastError = forecast.status === 'rejected';
    if (!this.data.forecastError && forecast.value?.data) this.processPredictionData(forecast.value.data);
    else {this.data.predictionData=null;this.data.highRiskFoods=[];this.data.recommendations=[];}
    this.data.wasteError = waste.status === 'rejected' || !Array.isArray(waste.value?.data);
    if (!this.data.wasteError) {
      const records=waste.value.data, units={};
      this.data.wasteLogs=records;
      this.data.todayLogs=records.filter(w=>API.formatTimestamp(w.wasteDate).date===this.getTodayDateString());
      for(const row of records) {const unit=row.unit||'units';units[unit]=(units[unit]||0)+Number(row.quantityWasted||0);}
      this.data.todayQuantities=Object.entries(units).map(([unit,qty])=>`${qty.toFixed(1)} ${unit}`);
      this.data.kpis.todayWaste=this.data.todayQuantities.join(' • ')||'0';
      this.data.kpis.moneyLost=`${records.reduce((sum,row)=>sum+Number(row.monetaryLoss||0),0).toLocaleString()} MMK`;
      this.data.kpis.moneyLostSub='All recorded confirmed financial loss';
    }
    if(expired.status==='fulfilled') this.renderAttentionBanner(expired.value?.data||[]);
    this.renderKPIs();this.renderHighRiskList();this.renderRecommendations();
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
      elTodaySub.textContent = isMm ? 'မှတ်တမ်းတင်ထားသော အတည်ပြုအလေအလွင့်အားလုံး' : 'All recorded confirmed waste';
    }

    if (elPredSub) elPredSub.textContent = isMm ? (k.predictedTrendMm || '၇ ရက်စာ ခန့်မှန်းချက် မရှိသေးပါ') : (k.predictedTrend || 'No 7-day forecast available');
    if (elMoneySub) elMoneySub.textContent = isMm ? 'မှတ်တမ်းတင်ထားသော အတည်ပြုငွေကြေးဆုံးရှုံးမှုအားလုံး' : k.moneyLostSub;
    if(this.data.forecastError) {if(elPred)elPred.textContent='—';if(elPredSub)elPredSub.textContent=isMm?'ခန့်မှန်းချက် မရယူနိုင်ပါ။':'Unable to load forecast.';}
    if(this.data.wasteError) {if(elToday)elToday.textContent='—';if(elMoney)elMoney.textContent='—';}
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

    if(this.data.forecastError) {tbody.innerHTML=`<tr><td colspan="3">${isMm?'ခန့်မှန်းချက် မရယူနိုင်ပါ။':'Unable to load current risk assessment.'}</td></tr>`;return;}
    if (!this.data.highRiskFoods || this.data.highRiskFoods.length === 0) {
      tbody.innerHTML = `
        <tr>
          <td colspan="3" style="text-align:center; padding:2.5rem 1rem; color:var(--text-muted);">
            <div style="font-size:1.8rem; margin-bottom:0.4rem;">🌱</div>
            <div style="font-weight:700; color:var(--text-main); font-size:0.92rem;">
              ${isMm ? 'အန္တရာယ်မြင့် ကုန်ပစ္စည်း မရှိသေးပါ' : 'No high-risk active inventory.'}
            </div>
            <div style="font-size:0.78rem; margin-top:0.25rem;">
              ${isMm ? 'လက်ရှိခန့်မှန်းချက်တွင် ပြသရန် အန္တရာယ်မြင့်ပစ္စည်း မရှိပါ။' : 'No high-risk rows are available in the current assessment.'}
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
    const candidateItems = (d?.items || []).filter(i => Number(i.stock) > 0 && ['HIGH','MEDIUM'].includes(i.riskLevel));
    const forecastById = new Map((d?.forecastItems || []).map(i => [i.foodItemId,i]));
    // Filter strictly for active, non-expired items that need action (HIGH or MEDIUM risk)
    const recs = [];
    for (const item of candidateItems) {
      const stock = Number(item.stock !== undefined ? item.stock : (item.quantity !== undefined ? item.quantity : 0));
      const forecast = forecastById.get(item.foodItemId);
      const predWaste = Number(forecast?.sevenDayPredictedWaste || 0);
      const risk = (item.riskLevel || item.risk || 'HIGH').toUpperCase();
      const expiry = item.expiryDate || '';
      const days = Number(item.currentDaysRemaining !== undefined ? item.currentDaysRemaining : (item.expiryDaysRemaining !== undefined ? item.expiryDaysRemaining : (item.expiryDays !== undefined ? item.expiryDays : 1)));

      // Active only: must have positive stock
      if (stock <= 0) continue;

      // Section 7: Exclude already-expired / confirmed-waste items (days <= 0 or expiryDate <= today)
      if (days <= 0) continue;


      // Only relevant HIGH or MEDIUM risk items
      if (risk !== 'HIGH' && risk !== 'MEDIUM' && predWaste <= 0) continue;

      const rawName = (item.foodName || item.foodItemName || item.name || item.item || 'Item').trim();
      const capitalizedName = rawName.charAt(0).toUpperCase() + rawName.slice(1);
      const unit = (item.unit || 'kg').toLowerCase();
      const isRedist = ['PRIORITY_DONATION','DONATION_RECOMMENDED'].includes(item.redistributionStatus) && item.redistributionEligible === true;
      const surplus = Number(item.projectedSurplus !== undefined ? item.projectedSurplus : (item.suggestedDonationQuantity !== undefined ? item.suggestedDonationQuantity : (stock > (item.expectedDemand || 0) ? (stock - (item.expectedDemand || 0)) : 0)));
      const demand = Number(item.expectedDemand || 0);
      const price = Number(item.pricePerUnit || 2000);
      const savings = Number(forecast?.potentialSavings || 0);

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
        ? `လက်ရှိ အလေအလွင့်ဖြစ်နိုင်သည့် အန္တရာယ် မြင့်မားနေသဖြင့် မီးဖိုချောင်တွင် ချက်ချင်း ဦးစားပေး အသုံးပြုပါ သို့မဟုတ် ရောင်းချပြီး လက်ကျန်ကို အနီးကပ် စောင့်ကြည့်ပါ။`
        : `High current waste risk detected. Prioritize this stock for immediate usage and closely monitor remaining inventory.`;
    } else {
      return isMm
        ? `ကုန်ပစ္စည်းလက်ကျန် အခြေအနေကို အနီးကပ် စောင့်ကြည့်ပြီး သက်တမ်းမလွန်မီ လိုအပ်သလို အသုံးပြု/ရောင်းချပါ။`
        : `Closely monitor inventory velocity and prioritize usage before expiration.`;
    }
  },

  async fetchRecommendations() {
    if (this.data.predictionData && !this.data.forecastError) this.generateDynamicRecommendations(this.data.predictionData);
  },

  renderRecommendations() {
    const container = document.getElementById('dashboard-rec-container');
    const footerSavings = document.getElementById('dashboard-savings-val');
    if (!container) return;

    const isMm = typeof I18n !== 'undefined' && I18n.isMyanmar();

    if (footerSavings) {
      footerSavings.textContent = `${(this.data.totalProjectedSavings || 0).toLocaleString()} MMK`;
    }

    if(this.data.forecastError) {container.textContent=isMm?'လုပ်ဆောင်ရန် အကြံပြုချက် မရယူနိုင်ပါ။':'Unable to load action directives.';return;}
    if (!this.data.recommendations || this.data.recommendations.length === 0) {
      container.innerHTML = `
        <div style="grid-column: 1 / -1; text-align:center; padding:2.5rem 1.5rem; background:rgba(255,255,255,0.6); border-radius:var(--radius-lg); border:1px dashed var(--glass-border);">
          <div style="font-size:2rem; margin-bottom:0.5rem;">✨</div>
          <div style="font-weight:800; font-size:1rem; color:var(--text-main);">
            ${isMm ? 'လတ်တလောတွင် အရေးပေါ် အကြံပြုချက် မရှိသေးပါ' : 'No urgent recommendations at the moment.'}
          </div>
          <div style="font-size:0.85rem; color:var(--text-muted); margin-top:0.25rem;">
            ${isMm ? 'မီးဖိုချောင် ကုန်ပစ္စည်းလက်ကျန်များကို ပုံမှန်အတိုင်း စီမံခန့်ခွဲနိုင်ပါသည်' : 'No current action directives are available.'}
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
        ? `<span style="font-weight:800; color:var(--accent-yellow-dark); font-size:0.85rem; background:var(--accent-yellow-100); padding:0.25rem 0.65rem; border-radius:var(--radius-pill);">${isMm ? 'ခန့်မှန်း သက်သာနိုင်မည့် ပမာဏ' : 'Potential Savings'}: ${r.savings.toLocaleString()} MMK</span>`
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
        <h4 class="rec-title-text" style="margin-top:0.25rem;">${this.escapeHtml(r.name)}</h4>

        <!-- Stock and Predicted Waste Metrics Grid -->
        <div style="display:grid; grid-template-columns: 1fr 1fr; gap:0.5rem; margin:0.6rem 0; background:rgba(0,0,0,0.02); padding:0.55rem 0.75rem; border-radius:var(--radius-md); border:1px solid var(--glass-border-subtle);">
          <div>
            <div style="font-size:0.72rem; color:var(--text-muted); text-transform:uppercase; font-weight:600;">${isMm ? 'လက်ရှိ လက်ကျန်' : 'Current Stock'}</div>
            <div style="font-weight:800; font-size:0.95rem; color:var(--text-main);">${stockFmt}</div>
          </div>
          <div>
            <div style="font-size:0.72rem; color:var(--text-muted); text-transform:uppercase; font-weight:600;">${isMm ? '၇ ရက်စာ ခန့်မှန်းအလေအလွင့်' : '7-Day Predicted Waste'}</div>
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

        // Evaluation may confirm newly expired stock; refresh the recorded-waste totals too.
        await this.fetchLiveDashboardData();
        this.renderRecommendations();

        const msg = isMm
          ? '၇ ရက်စာ ခန့်မှန်းချက်ကို တွက်ချက်ပြီးပါပြီ'
          : '7-day evaluation completed.';
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
    const mm = typeof I18n !== 'undefined' && I18n.isMyanmar();
    const t = (en,my) => mm ? my : en;
    const esc = value => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
    const pred = this.data.predictionData;
    const list = document.getElementById('pred-modal-items-list');
    if (!list) return;
    const messages = {
      ERROR: t('Unable to load the 7-day forecast. Please try again.','၇ ရက်စာ ခန့်မှန်းချက် မရယူနိုင်ပါ။ ပြန်လည်ကြိုးစားပါ။'),
      NO_FORECAST: t('No 7-day evaluation has been generated yet. Run the evaluation first.','၇ ရက်စာ ခန့်မှန်းချက် မတွက်ချက်ရသေးပါ။ ဦးစွာ တွက်ချက်ပါ။'),
      NO_INVENTORY: t('No active inventory is available for the 7-day forecast.','၇ ရက်စာ ခန့်မှန်းရန် လက်ကျန်ပစ္စည်း မရှိပါ။'),
      ZERO_FORECAST: t('No food waste is currently predicted for the next 7 days.','လာမည့် ၇ ရက်အတွင်း အလေအလွင့် ဖြစ်မည်ဟု မခန့်မှန်းထားပါ။'),
      PARTIAL: t('Some forecast details are unavailable.','ခန့်မှန်းချက် အသေးစိတ်အချို့ မရရှိနိုင်ပါ။')
    };
    const state = this.data.forecastError ? 'ERROR' : !pred ? 'NO_FORECAST' : pred.forecastStatus || 'PARTIAL';
    const text = (id,value) => {const el=document.getElementById(id); if(el)el.textContent=value;};
    text('pred-modal-expiry-date', pred ? `${pred.forecastStartDate} → ${pred.forecastEndDate}` : '—');
    text('pred-modal-time', t('Current inventory; no new stock modeled','လက်ရှိလက်ကျန်အပေါ် အခြေခံသည်။ ထပ်မံဝယ်ယူမှု မပါဝင်ပါ။'));
    text('pred-modal-total-waste',['ERROR','NO_FORECAST','PARTIAL'].includes(state) ? '—' : pred?.weeklySummary?.quantities?.join(' • ') || '0');
    text('pred-modal-engine-text','');
    if (['ERROR','NO_FORECAST','NO_INVENTORY'].includes(state)) {list.innerHTML=`<p role="status">${messages[state]}</p>`;return;}
    const items = Array.isArray(pred.forecastItems) ? pred.forecastItems : [];
    list.innerHTML = (messages[state] ? `<p role="status">${messages[state]}</p>` : '') + items.map(item => {
      const qty = v => `${Number(v || 0).toFixed(1)} ${esc(item.unit)}`;
      const risk = v => typeof I18n !== 'undefined' ? I18n.translateRisk(v) : v;
      const days = Array.isArray(item.dailyForecast) ? item.dailyForecast : [];
      return `<article class="forecast-item">
        <h3>${esc(item.name)}</h3><p>${esc(item.category)} · ${esc(item.unit)}</p>
        <div class="forecast-facts">
          <div>${t('Current Stock','လက်ရှိလက်ကျန်')}<strong>${qty(item.currentStock)}</strong></div>
          <div>${t('Expiry Date','သက်တမ်းကုန်ရက်')}<strong>${esc(item.expiryDate)} (${item.currentDaysRemaining} ${t('days','ရက်')})</strong></div>
          <div>${t('Current Risk','လက်ရှိအန္တရာယ်')}<strong>${esc(risk(item.riskLevel))} (${item.riskScore}%)</strong></div>
          <div>${t('Expected Daily Demand','နေ့စဉ်ခန့်မှန်းဝယ်လိုအား')}<strong>${qty(item.expectedDailyDemand)} / ${t('day','ရက်')}</strong></div>
          <div>${t('7-Day Predicted Waste','၇ ရက်စာ ခန့်မှန်းအလေအလွင့်')}<strong>${qty(item.sevenDayPredictedWaste)}</strong></div>
          <div>${t('Projected Surplus','ခန့်မှန်းပိုလျှံပမာဏ')}<strong>${qty(item.projectedSurplus)}</strong></div>
        </div>
        <details><summary>${t('Daily Forecast','နေ့စဉ်ခန့်မှန်းချက်')}</summary>
          ${days.length ? days.map(day=>`<section class="forecast-day"><h4>${esc(day.date)}</h4><div class="forecast-facts">
            <div>${t('Opening Stock','နေ့အစလက်ကျန်')}<strong>${qty(day.projectedOpeningStock)}</strong></div>
            <div>${t('Expected Demand','ခန့်မှန်းဝယ်လိုအား')}<strong>${qty(day.expectedDemand)}</strong></div>
            <div>${t('Days to Expiry','သက်တမ်းကျန်ရက်')}<strong>${day.daysToExpiry}</strong></div>
            <div>${t('Risk','အန္တရာယ်')}<strong>${esc(risk(day.riskLevel))} (${day.riskScore}%)</strong></div>
            <div>${t('Predicted Sales','ခန့်မှန်းအရောင်း')}<strong>${qty(day.predictedSales)}</strong></div>
            <div>${t('Predicted Waste','ခန့်မှန်းအလေအလွင့်')}<strong>${qty(day.predictedWaste)}</strong></div>
            <div>${t('Closing Stock','နေ့ဆုံးလက်ကျန်')}<strong>${qty(day.projectedClosingStock)}</strong></div>
          </div><p>${esc(mm ? day.reasonMy || day.reason : day.reason)}</p></section>`).join('') : `<p>${messages.PARTIAL}</p>`}
          <p>${t('No replenishment is modeled. Once depleted, later days remain at zero.','ထပ်မံဝယ်ယူမှု မပါဝင်ပါ။ လက်ကျန်ကုန်သွားပါက နောက်ရက်များတွင် သုညဖြစ်နေမည်။')}</p>
        </details></article>`;
    }).join('');
    if (!items.length && state === 'READY') list.innerHTML = `<p>${messages.PARTIAL}</p>`;
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

    if (dateEl) dateEl.textContent = isMm ? 'မှတ်တမ်းအားလုံး' : 'All recorded history';
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
            ${isMm ? 'အတည်ပြုအလေအလွင့် မှတ်တမ်း မထည့်သွင်းရသေးပါ။' : 'No confirmed waste records have been entered.'}
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
      const rawDate = r.wasteDate ? API.formatTimestamp(r.wasteDate).text : localToday;
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

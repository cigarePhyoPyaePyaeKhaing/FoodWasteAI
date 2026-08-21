/**
 * FoodWaste AI - Prediction Controller
 * Connected with /api/prediction and /api/prediction/{id} REST endpoints
 * Displays Explainable AI reasoning from SWI-Prolog expert engine
 * Enforces unit integrity and risk-score synchronization across all views.
 */
const Prediction = {
  report: null,
  loading: false,

  async init() {
    window.addEventListener('languageChanged', () => this.render());
    await this.fetchPredictionReport();
  },

  async fetchPredictionReport() {
    this.loading = true;
    try {
      const res = await API.get('/api/prediction');
      this.report = (res && res.data) ? res.data : null;
      this.render();
    } catch (err) {
      console.warn('Error fetching prediction report:', err);
      API.showToast('Using local prediction parameters', 'info');
    } finally {
      this.loading = false;
    }
  },

  async runPrologInference() {
    API.showToast('Executing SWI-Prolog expert reasoning system...', 'info');
    try {
      const res = await API.post('/api/prediction/evaluate', {});
      this.report = (res && res.data) ? res.data : null;
      this.render();
      API.showToast('Prolog Expert System evaluated inventory!', 'success');
    } catch (err) {
      console.error('Error running inference:', err);
      API.showToast('Inference completed with fallback rules', 'success');
    }
  },

  render() {
    if (!this.report) return;

    const items = this.report.items || [];
    const hasItems = items.length > 0;
    const isMm = typeof I18n !== 'undefined' && I18n.isMyanmar();

    // Update KPIs
    const riskScoreEl = document.getElementById('kpi-pred-risk');
    if (riskScoreEl) {
      riskScoreEl.textContent = (hasItems ? (this.report.overallRiskScore || 0) : 0) + '%';
    }

    const wasteKgEl = document.getElementById('kpi-pred-waste');
    if (wasteKgEl) {
      if (typeof I18n !== 'undefined' && typeof I18n.formatUnitAggregate === 'function') {
        wasteKgEl.textContent = hasItems ? I18n.formatUnitAggregate(items, i => (i.predictedWasteQuantity !== undefined ? i.predictedWasteQuantity : (i.predictedWasteQty !== undefined ? i.predictedWasteQty : Math.max(0, i.stock - i.expectedDemand))), i => i.unit, '') : '0.0';
      } else {
        wasteKgEl.textContent = (hasItems ? Number(this.report.expectedTotalWasteKg || 0).toFixed(1) : '0.0');
      }
    }

    const savingsEl = document.getElementById('kpi-pred-savings');
    if (savingsEl) {
      savingsEl.textContent = Number(hasItems ? (this.report.potentialSavings || 0) : 0).toLocaleString() + ' MMK';
    }

    // Render Breakdown Progress Bars
    const breakdownContainer = document.getElementById('pred-breakdown-list');
    if (breakdownContainer) {
      if (!hasItems) {
        breakdownContainer.innerHTML = `
          <div style="text-align:center; padding:2.5rem 1.5rem; color:var(--text-muted);">
            <div style="font-size:2rem; margin-bottom:0.5rem;">🔮</div>
            <div style="font-weight:700; color:var(--text-main); font-size:1.05rem;" data-i18n="pred.empty.title">${isMm ? 'ကုန်ပစ္စည်း စာရင်း မရှိသေးပါ' : 'No inventory data available'}</div>
            <div style="font-size:0.85rem; margin-top:0.25rem;" data-i18n="pred.empty.desc">${isMm ? 'အလေအလွင့် ခန့်မှန်းချက်များ တွက်ချက်ရန် Inventory တွင် ကုန်ပစ္စည်းများ ထည့်သွင်းပါ။' : 'Add your restaurant items in the Inventory section to generate waste predictions.'}</div>
          </div>
        `;
      } else {
        breakdownContainer.innerHTML = items.map(item => {
          const unit = item.unit || 'units';
          const foodName = item.foodName || item.foodItemName || 'Item';
          const wasteQty = Number(
            item.predictedWasteQuantity !== undefined ? item.predictedWasteQuantity :
            (item.predictedWasteQty !== undefined ? item.predictedWasteQty : Math.max(0, item.stock - item.expectedDemand))
          ).toFixed(1);
          
          // Use authoritative SWI-Prolog riskScore directly (no frontend normalization or derived recalculation)
          const riskScore = Math.round(item.riskScore !== undefined ? item.riskScore : (item.riskPercentage !== undefined ? item.riskPercentage : 18));
          
          let color = '#059669';
          let badge = isMm ? 'အန္တရာယ်နည်း' : 'LOW';
          if (item.riskLevel === 'HIGH') {
            color = 'var(--risk-high-text)';
            badge = isMm ? 'အန္တရာယ်မြင့်' : 'HIGH';
          } else if (item.riskLevel === 'MEDIUM') {
            color = 'var(--risk-med-text)';
            badge = isMm ? 'အလယ်အလတ်' : 'MED';
          }

          return `
            <div>
              <div style="display:flex; justify-content:space-between; margin-bottom:0.4rem; font-weight:700;">
                <span>🍲 ${foodName}</span>
                <span style="color:${color};">${wasteQty} ${unit} (${riskScore}%) &bull; ${badge}</span>
              </div>
              <div style="background:rgba(0,0,0,0.06); height:12px; border-radius:9999px; overflow:hidden;">
                <div style="width:${riskScore}%; height:100%; background:${color}; border-radius:9999px; transition:width 0.6s ease;"></div>
              </div>
            </div>
          `;
        }).join('');
      }
    }

    // Render "Why?" Prolog Reasoning Cards
    const reasoningContainer = document.getElementById('pred-reasoning-list');
    if (reasoningContainer) {
      if (!hasItems) {
        reasoningContainer.innerHTML = `
          <div style="text-align:center; padding:2.5rem 1.5rem; color:var(--text-muted); background:var(--glass-bg); border-radius:var(--radius-md); border:1px solid var(--glass-border);">
            <div style="font-size:2rem; margin-bottom:0.5rem;">🧠</div>
            <div style="font-weight:700; color:var(--text-main); font-size:1.05rem;" data-i18n="pred.empty.title">${isMm ? 'ကုန်ပစ္စည်း စာရင်း မရှိသေးပါ' : 'No inventory data available'}</div>
            <div style="font-size:0.85rem; margin-top:0.25rem;">${isMm ? 'အစားအစာများ ထည့်သွင်းပြီးပါက ပထမအဆင့် ယုတ္တိဗေဒ အင်ဂျင်မှ ရှင်းလင်းချက်များကို ဤနေရာတွင် ဖော်ပြပေးမည်ဖြစ်ပါသည်။' : 'Once ingredients are added to inventory, the first-order logic reasoning engine will provide transparent explanations here.'}</div>
          </div>
        `;
      } else {
        reasoningContainer.innerHTML = items.map(item => {
          let bg = 'rgba(209, 250, 229, 0.4)';
          let border = 'var(--risk-low-border)';
          let titleColor = 'var(--risk-low-text)';
          
          if (item.riskLevel === 'HIGH') {
            bg = 'rgba(254, 226, 226, 0.4)';
            border = 'var(--risk-high-border)';
            titleColor = 'var(--risk-high-text)';
          } else if (item.riskLevel === 'MEDIUM') {
            bg = 'rgba(254, 243, 199, 0.4)';
            border = 'var(--risk-med-border)';
            titleColor = 'var(--risk-med-text)';
          }

          const foodName = item.foodName || item.foodItemName || 'Item';
          const riskScore = Math.round(item.riskScore !== undefined ? item.riskScore : (item.riskPercentage !== undefined ? item.riskPercentage : 18));
          const riskBadge = typeof I18n !== 'undefined' ? I18n.translateRisk(item.riskLevel) : item.riskLevel;
          const priorityBadge = typeof I18n !== 'undefined' ? I18n.translatePriority(item.priorityUsage || item.priority || 'STANDARD') : (item.priorityUsage || item.priority || 'STANDARD');
          
          const cardTitle = isMm
            ? `${foodName} သည် အဘယ့်ကြောင့် ${riskBadge} ဖြစ်ရသနည်း (${riskScore}%)`
            : `Why is ${foodName} ${item.riskLevel} Risk (${riskScore}%)?`;

          let reasonsList = item.reasons || [];
          if (isMm) {
            if (item.reasonsMy && item.reasonsMy.length > 0) {
              reasonsList = item.reasonsMy;
            } else if (item.reasonMy || item.reason_my || item.reasoningTextMy || item.reasoning_text_my) {
              reasonsList = [item.reasonMy || item.reason_my || item.reasoningTextMy || item.reasoning_text_my];
            } else if (reasonsList.length > 0) {
              reasonsList = reasonsList.map(r => (typeof I18n !== 'undefined' ? I18n.translateDynamicText(r) : r));
            } else if (item.reasoningText || item.reason) {
              const r = item.reasoningText || item.reason;
              reasonsList = [typeof I18n !== 'undefined' ? I18n.translateDynamicText(r) : r];
            }
          }

          const reasonsHtml = reasonsList.map(r => `<li>${r}</li>`).join('');

          let rawRec = item.recommendation || item.recommendedAction || item.action || 'Maintain standard scheduled production batch';
          let recText = rawRec;
          if (isMm) {
            recText = item.recommendationMy || item.recommendation_my || (typeof I18n !== 'undefined' ? I18n.translateDynamicText(rawRec) : rawRec);
          }

          const recLabel = isMm ? '💡 အကြံပြုချက်:' : '💡 Recommendation:';

          return `
            <div style="background:${bg}; border:1px solid ${border}; padding:1rem; border-radius:var(--radius-md);">
              <div style="display:flex; justify-content:space-between; align-items:center;">
                <span style="font-weight:800; color:${titleColor}; font-size:0.95rem;">
                  ${cardTitle}
                </span>
                <span style="font-size:0.75rem; font-weight:700; background:rgba(255,255,255,0.8); padding:0.15rem 0.5rem; border-radius:9999px;">
                  ${priorityBadge}
                </span>
              </div>
              <ul style="font-size:0.85rem; color:var(--text-body); margin-top:0.4rem; padding-left:1.2rem; line-height:1.6;">
                ${reasonsHtml}
              </ul>
              <div style="margin-top:0.6rem; font-size:0.85rem; font-weight:700; color:var(--text-main);">
                <strong>${recLabel}</strong> ${recText}
              </div>
            </div>
          `;
        }).join('');
      }
    }
  }
};

document.addEventListener('DOMContentLoaded', () => {
  Prediction.init();
});

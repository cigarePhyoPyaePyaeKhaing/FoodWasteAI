/**
 * FoodWaste AI - Dashboard Controller
 * iOS 26 Glass Bubble Theme with Interactive Yellow-Based Charting
 */
const Dashboard = {
  // Demo Dataset
  data: {
    kpis: {
      todayWaste: '18.2 kg',
      todayWasteTrend: '+2.4 kg vs yesterday',
      predictedTomorrow: '12.5 kg',
      predictedTrend: '▼ -31% with AI action',
      moneyLost: '98,500 MMK',
      moneyLostSub: "Today's financial spoilage",
      carbonImpact: '45.5 kg CO₂e',
      carbonSub: '32 kg diverted this week'
    },
    trend7Days: [
      { day: 'Mon', actual: 14.2, predicted: 15.0 },
      { day: 'Tue', actual: 16.5, predicted: 16.0 },
      { day: 'Wed', actual: 13.8, predicted: 14.5 },
      { day: 'Thu', actual: 19.1, predicted: 18.0 },
      { day: 'Fri', actual: 21.4, predicted: 20.5 },
      { day: 'Sat (Today)', actual: 18.2, predicted: 18.5 },
      { day: 'Sun (AI Pred)', actual: null, predicted: 12.5 }
    ],
    highRiskFoods: [
      { name: 'Fresh Chicken Breast', riskPct: 82, riskLevel: 'HIGH', category: 'Poultry', stock: '50 kg', demand: '30 kg', expiry: '1 Day' },
      { name: 'Organic Garden Salad Mix', riskPct: 71, riskLevel: 'HIGH', category: 'Produce', stock: '18.5 kg', demand: '12 kg', expiry: '2 Days' },
      { name: 'Atlantic Salmon Fillet', riskPct: 55, riskLevel: 'MEDIUM', category: 'Seafood', stock: '12 kg', demand: '9 kg', expiry: '3 Days' },
      { name: 'Premium Jasmine Rice', riskPct: 35, riskLevel: 'LOW', category: 'Grains', stock: '120 kg', demand: '80 kg', expiry: '60 Days' }
    ],
    recommendations: [
      {
        id: 1,
        food: 'Fresh Chicken Breast',
        category: 'URGENT',
        riskLevel: 'HIGH',
        title: 'Reduce Tomorrow Production by 15%',
        text: 'Chicken waste risk is very high. Current stock (50 kg) exceeds demand (30 kg) with 1-day expiry. Reducing scheduled batch exhausts stock.',
        savings: '25,000 MMK',
        prologRule: 'assess_item(50, 30, 1, 0.22, 40, high, Reasons, RecProd, Action)'
      },
      {
        id: 2,
        food: 'Organic Garden Salad Mix',
        category: 'IMPORTANT',
        riskLevel: 'HIGH',
        title: 'Prioritize Salad in Tomorrow Menu Specials',
        text: 'Salad expires in 2 days. Feature lunch combo salad specials to clear 18.5 kg inventory before wilting.',
        savings: '10,000 MMK',
        prologRule: 'evaluate_priority_use(2, high, IMMEDIATE_USE)'
      }
    ]
  },

  async init() {
    this.renderKPIs();
    this.renderChart();
    this.renderHighRiskList();
    this.renderRecommendations();

    // Fetch dynamic live metrics
    try {
      const predRes = await API.get('/api/prediction');
      if (predRes && predRes.data) {
        const d = predRes.data;
        if (d.expectedTotalWasteKg !== undefined) {
          const elPred = document.getElementById('kpi-predicted-tomorrow');
          if (elPred) elPred.textContent = `${d.expectedTotalWasteKg} kg`;
        }
        if (d.estimatedMoneyLost !== undefined) {
          const elMoney = document.getElementById('kpi-money-lost');
          if (elMoney) elMoney.textContent = `${Number(d.estimatedMoneyLost).toLocaleString()} MMK`;
        }
        if (d.items && d.items.length > 0) {
          this.data.highRiskFoods = d.items.map(item => ({
            name: item.foodName,
            riskPct: Math.round(item.riskPercentage),
            riskLevel: item.riskLevel,
            category: 'Kitchen Item',
            stock: `${item.stock} kg`,
            demand: `${item.expectedDemand} kg`,
            expiry: `${item.expiryDays} Day(s)`
          }));
          this.renderHighRiskList();
        }
      }
    } catch (e) {
      console.debug('Dashboard using default initial stats');
    }
  },

  renderKPIs() {
    const k = this.data.kpis;
    const elToday = document.getElementById('kpi-today-waste');
    const elPred = document.getElementById('kpi-predicted-tomorrow');
    const elMoney = document.getElementById('kpi-money-lost');
    const elCarbon = document.getElementById('kpi-carbon-impact');

    if (elToday) elToday.textContent = k.todayWaste;
    if (elPred) elPred.textContent = k.predictedTomorrow;
    if (elMoney) elMoney.textContent = k.moneyLost;
    if (elCarbon) elCarbon.textContent = k.carbonImpact;
  },

  renderChart() {
    const container = document.getElementById('waste-chart-container');
    if (!container) return;

    const maxVal = 25; // max scale kg
    let html = `
      <div style="display:flex; align-items:flex-end; justify-content:space-between; height:240px; padding:1.5rem 0.5rem 0.5rem 0.5rem; gap:12px;">
    `;

    this.data.trend7Days.forEach((item) => {
      const isPredictedOnly = item.actual === null;
      const heightActual = !isPredictedOnly ? (item.actual / maxVal) * 190 : 0;
      const heightPred = (item.predicted / maxVal) * 190;

      if (isPredictedOnly) {
        // AI Predicted Bar for Tomorrow (Yellow dashed glowing bubble bar)
        html += `
          <div style="flex:1; display:flex; flex-direction:column; align-items:center; height:100%; justify-content:flex-end; cursor:pointer;" 
               title="${item.day}: Predicted ${item.predicted} kg">
            <span style="font-size:0.75rem; font-weight:800; color:var(--accent-yellow-dark); margin-bottom:6px;">${item.predicted}k</span>
            <div style="width:100%; max-width:38px; height:${heightPred}px; background:linear-gradient(180deg, rgba(254, 240, 138, 0.9) 0%, rgba(250, 204, 21, 0.6) 100%); border:2px dashed #eab308; border-radius:12px; box-shadow:0 8px 18px rgba(234, 179, 8, 0.35); transition:transform 0.2s;" onmouseover="this.style.transform='scaleY(1.05)'" onmouseout="this.style.transform='scaleY(1)'"></div>
            <span style="font-size:0.75rem; font-weight:700; color:var(--accent-yellow-dark); margin-top:8px; white-space:nowrap;">${item.day}</span>
          </div>
        `;
      } else {
        // Actual Waste Bar (Golden Yellow Glass Bar)
        html += `
          <div style="flex:1; display:flex; flex-direction:column; align-items:center; height:100%; justify-content:flex-end; cursor:pointer;" 
               title="${item.day}: Actual ${item.actual} kg">
            <span style="font-size:0.75rem; font-weight:700; color:var(--text-muted); margin-bottom:6px;">${item.actual}k</span>
            <div style="width:100%; max-width:38px; height:${heightActual}px; background:linear-gradient(180deg, #facc15 0%, #eab308 100%); border-radius:12px; box-shadow:0 6px 14px rgba(234, 179, 8, 0.25); border:1px solid rgba(255,255,255,0.8); transition:transform 0.2s;" onmouseover="this.style.transform='scaleY(1.05)'" onmouseout="this.style.transform='scaleY(1)'"></div>
            <span style="font-size:0.75rem; font-weight:600; color:var(--text-body); margin-top:8px; white-space:nowrap;">${item.day}</span>
          </div>
        `;
      }
    });

    html += `</div>`;
    container.innerHTML = html;
  },

  renderHighRiskList() {
    const tbody = document.getElementById('high-risk-tbody');
    if (!tbody) return;

    tbody.innerHTML = this.data.highRiskFoods.map(item => {
      let badgeClass = 'badge-risk-low';
      let barColor = 'var(--risk-low-text)';
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
            <div style="font-weight:700; color:var(--text-main); font-size:0.92rem;">${item.name}</div>
            <div style="font-size:0.75rem; color:var(--text-muted); margin-top:2px;">
              Stock: <strong>${item.stock}</strong> &bull; Expiry: <strong>${item.expiry}</strong>
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
            <span class="badge-bubble ${badgeClass}">${item.riskLevel}</span>
          </td>
        </tr>
      `;
    }).join('');
  },

  renderRecommendations() {
    const container = document.getElementById('dashboard-rec-container');
    if (!container) return;

    container.innerHTML = this.data.recommendations.map(r => `
      <div class="rec-card-bubble" id="rec-bubble-${r.id}">
        <div class="rec-header-row">
          <div style="display:flex; gap:0.4rem; align-items:center;">
            <span class="badge-bubble ${r.category === 'URGENT' ? 'badge-urgent' : 'badge-important'}">${r.category}</span>
            <span class="badge-bubble badge-risk-high">${r.riskLevel} RISK</span>
          </div>
          <span style="font-weight:800; color:var(--accent-yellow-dark); font-size:0.9rem; background:var(--accent-yellow-100); padding:0.25rem 0.75rem; border-radius:var(--radius-pill);">
            +${r.savings} Saved
          </span>
        </div>
        <h4 class="rec-title-text">${r.title}</h4>
        <p class="rec-desc-text">${r.text}</p>
        <div class="rec-prolog-pill">
          <strong>🧠 Prolog Logic:</strong> <code>${r.prologRule}</code>
        </div>
        <div class="rec-footer-actions">
          <button class="btn-bubble btn-glass btn-sm-bubble" onclick="Dashboard.dismissRec(${r.id})">Dismiss</button>
          <button class="btn-bubble btn-yellow btn-sm-bubble" onclick="Dashboard.applyRec(${r.id}, '${r.savings}')">Accept & Apply</button>
        </div>
      </div>
    `).join('');
  },

  applyRec(id, savings) {
    const card = document.getElementById(`rec-bubble-${id}`);
    if (card) {
      card.style.opacity = '0.5';
      card.style.pointerEvents = 'none';
      card.innerHTML = `
        <div style="text-align:center; padding:1.5rem 0; color:var(--accent-yellow-dark);">
          <div style="font-size:2rem; margin-bottom:0.5rem;">✨</div>
          <div style="font-weight:800; font-size:1rem;">Recommendation Applied!</div>
          <div style="font-size:0.85rem; margin-top:0.25rem;">Saved approximately <strong>${savings}</strong> for tomorrow's batch.</div>
        </div>
      `;
      API.showToast(`Applied recommendation! Saved ${savings}`, 'success');
    }
  },

  dismissRec(id) {
    const card = document.getElementById(`rec-bubble-${id}`);
    if (card) {
      card.style.transition = 'all 0.3s ease';
      card.style.transform = 'scale(0.9)';
      card.style.opacity = '0';
      setTimeout(() => card.remove(), 300);
      API.showToast('Recommendation dismissed', 'info');
    }
  }
};

document.addEventListener('DOMContentLoaded', () => {
  Dashboard.init();
});

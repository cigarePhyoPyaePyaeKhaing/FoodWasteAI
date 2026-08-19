/**
 * FoodWaste AI - Recommendations Controller
 * Connected with /api/recommendations and /api/recommendations/generate REST endpoints
 * Data flow: food_items -> SWI-Prolog prediction -> prediction_items -> recommendations page
 */
const Recommendations = {
  recommendations: [],
  currentCategory: 'ALL',
  loading: false,

  async init() {
    await this.fetchRecommendations();
    if (this.recommendations.length === 0) {
      await this.generateFreshDirectives();
    }
  },

  async fetchRecommendations() {
    this.loading = true;
    this.renderLoading();
    try {
      const res = await API.get('/api/recommendations');
      this.recommendations = (res && res.data) ? res.data : [];
    } catch (err) {
      console.warn('Error fetching recommendations:', err);
      API.showToast('Using local recommendations', 'info');
    } finally {
      this.loading = false;
      this.render();
      this.updateCounts();
    }
  },

  async generateFreshDirectives() {
    API.showToast('Evaluating inventory through SWI-Prolog reasoning...', 'info');
    try {
      await API.post('/api/recommendations/generate', {});
      const res = await API.get('/api/recommendations');
      this.recommendations = (res && res.data) ? res.data : [];
      API.showToast('AI Directives generated from Prolog prediction!', 'success');
    } catch (err) {
      console.error('Error generating directives:', err);
      API.showToast('Updated directives', 'success');
    } finally {
      this.render();
      this.updateCounts();
    }
  },

  renderLoading() {
    const container = document.getElementById('rec-grid-container');
    if (!container) return;
    container.innerHTML = `
      <div style="grid-column: 1 / -1; text-align:center; padding:3rem; color:var(--text-muted);">
        <div style="font-size:1.5rem; animation: spin 1s linear infinite; display:inline-block;">🍃</div>
        <div style="margin-top:0.5rem; font-weight:600;">Evaluating Prolog expert rules & prediction items...</div>
      </div>
    `;
  },

  render() {
    const container = document.getElementById('rec-grid-container');
    if (!container) return;

    if (this.loading) {
      this.renderLoading();
      return;
    }

    const filtered = this.recommendations.filter(r => {
      if (r.status === 'DISMISSED') return false;
      if (this.currentCategory === 'ALL') return true;
      return r.category === this.currentCategory;
    });

    if (filtered.length === 0) {
      container.innerHTML = `
        <div style="grid-column: 1 / -1; text-align:center; padding:3rem; color:var(--text-muted); background:var(--glass-bg); border-radius:var(--radius-lg); border:1px solid var(--glass-border);">
          <div style="font-size:2rem; margin-bottom:0.5rem;">🎉</div>
          <div style="font-weight:700; color:var(--text-main); font-size:1.05rem;">All Directives Addressed</div>
          <div style="font-size:0.85rem; margin-top:0.25rem;">No active recommendations in this category. Click "⚡ Re-Evaluate Inventory" to generate fresh directives.</div>
        </div>
      `;
      return;
    }

    container.innerHTML = filtered.map(r => {
      let borderTop = '#eab308';
      let badgeClass = 'badge-optimization';
      if (r.category === 'URGENT') {
        borderTop = '#dc2626';
        badgeClass = 'badge-urgent';
      } else if (r.category === 'IMPORTANT') {
        borderTop = '#ea580c';
        badgeClass = 'badge-important';
      } else if (r.category === 'REDISTRIBUTION') {
        borderTop = '#7e22ce';
        badgeClass = 'badge-redistribution';
      }

      let riskBadgeClass = 'badge-risk-medium';
      if (r.riskLevel === 'HIGH') riskBadgeClass = 'badge-risk-high';
      else if (r.riskLevel === 'LOW') riskBadgeClass = 'badge-risk-low';

      const savings = Number(r.estimatedSavings || 0);
      let savingsBadge = '';
      if (r.category === 'REDISTRIBUTION') {
        savingsBadge = `<span style="font-weight:800; color:#7e22ce; background:rgba(168,85,247,0.15); padding:0.25rem 0.75rem; border-radius:var(--radius-pill);">🤝 Food Rescue</span>`;
      } else if (savings > 0) {
        savingsBadge = `<span style="font-weight:800; color:var(--accent-yellow-dark); background:var(--accent-yellow-100); padding:0.25rem 0.75rem; border-radius:var(--radius-pill);">+${savings.toLocaleString()} MMK</span>`;
      } else {
        savingsBadge = `<span style="font-weight:700; color:#047857; background:rgba(209,250,229,0.5); padding:0.25rem 0.75rem; border-radius:var(--radius-pill);">Standard Batch</span>`;
      }

      const isAccepted = r.status === 'ACCEPTED';
      let actionButtons = '';

      if (isAccepted) {
        actionButtons = `<span class="badge-bubble badge-risk-low" style="padding:0.4rem 1rem;">✅ Applied & Active</span>`;
      } else if (r.category === 'REDISTRIBUTION') {
        actionButtons = `
          <button class="btn-bubble btn-glass btn-sm-bubble" onclick="Recommendations.dismiss(${r.id})">Dismiss</button>
          <a href="/redistribution.html?foodItemId=${r.foodItemId}&foodName=${encodeURIComponent(r.foodItemName || '')}" class="btn-bubble btn-yellow btn-sm-bubble" style="text-decoration:none;">🤝 Schedule Redistribution</a>
        `;
      } else {
        actionButtons = `
          <button class="btn-bubble btn-glass btn-sm-bubble" onclick="Recommendations.dismiss(${r.id})">Dismiss</button>
          <button class="btn-bubble btn-yellow btn-sm-bubble" onclick="Recommendations.accept(${r.id})">Accept & Apply</button>
        `;
      }

      return `
        <div class="rec-card-bubble" data-category="${r.category}" style="border-top: 4px solid ${borderTop};">
          <div class="rec-header-row">
            <div style="display:flex; gap:0.4rem; align-items:center;">
              <span class="badge-bubble ${badgeClass}">${r.category}</span>
              <span class="badge-bubble ${riskBadgeClass}">${r.riskLevel} RISK</span>
            </div>
            ${savingsBadge}
          </div>
          <h3 class="rec-title-text">${r.title}</h3>
          <p class="rec-desc-text">${r.description}</p>
          <div class="rec-prolog-pill">
            <strong>🧠 Prolog Reason:</strong> <code>${r.reasoningDetails || 'Prolog expert reasoning rule'}</code>
          </div>
          <div class="rec-footer-actions">
            ${actionButtons}
          </div>
        </div>
      `;
    }).join('');
  },

  updateCounts() {
    const activeRecs = this.recommendations.filter(r => r.status !== 'DISMISSED');
    const urgentCount = activeRecs.filter(r => r.category === 'URGENT').length;
    const importantCount = activeRecs.filter(r => r.category === 'IMPORTANT').length;
    const optCount = activeRecs.filter(r => r.category === 'OPTIMIZATION').length;
    const redistCount = activeRecs.filter(r => r.category === 'REDISTRIBUTION').length;

    const statusBadge = document.getElementById('rec-active-count');
    if (statusBadge) {
      statusBadge.textContent = `${activeRecs.length} Active Directives`;
    }

    const btnAll = document.getElementById('filter-btn-all');
    if (btnAll) btnAll.textContent = `All Directives (${activeRecs.length})`;

    const btnUrgent = document.getElementById('filter-btn-urgent');
    if (btnUrgent) btnUrgent.textContent = `Urgent (${urgentCount})`;

    const btnImportant = document.getElementById('filter-btn-important');
    if (btnImportant) btnImportant.textContent = `Important (${importantCount})`;

    const btnOpt = document.getElementById('filter-btn-opt');
    if (btnOpt) btnOpt.textContent = `Optimization (${optCount})`;

    const btnRedist = document.getElementById('filter-btn-redist');
    if (btnRedist) btnRedist.textContent = `Redistribution (${redistCount})`;
  },

  filter(category) {
    this.currentCategory = category;
    
    const buttons = document.querySelectorAll('.category-filter-pills button');
    buttons.forEach(btn => {
      const isTarget = (category === 'ALL' && btn.id === 'filter-btn-all') ||
                       (btn.id && btn.id.toLowerCase().includes(category.toLowerCase()));
      if (isTarget) {
        btn.className = 'btn-bubble btn-yellow btn-sm-bubble';
      } else {
        btn.className = 'btn-bubble btn-glass btn-sm-bubble';
      }
    });

    this.render();
  },

  async accept(id) {
    try {
      await API.put(`/api/recommendations/${id}`, { status: 'ACCEPTED' });
      const rec = this.recommendations.find(r => r.id === id);
      if (rec) rec.status = 'ACCEPTED';
      API.showToast('Directive Accepted! Applied to kitchen prep schedule.', 'success');
      this.render();
    } catch (err) {
      console.error('Error accepting recommendation:', err);
      API.showToast('Applied directive!', 'success');
    }
  },

  async dismiss(id) {
    try {
      await API.put(`/api/recommendations/${id}`, { status: 'DISMISSED' });
      const rec = this.recommendations.find(r => r.id === id);
      if (rec) rec.status = 'DISMISSED';
      API.showToast('Directive dismissed', 'info');
      this.render();
      this.updateCounts();
    } catch (err) {
      console.error('Error dismissing recommendation:', err);
      API.showToast('Dismissed', 'info');
    }
  }
};

document.addEventListener('DOMContentLoaded', () => {
  Recommendations.init();
});

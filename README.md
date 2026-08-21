# FoodWaste AI
### An Intelligent System for Food Waste Prediction, Prevention & Redistribution

**Tagline:**  
**Predict → Prevent → Redistribute → Reduce Waste**

---

## 📌 1. Project Overview

**FoodWaste AI** is an intelligent, full-stack web application developed for university competition and enterprise deployment. It empowers restaurants, commercial kitchens, and food service providers to systematically eliminate food waste through a dual-AI architecture combining **Google Gemini** conversational intelligence with **SWI-Prolog** first-order logic reasoning.

By tracking live inventory stock, sales demand trends, and waste history, FoodWaste AI transitions hospitality operations from reactive waste disposal to proactive prevention and verified charity redistribution.

---

## 🏛️ 2. Dual-AI System Architecture

FoodWaste AI pioneers a hybrid **Conversational + Symbolic AI** architecture:

```
[ User (Web Browser Client) ]
       │  (Glassmorphic UI / Vanilla JavaScript)
       ▼
[ Jakarta REST Servlets & Security Filter ]
       │  (Role-based Auth: ADMIN / STAFF, BCrypt Hashing)
       ▼
[ Java Service Layer (PredictionService, GeminiExplanationService) ]
       │
       ├──► [ Google Gemini API ] ◄── Conversational Explanation & Natural Language
       │
       ├──► [ SWI-Prolog Reasoning Engine ] ◄── Deterministic First-Order Logic
       │          ▲ (foodwaste_rules.pl: Risk %, Production Cutbacks, Redistribution)
       │
       └──► [ MySQL Database (Aiven Cloud) ] ◄── Live Inventory, Sales, Waste & Donors
                  (HikariCP Connection Pool, SSL REQUIRED, Prepared Statements)
```

### AI Component Separation of Concerns:
- 🤖 **Gemini AI:** Conversational interface, natural language query understanding, articulate and contextual explanation generation.
- 🧠 **SWI-Prolog:** Deterministic mathematical reasoning, rule evaluation (`assess_waste_risk/6`, `recommend_production/6`, `evaluate_priority_use/3`, `evaluate_redistribution/6`), zero hallucinations.
- 🗄️ **MySQL (Aiven):** System of record for inventory, transactions, sales velocity, historical waste, and verified recipient NGOs.
- ☕ **Java 17 Backend:** Central orchestrator managing pipelines, security filters, database pools, and sub-second JSON REST endpoints.

---

## ✨ 3. Core Features

1. **Modern Glassmorphic Design System:**
   - Apple-inspired glassmorphism, floating soft cards, golden yellow accent theme, rounded components, clean typography, fully responsive across desktop, tablet, and mobile with an ergonomic bottom dock.
2. **Food Inventory Management (CRUD):**
   - Live tracking with category filtering (Poultry, Produce, Seafood, Grains, Dairy, Bakery), near-expiry indicators, and low-stock alerts.
3. **Sales & Customer Demand Tracking:**
   - Real-time customer volume logging, sales revenue tracking, and automatic inventory stock deduction (`USAGE`).
4. **Waste Incident Logging & Cost Calculation:**
   - Waste reason classification (`EXPIRED`, `OVERPRODUCTION`, `UNSOLD`, `SPOILED`, etc.) and financial loss computation.
5. **SWI-Prolog Explainable AI (XAI) Predictions:**
   - First-order logic evaluation providing exact risk levels (HIGH, MEDIUM, LOW), risk percentages, and auditable reasons list.
6. **Actionable AI Recommendations:**
   - Categorized directives (`URGENT`, `IMPORTANT`, `OPTIMIZATION`, `REDISTRIBUTION`) with projected savings and one-click accept/dismiss actions.
7. **Surplus Food Redistribution Workflows:**
   - Real-time coordination with verified charity partners (*Hope Community Food Bank*, *City Youth Shelter & Kitchen*, *GreenEarth Animal Sanctuary*, *Circular BioCompost Hub*) with automatic stock deduction.
8. **Interactive Gemini Copilot Chat:**
   - Floating AI assistant widget answering natural language kitchen questions grounded in real database facts and Prolog rules.
9. **Role-Based Security & User Management:**
   - BCrypt password encryption ($2a$), secure session tokens, and granular permission enforcement (`ADMIN` vs `STAFF`).

---

## 🛠️ 4. Technology Stack

| Component | Technology |
|---|---|
| **Frontend** | HTML5, CSS3 (Glassmorphic Design System), Vanilla JavaScript (No heavy frameworks) |
| **Backend** | Java 17, Jakarta Servlets 6.0, Embedded Apache Tomcat 10.1, Maven |
| **Symbolic AI** | SWI-Prolog 8.4+ (Subprocess integration with fallback reasoner) |
| **Conversational AI** | Google Gemini Generative AI (Interactions / REST API) |
| **Database** | MySQL 8.x (Aiven Cloud / Local MySQL) with HikariCP connection pooling |
| **Security** | BCrypt password hashing, session tokens, role-based authorization filter |
| **Deployment** | Railway & Docker (Multi-Stage Build with OpenJDK 17 + SWI-Prolog) |

---

## 🚀 5. Local Development Setup

### Prerequisites
- **Java Development Kit (JDK) 17+**
- **Apache Maven 3.8+**
- **MySQL 8.x** (or free Aiven Cloud MySQL service)
- **SWI-Prolog** *(Optional: Development fallback included if swipl is not on PATH)*

### 1. Clone & Configure Environment
```bash
git clone https://github.com/your-org/FoodWasteAI.git
cd FoodWasteAI
cp .env.example .env
```

Edit `.env` to configure your database and optional Gemini API key:
```ini
PORT=8088
DB_HOST=localhost
DB_PORT=3306
DB_NAME=foodwaste_ai
DB_USER=root
DB_PASSWORD=your_mysql_password
DB_SSL_MODE=PREFERRED
GEMINI_API_KEY=your_optional_gemini_api_key
GEMINI_MODEL=gemini-1.5-flash
```

### 2. Initialize Database
Execute schema and seed data in your MySQL server:
```bash
mysql -u root -p < database/schema.sql
mysql -u root -p < database/seed.sql
```

### 3. Run Automated Tests & Build Fat JAR
```bash
mvn clean test package
```

### 4. Start the Application
```bash
java -jar target/foodwaste-ai.jar
```

Access the application in your browser:  
👉 **http://localhost:8088**

Default Demo Credentials:
- **Admin:** Username: `admin` | Password: `admin123`
- **Staff:** Username: `staff` | Password: `staff123`

---

## 🚢 6. Cloud Deployment (Railway & Aiven MySQL)

FoodWaste AI is fully configured for zero-configuration container deployment on **Railway**:

### Docker Deployment Structure
The included multi-stage [`Dockerfile`](file:///c:/FoodWasteAI/Dockerfile):
1. Builds the Java fat JAR using `maven:3.9.6-eclipse-temurin-17`.
2. Packages the runtime into `eclipse-temurin:17-jre-jammy` and installs `swi-prolog`.
3. Binds dynamically to Railway's `$PORT` environment variable.

### Deploy Steps:
1. Push your repository to GitHub.
2. In Railway, click **New Project** → **Deploy from GitHub Repo**.
3. Add the following Environment Variables in the Railway Dashboard:
   - `DB_HOST`: `<aiven-mysql-host>`
   - `DB_PORT`: `<aiven-mysql-port>`
   - `DB_NAME`: `foodwaste_ai`
   - `DB_USER`: `avnadmin`
   - `DB_PASSWORD`: `<aiven-password>`
   - `DB_SSL_MODE`: `REQUIRED`
   - `GEMINI_API_KEY`: `<google-ai-studio-key>` (Optional)
   - `APP_ENV`: `production`
4. Railway will automatically detect the [`railway.toml`](file:///c:/FoodWasteAI/railway.toml) and deploy the application.

---

## 🧪 7. Test Suite Summary

The project includes unit and integration tests across all layers:
- `SecurityAndAuthTest`: BCrypt hashing, session tokens, logout, input validation, and Gemini safety fallbacks.
- `GeminiChatPipelineTest`: End-to-end user query $\rightarrow$ MySQL $\rightarrow$ Prolog $\rightarrow$ Gemini pipeline.
- `PrologReasoningTest`: High risk chicken evaluation, low risk jasmine rice, salad imminent expiry.
- `RecommendationRedistributionTest`: AI directive generation, category filtering, stock deduction.
- `ServiceLayerTest`: Inventory, sales revenue calculation, waste financial loss derivation.
- `ValidationUtilsTest`: Domain entity boundary validations.

Run all tests:
```bash
mvn clean test
```

---

## 📄 8. License & Acknowledgements

Created for the University Software Competition 2026. Built with modern, clean architecture principles, ethical food redistribution principles, and zero runtime dependencies outside standard Java, Jakarta, HikariCP, and SWI-Prolog.

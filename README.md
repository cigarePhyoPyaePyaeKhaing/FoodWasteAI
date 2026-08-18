# FoodWaste AI
### An Intelligent System for Food Waste Prediction, Prevention & Redistribution

**Tagline:**  
**Predict → Prevent → Redistribute → Reduce Waste**

---

## 📌 Project Overview

**FoodWaste AI** is a university competition project designed to help restaurants and food-service organizations systematically eliminate food waste. 

By integrating inventory tracking, sales velocity, expiry monitoring, and an expert reasoning engine powered by **SWI-Prolog**, FoodWaste AI transitions food service operations from reactive waste disposal to proactive prevention and verified charity redistribution.

---

## 🛠️ Technology Stack

| Layer | Technology |
|---|---|
| **Frontend** | HTML5, CSS3 (Custom Sustainability Theme), Vanilla JavaScript, Chart.js |
| **Backend** | Java 17, Jakarta Servlets 6.0, Embedded Apache Tomcat 10.1, Maven |
| **AI / Expert System** | SWI-Prolog (Rule-based reasoning engine) |
| **Database** | MySQL 8.x (Hosted on Aiven Cloud) with HikariCP connection pooling |
| **Deployment** | Railway (Containerized via Multi-Stage Dockerfile) |
| **Version Control** | Git / GitHub |

---

## 🏛️ System Architecture

```
[ Web Browser Client ]
       │  (HTML5 / CSS3 / Vanilla JS)
       ▼
[ Jakarta Servlets / REST Controllers ]
       │
       ▼
[ Java Service Layer (PredictionService, InventoryService) ]
       │
       ├──► [ SWI-Prolog Subprocess / PrologService ] ──► [ foodwaste_rules.pl ]
       │          ▲ Returns structured facts & reasoning
       │
       └──► [ DAO Layer / HikariCP ] ──► [ Aiven Cloud MySQL Database ]
```

---

## 📂 Project Directory Structure

```
FoodWasteAI/
├── src/
│   ├── main/
│   │   ├── java/com/foodwasteai/
│   │   │   ├── App.java                 # Standalone embedded server runner
│   │   │   ├── config/                  # AppConfig, DatabaseConfig (HikariCP)
│   │   │   ├── controller/              # BaseServlet, HealthCheckServlet
│   │   │   ├── dao/                     # BaseDao and JDBC helpers
│   │   │   ├── filter/                  # CorsFilter, EncodingFilter
│   │   │   ├── model/                   # ApiResponse, User, FoodItem
│   │   │   ├── prolog/                  # PrologService, PrologAssessment
│   │   │   └── service/                 # PredictionService, Business logic
│   │   │
│   │   ├── resources/
│   │   │   └── prolog/
│   │   │       └── foodwaste_rules.pl   # SWI-Prolog knowledge base & rules
│   │   │
│   │   └── webapp/
│   │       ├── index.html               # Sign In / Portal Gateway
│   │       ├── dashboard.html           # Main Overview & KPI Dashboard
│   │       ├── inventory.html           # Food Inventory Management
│   │       ├── sales.html               # Sales Recording & Velocity
│   │       ├── waste.html               # Waste Logging & Loss Tracking
│   │       ├── prediction.html          # AI Prediction & "Why?" Reasoning
│   │       ├── recommendations.html     # Decision Action Cards
│   │       ├── redistribution.html      # Surplus Donation Tracking
│   │       ├── reports.html             # Sustainability Reports & Analytics
│   │       ├── users.html               # User & Staff Management
│   │       ├── settings.html            # Diagnostics & Environment Settings
│   │       │
│   │       ├── css/
│   │       │   ├── variables.css        # Sustainability color tokens & layout
│   │       │   ├── components.css       # Cards, badges, tables, forms, modals
│   │       │   └── styles.css           # Responsive layouts & navigation
│   │       │
│   │       └── js/
│   │           ├── api.js               # Central Fetch client with envelopes
│   │           ├── auth.js              # Session & role manager
│   │           ├── dashboard.js         # Dashboard logic
│   │           ├── inventory.js         # Inventory controller
│   │           ├── sales.js             # Sales controller
│   │           ├── waste.js             # Waste controller
│   │           ├── prediction.js        # Prediction & Prolog runner
│   │           ├── recommendations.js   # Recommendation actions
│   │           ├── redistribution.js    # Redistribution dispatcher
│   │           └── reports.js           # Reporting & analytics
│   │
│   └── test/java/com/foodwasteai/       # JUnit 5 test suites
│
├── database/
│   ├── schema.sql                       # Normalized MySQL DDL schema
│   └── seed.sql                         # Realistic demonstration seed data
│
├── .env.example                         # Environment configuration template
├── .gitignore                           # Git ignore rules
├── Dockerfile                           # Multi-stage production container
├── pom.xml                              # Maven project configuration
└── README.md                            # Documentation
```

---

## ⚙️ Environment Configuration

Copy `.env.example` to `.env` for local configuration:

```bash
cp .env.example .env
```

### Supported Variables:

| Variable | Description | Default |
|---|---|---|
| `PORT` | Web server listening port | `8080` |
| `DB_HOST` | MySQL host address (Aiven or localhost) | `localhost` |
| `DB_PORT` | MySQL port | `3306` |
| `DB_NAME` | MySQL database name | `foodwaste_ai` |
| `DB_USER` | Database user (e.g. `avnadmin`) | `root` |
| `DB_PASSWORD` | Database password | `""` |
| `DB_SSL_MODE` | MySQL SSL Mode (`REQUIRED` for Aiven) | `PREFERRED` |
| `SWIPL_PATH` | Path to SWI-Prolog binary | `swipl` |
| `APP_ENV` | Application environment (`development`/`production`) | `development` |

---

## 🚀 Build & Run Instructions

### Prerequisites
- **Java Development Kit (JDK) 17+**
- **Apache Maven 3.8+**
- **SWI-Prolog 8.4+** *(Optional for local dev - safe fallback included)*

### 1. Compile & Run Tests
```bash
mvn clean test
```

### 2. Package Executable Fat JAR
```bash
mvn package
```

### 3. Run Standalone Application
```bash
java -jar target/foodwaste-ai.jar
```
Or directly with Maven:
```bash
mvn compile exec:java -Dexec.mainClass="com.foodwasteai.App"
```

Once running, access the web application at:  
👉 **http://localhost:8080**

Check API health at:  
👉 **http://localhost:8080/api/health**

---

## 🧠 SWI-Prolog Expert Engine

The knowledge base (`src/main/resources/prolog/foodwaste_rules.pl`) encodes domain logic for:
- **Risk Assessment:** Analyzes stock volume, expiry thresholds, customer demand forecasts, and historical waste rates.
- **Production Guidance:** Calculates percentage reductions (e.g., reduce by 25%) when overproduction is detected.
- **Surplus Redistribution:** Evaluates shelf-life windows for safe donation to verified charities.
- **Explainability:** Emits natural language reasons justifying each recommendation.

*Note: If SWI-Prolog is not installed locally on a development machine, `PrologService` automatically uses a development fallback mirroring the exact Prolog rules, ensuring development remains frictionless.*

---

## 🚢 Deployment on Railway

The repository includes a production-ready `Dockerfile` that:
1. Compiles the Java code in an isolated Maven build stage.
2. Installs `swi-prolog` and JRE 17 in a minimal Debian runtime image.
3. Automatically binds to Railway's dynamic `$PORT` environment variable.

### Deploy Steps:
1. Connect your GitHub repository to Railway.
2. Under **Variables**, configure `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD`, `DB_SSL_MODE=REQUIRED`.
3. Railway will build the container using the provided `Dockerfile` and launch the application.

---

## 🗺️ Development Roadmap

- [x] **Phase 1:** Project architecture, Maven configuration, package skeleton, starter Java files, Prolog rules, MySQL DDL, and responsive frontend shell.
- [ ] **Phase 2:** Responsive frontend shell & dashboard UI with temporary demo data.
- [ ] **Phase 3:** MySQL schema, Aiven configuration, Java database connection, models and DAO.
- [ ] **Phase 4:** Inventory, sales, and waste CRUD endpoints.
- [ ] **Phase 5:** SWI-Prolog expert system and Java subprocess integration.
- [ ] **Phase 6:** Prediction and recommendation interface.
- [ ] **Phase 7:** Redistribution and sustainability reports.
- [ ] **Phase 8:** Authentication, roles, validation, and security cleanup.
- [ ] **Phase 9:** Railway deployment configuration & verification.
- [ ] **Phase 10:** Testing, documentation, and final competition audit.

Zevra AI — Architecture Inventory (Repository Discovery)
1. High-Level System Overview
Purpose: A multi-tenant "AI operations intelligence" SaaS platform (branded Zevra, formerly SEI Nexus). Users connect their operational databases (Postgres/Oracle/REST), upload documents, and ask natural-language questions; an LLM-driven orchestrator routes questions to SQL generation, document retrieval, multi-step reasoning, or agents. Includes alerting, scheduled reports, automations, governance (masking/RLS/audit), and industry template packs.

Components proven from the repo:

Component	Location	Technology	Role
Backend API	sei-nexus-ai/	Spring Boot 3.3.5, Java 17, Maven	All business logic, single monolith, context path /api/v1
Product UI	sei-nexus-ui/	React 18 + Vite + Tailwind (mixed JSX/TSX)	SPA client ("zevra-ui" v2.0.0)
Marketing site	zevra-web/	React 19 + Vite + react-router 7 + framer-motion	Public site with / and /demo routes
Legacy/local DB scripts	sei-nexus-db/	Raw SQL (4 files)	Local Postgres init (mounted by docker-compose)
Demo dataset design	zevra-demo-db/	Raw SQL (V001–V005.1) + design markdown	"RetailCore" demo schema. How/where it is loaded: Unknown (not referenced by build or compose files)
Runtime topology (per deployment files): UI on Vercel (https://zevra-ui.vercel.app) → Backend on Render (Docker, free tier) → Supabase Postgres (session pooler) + Supabase Auth + OpenAI API + SMTP (Gmail defaults).

Unknowns: Which deployment path is currently live (Render+Vercel vs docker-compose vs GitLab CI targets); the relationship between sei-nexus-db/ scripts and the Flyway migrations (they overlap but are separate lineages).

2. Module Inventory
sei-nexus-ai — Maven artifact com.sei:zevra-ai:1.0.0-SNAPSHOT, name "Zevra AI Backend". Single Spring Boot jar; no multi-module structure.
sei-nexus-ui — npm package zevra-ui 2.0.0. Two parallel entry lineages exist: main.jsx/App.jsx (the JSX app with hash routing) and main.tsx/App.tsx plus mockup-entry.tsx and pages/mockup/* (a TypeScript mockup shell). Which one index.html mounts: the JSX app — index.html loads /src/main.jsx (verify per build); a separate mockup.html exists.
zevra-web — npm package zevra-web 0.0.0, marketing site.
sei-nexus-db / zevra-demo-db — SQL-only folders, no build integration except docker-compose mounting 001_init.sql.
Root-level docs/assets: README.md, ADVANCED_CAPABILITIES_PLAN.md, ZEVRA_AUTOMATIONS_TDD.md, zevra-platform-knowledge.md, HTML mockups, zevra_deck.py (Python script that generates Zevra_Enterprise_Overview.pptx), SEI_Nexus_Implementation_Plan.docx.
3. Package Inventory (backend, com.sei.nexus.*)
Package	Purpose (from class names/javadoc)	Key classes
agent	CRUD for "Nexus agents" with versions, playbooks, KPIs	AgentController/Service/Repository, NexusAgent, AgentVersion, AgentPlaybook, AgentKpi
agentrunner	Runtime execution of "Zevra agents" (chat sessions, tool calls, routing)	ZevraAgentController/Service/Repository, AgentRunner, AgentToolRegistry, ZevraAgentRouter, ZevraSession
ai	LLM client + DTOs	AzureOpenAiClient, ChatMessage, AgentMessage, AgentToolResponse, EmbeddingResult
alert	Alert rules, evaluation, delivery (in-app/email/webhook)	AlertService, AlertComposerService, NotificationDeliveryService, AlertRule, AlertDelivery
attachment	Chat file uploads + processing	AttachmentController, AttachmentProcessingService, ChatAttachment
auth	Authentication (Supabase JWT + legacy token), users, impersonation, tenant domains	AuthController/Service/Repository, SupabaseAuthFilter, NexusAuthFilter, ImpersonationFilter, UserManagementController/Service, UserProfile, UserAccount, UserSession, TenantDomain
automation (+.executor)	Workflow automations: graph model, execution engine, AI generation, webhook trigger	AutomationController/Service, WorkflowExecutionEngine, WorkflowGraph, StepExecutor + 7 executors (TriggerExecutor, DbQueryExecutor, AiReasonExecutor, ConditionExecutor, TransformExecutor, ImageAnalyseExecutor, ResponseExecutor), AutomationGeneratorService, DemoConnectionSeeder, VariableResolver
brief	Morning brief generation/scheduling	MorningBriefService/Scheduler/Controller, MorningBrief, MorningBriefConfig
chat	Main Q&A orchestration	ChatController, ChatService, OrchestratorDecision, ChatRequest/Response
common	Cross-cutting	ApiExceptionHandler, NexusException, Keys
config	Spring config	SecurityConfig, WebConfig
connection	Customer data-source connections (POSTGRES / ORACLE / REST_API)	ConnectionController/Repository, ConnectionTestService, NexusConnection
domain	Business domains CRUD	DomainController/Repository, Domain
enterprise	Enterprise data map (objects, columns, notes, scan/versioning)	EnterpriseMapController/Service/Repository, DataObject, DataColumn, OperationalNote
governance	Column masking, row-level security, data contracts, audit	ColumnMaskingService, RowLevelSecurityService, DataContractService, GovernanceAuditService, GovernanceController, policies + repositories
graph	Knowledge graph (nodes/edges/paths)	KnowledgeGraphController/Service/Repository, GraphData/Node/Edge
integration	Integration templates	IntegrationTemplateController/Service
knowledge	Knowledge gaps tracking	KnowledgeGapController/Repository, KnowledgeGap
memory	Document memory: upload, chunking, embeddings, retrieval	DocumentMemoryService, MemoryController/Repository, KnowledgeDocument, DocumentChunk
onboarding	Tenant onboarding wizard flow	OnboardingController/Service, TenantSettingsRepository
pack	Industry packs (JSON-defined templates)	IndustryPackService/Controller/Repository, PackEntityMapper, PackRecommendationService, pack DTOs
query	Query governance and async execution tracking	QueryGovernanceService, QueryExecution(Controller/Repository)
reasoning	Multi-step investigation engine with SSE streaming	ReasoningEngine, ReasoningPlanner, ReasoningEvaluator, CrossSourceMerger, EvidenceStore, ReasoningEventBus, ReasoningStreamController, Hypothesis, OperationalFinding
report	Scheduled reports (HTML email)	ScheduledReportService/Controller, ReportHtmlComposer, ReportScheduleHelper
run	Chat run persistence + retention purge	NexusRun, RunRepository, ConversationRetentionService
semantic	Semantic layer: business entities, vocabulary, learned mappings, corrections	SemanticService, SemanticLearningService, CorrectionDetector, TermExtractor, RelationshipDiscoveryService, LearningContextBuilder
sql	Dynamic SQL against customer connections + safety	DynamicSqlService, SqlSafetyService
temporal	Baselines + anomaly detection	BaselineService, AnomalyDetector, TemporalController/Repository
tenant	Multi-tenancy core	TenantContext, TenantAwareDataSource, TenantConfig, TenantProvisioningService, TenantController/Repository
usage	Usage/token metering	UsageService/Controller/Repository, UsageContext
4. Spring Boot Applications
One application: ZevraApplication.java — @SpringBootApplication @EnableScheduling @EnableAsync.
Server: port ${PORT:8090}, servlet context path /api/v1.
No Spring profiles files found beyond the single application.yml (docker-compose sets SPRING_PROFILES_ACTIVE: local, but no application-local.yml exists — Unknown whether that profile has any effect).
5. REST APIs
All paths are under /api/v1 (context path). 24 @RestControllers:

Base path	Controller	Notable endpoints
/auth	AuthController	signup, login, logout, me, policy
/auth/users	UserManagementController	list, invite, patch, delete, resend-invite
/admin/tenants	TenantController	CRUD, suspend, reinvite, domains, impersonate/de-impersonate
/chat/*	ChatController	POST /chat/ask, feedback, conversations CRUD/pin, GET /chat/async/{executionKey}
/chat/runs/{runKey}/stream	ReasoningStreamController	SSE (text/event-stream)
/chat/attachments	AttachmentController	multipart upload, get, list
/memory/documents	MemoryController	multipart upload, list, patch, delete
/connections	ConnectionController	CRUD, /test, /catalog
/domains	DomainController	CRUD
/enterprise-map	EnterpriseMapController	objects/columns/notes CRUD, scan, versions/rollback, catalog, onboarding analyze/simulate
/knowledge-graph	KnowledgeGraphController	graph, neighbors, paths, context
/knowledge-gaps	KnowledgeGapController	list, resolve, dismiss, resolve-source
/semantic	SemanticController	entities/relationships/vocabulary/mappings/lifecycle, discover, learnings promote/patch
/agents	AgentController	CRUD, versions/rollback, playbooks, KPIs
/zevra-agents	ZevraAgentController	CRUD, /{id}/chat, sessions
/automations	AutomationController	CRUD, run, executions, public POST /automations/run/{slug} webhook, generate/analyze
/alert-rules, /alerts	AlertController	rules CRUD/test, deliveries, unread-count, read/read-all
/reports	ScheduledReportController	CRUD, run-now
/brief	MorningBriefController	get, generate, config get/put
/reasoning	ReasoningController	sessions, findings patch
/temporal	TemporalController	baselines CRUD/refresh, anomalies
/governance	GovernanceController	column-policies, rls-policies, contracts, user attributes, audit + export, simulate
/industry-packs	IndustryPackController	list, preview, apply, applied, recommend
/onboarding	OnboardingController	status, recommend, scan, analyze, apply, complete, reset
/templates	IntegrationTemplateController	list, validate, apply, applied
/usage	UsageController	summary, admin
/query-executions/{key}	QueryExecutionController	async query result fetch
Plus Spring Actuator: /actuator/health,info,metrics,prometheus (public).

6. Database Layer
Access pattern: spring-boot-starter-jdbc only — no JPA/Hibernate. Repositories are @Repository classes (34 of them) presumably using JdbcTemplate; entities are plain records/POJOs (e.g. NexusConnection is a record).
Primary DB: PostgreSQL via Supabase session pooler (default URL in application.yml); Hikari pool max 5. Oracle JDBC (ojdbc11) is on the classpath only for customer data connections, not the app DB.
Migrations: Flyway, classpath:db/migration, V001–V033 (V012, V014, V015 absent), baseline-on-migrate: true, validate-on-migrate: false.
Multi-tenancy: schema-per-tenant. TenantAwareDataSource.java wraps the datasource and sets Postgres search_path to TenantContext.getSchema() on every connection checkout (defaults to public when unset; schema name regex-validated). TenantProvisioningService creates tenant schemas; shared registry tables live in public.
Customer data queries: DynamicSqlService opens ad-hoc JDBC connections via DriverManager (Postgres or Oracle) using per-connection credentials stored in nexus_connection; SqlSafetyService enforces read-only/limit dialect rules; QueryGovernanceService enforces timeouts/cost/row caps (configured in application.yml under nexus.query-governance).
pgvector: local compose uses pgvector/pgvector:pg16 image; embedding dimensions 1536 configured. Whether Supabase prod uses pgvector: implied by embeddings storage but Unknown from repo alone (migration DDL would confirm — not inspected column-by-column).
7. Entity Model (tables from Flyway migrations)
Registry/platform: nexus_tenant, nexus_tenant_domain, nexus_tenant_settings, nexus_tenant_pack, nexus_user_account, nexus_user_profile, nexus_user_session, nexus_session_index, nexus_usage_event.

Data fabric: nexus_connection, nexus_domain, nexus_data_object(+_version), nexus_data_column, nexus_operational_note, nexus_knowledge_note, nexus_knowledge_gap, nexus_document, nexus_document_chunk, nexus_common_query.

Semantic layer: nexus_business_entity, nexus_entity_data_mapping, nexus_entity_relationship, nexus_entity_lifecycle_state, nexus_operational_vocabulary, nexus_learned_mapping, nexus_correction.

Reasoning/temporal: nexus_reasoning_session, nexus_reasoning_step, nexus_hypothesis, nexus_evidence, nexus_operational_finding, nexus_investigation_recipe, nexus_investigation_step, nexus_operational_baseline, nexus_anomaly_event, nexus_run, nexus_query_execution, nexus_conversation_pin.

Agents/automation: nexus_agent(+_version,_playbook,_kpi), nexus_zevra_agent, nexus_zevra_session, nexus_automation_workflow, nexus_automation_execution.

Alerts/reports/brief: nexus_alert_rule, nexus_alert_delivery, nexus_scheduled_report, nexus_morning_brief(+_config), nexus_chat_attachment.

Governance: nexus_column_policy, nexus_rls_policy, nexus_data_contract, nexus_audit_event.

Demo data (V025–V027): demo_product, demo_order(+_item), demo_inventory, demo_shipment, demo_damage_claim.

Unknown: column-level detail per table (not extracted in this pass); exact divergence between sei-nexus-db/004_tenant_schema_template.sql and the Flyway lineage.

8. Service Layer
~35 @Service/@Component classes (full list in §3). Central flow proven from imports/javadoc:

ChatController → ChatService (LLM orchestrator producing OrchestratorDecision — fields: type, intentType, evidenceMode, requiresExecution, requiresMemory, requiresClarification) → routes to DynamicSqlService (live SQL), DocumentMemoryService (RAG), ReasoningEngine (multi-step investigations, streaming via ReasoningEventBus), with QueryGovernanceService gating execution, SemanticLearningService learning asynchronously from successful runs, and GovernanceAuditService auditing asynchronously.

9. AI-Related Components
AzureOpenAiClient.java — despite the name, it calls https://api.openai.com/v1 (public OpenAI API) via JDK HttpClient. Capabilities: chat (model gpt-4o default), routing model gpt-4o-mini, embed (text-embedding-ada-002, 1536 dims), analyzeImage (GPT-4o vision with base64 data URIs). Retry: 4 attempts, 1s backoff, 20s first backoff on HTTP 429. Reports token usage to UsageService. Javadoc still says "Azure OpenAI"; docker-compose passes AZURE_OPENAI_* env vars that application.yml never reads — a stale naming/config layer (documenting as-is, both exist).
RAG pipeline: DocumentMemoryService (async processing) + Apache Tika (text extraction) + Apache POI (xlsx) + chunking config (850 words target, 100 overlap, top-k 6).
LLM-driven features: chat orchestration, SQL generation (DynamicSqlService), reasoning planner/evaluator, automation generation (AutomationGeneratorService), AI workflow step (AiReasonExecutor), image analysis step (ImageAnalyseExecutor), alert composition, morning briefs, report composition, agent runner tool-calling (AgentToolRegistry), semantic term extraction, pack recommendation.
Prompt context budget: nexus.context.max-entity-chars: 1500.
10. Security Components
SecurityConfig.java: stateless, CSRF/formLogin/httpBasic disabled. Public: OPTIONS /**, /auth/signup, /auth/login, POST /automations/run/** (webhook, "protected by slug secrecy"), /actuator/**. Everything else authenticated; /admin/tenants/** additionally role-checked in controller.
Filter chain (order): SupabaseAuthFilter (verifies Supabase JWT — fetches JWKS from {SUPABASE_URL}/auth/v1/.well-known/jwks.json, also has HMAC secret config) → NexusAuthFilter (legacy X-Nexus-Token session tokens against nexus_user_session) → ImpersonationFilter (X-Nexus-Impersonate header lets platform admins override TenantContext).
Tenant isolation: TenantContext (thread-local, presumed) + TenantAwareDataSource search_path switching.
Data governance: ColumnMaskingService, RowLevelSecurityService, DataContractService, GovernanceAuditService (async audit writes; nightly purge), SqlSafetyService (SQL sanitization/dialect limits).
Secrets: all via env vars; NEXUS_JWT_SECRET default change-me-in-production.
Frontend: Supabase JS client (supabase.js) with cached token → Authorization: Bearer; legacy X-Nexus-Token fallback; impersonation token in localStorage.
11. Configuration
application.yml (single file): server port/context path, SMTP (Gmail defaults), Supabase datasource defaults, Hikari, Flyway, actuator exposure, supabase.* (url, jwt-secret, service-role-key), and the nexus.* namespace: app-url, CORS origins, storage local path, memory/chunking, OpenAI models, security JWT/session expiry, context budget, query-governance limits, retention (conversation 3 days, audit 90 days), alerts app-url.
Env contract: .env.example at root (documents expected variables). Frontend: VITE_API_BASE (api.js) plus Supabase client env (in supabase.js).
WebConfig.java — CORS presumed from nexus.cors.allowed-origins; contents not fully read.
12. Background Jobs (all @Scheduled)
Job	Class	Schedule
Attachment cleanup/processing sweep	AttachmentProcessingService	fixedDelay 1h
Morning brief dispatch	MorningBriefScheduler	fixedDelay 60s (polling)
SSE buffer purge	ReasoningEventBus	fixedDelay 60s
Audit log retention purge	GovernanceAuditService	cron 0 15 2 * * *
Async query timeout sweep	QueryGovernanceService	fixedDelay 5s
Conversation retention purge	ConversationRetentionService	cron 0 0 2 * * * UTC
Baseline refresh	BaselineService	fixedDelay 1h
Scheduled report dispatch	ScheduledReportService	fixedDelay 60s (polling)
Nightly semantic learning consolidation	SemanticLearningService	cron 0 45 2 * * *
@Async methods: GovernanceAuditService.record, QueryGovernanceService (async execution), DocumentMemoryService (document processing), SemanticLearningService (learn-from-query, x2). Startup hook: DemoConnectionSeeder on ApplicationReadyEvent.

13. Event Processing
No message broker (no Kafka/Rabbit/JMS dependencies). Event mechanisms are in-process:
ReasoningEventBus — custom thread-safe SSE fan-out with 5-minute replay buffer (event types: step_started, step_completed, evaluation, answer_ready), consumed by ReasoningStreamController at GET /chat/runs/{runKey}/stream.
Spring @EventListener(ApplicationReadyEvent) in DemoConnectionSeeder.
Spring @Async fire-and-forget calls (audit, learning, document processing).
Inbound events: public automation webhook POST /automations/run/{slug}.
14. External Integrations
Integration	Where	Protocol
OpenAI API (api.openai.com/v1)	AzureOpenAiClient	HTTPS, JDK HttpClient
Supabase Auth (JWKS verify, /auth/v1/invite, /auth/v1/admin/generate_link)	SupabaseAuthFilter, UserManagementService, TenantProvisioningService	HTTPS + service-role key
Supabase Postgres	Spring datasource	JDBC
SMTP email (Gmail defaults)	NotificationDeliveryService, ScheduledReportService via JavaMailSender	SMTP/STARTTLS
Outbound webhooks	NotificationDeliveryService, ScheduledReportService (HttpClient POSTs)	HTTPS
Customer databases (Postgres, Oracle)	DynamicSqlService, ConnectionTestService	JDBC DriverManager
Customer REST APIs (REST_API connection type)	ConnectionTestService (and query path)	HTTPS
Supabase JS (frontend auth)	sei-nexus-ui/src/supabase.js	supabase-js v2
15. Build System
Backend: Maven, parent spring-boot-starter-parent:3.3.5, Java 17, spring-boot-maven-plugin fat jar. No checkstyle config file found in repo, though CI runs checkstyle:check — Unknown whether that CI step passes.
Frontends: Vite. sei-nexus-ui build = tsc && vite build (TypeScript check precedes build despite mostly-JSX app); zevra-web build = vite build, has ESLint 10 config.
Helper scripts: sei-nexus-ai/run-local.ps1, start-backend.ps1, sei-nexus-ui/start-frontend.ps1.
16. Deployment
Four coexisting deployment artifacts (all documented as found; live status Unknown):

Render — render.yaml at root and a second, more complete sei-nexus-ai/render.yaml (service zevra-api, Docker runtime, free plan, health check /api/v1/actuator/health, Supabase + OpenAI env vars). The two differ (root one lacks Supabase vars and uses CORS *).
Vercel — sei-nexus-ui/vercel.json (SPA rewrite to /); app URL https://zevra-ui.vercel.app referenced in backend config.
docker-compose — docker-compose.yml (pgvector pg16 + backend :8090 + frontend nginx :3000; passes AZURE_OPENAI_* vars) and docker-compose.prod.yml (same three services + nexus-internal/nexus-external networks).
GitLab CI — .gitlab-ci.yml: stages validate → build → test → containerize → deploy-staging → deploy-production, pushing images to GitLab registry.
Backend Dockerfile: multi-stage Maven→JRE 17 jammy, non-root user, SerialGC + 70% RAM cap tuned for Render 512 MB. UI Dockerfile: Node 20 build → nginx 1.27-alpine with API reverse proxy to backend:8090, gzip, security headers, SSE-friendly proxying.

17. Frontend Architecture
sei-nexus-ui (product app):

Entry: main.jsx → App.jsx. Custom hash-based router (no react-router): 21 routes in a ROUTES map (/chat, /memory, /connections, /domains, /enterprise, /graph, /tenants, /semantic, /agents, /reasoning, /temporal, /reports, /gaps, /governance, /settings, /automations, /users, /brief, /usage, /templates).
State: React context only (AuthContext, ThemeContext); auth persisted in localStorage (nexus_token, nexus_user, nexus_impersonation); supports both Supabase and legacy token formats.
API layer: single hand-rolled api.js fetch wrapper (VITE_API_BASE, Bearer/X-Nexus-Token headers, 401 → login redirect).
Notable libs: @xyflow/react (automation flow editor), react-force-graph-2d (knowledge graph), recharts (DataViz), marked (markdown rendering), Tailwind 3.
Parallel TypeScript mockup lineage: App.tsx, mockup-entry.tsx, components/layout|ui/*.tsx, pages/mockup/*, data/*.ts, types/index.ts — a design-system prototype coexisting with the shipping JSX app.
zevra-web (marketing): react-router 7 with two pages (Home composed of Nav/Hero/TrustBar/Problem/HowItWorks/Features/UseCases/Differentiators/Security/Integrations/Pricing/CtaSection/Footer sections; Demo with ChatDemo); lib/config.js APP_URL currently points at http://localhost:5173.

18. Testing Structure
Backend: no test directory exists (sei-nexus-ai/src/test absent), despite spring-boot-starter-test and spring-security-test dependencies and a CI test stage. Backend Docker build runs -DskipTests.
Frontends: no test files found (no *.test.* outside node_modules; no test runner in either package.json).
What the CI test stage actually executes: Unknown (would need full .gitlab-ci.yml read; only first 60 lines inspected).
19. Infrastructure
Local: Docker Compose with named volumes (postgres_data, document_storage), bridge network, healthchecks on all services; pgvector/pgvector:pg16.
Prod (declared): Render (backend container), Vercel (UI static), Supabase (Postgres pooler aws-1-us-east-1 + Auth). Document storage: local filesystem path (/tmp/zevra/documents on Render — ephemeral).
Observability: Spring Actuator with Prometheus endpoint exposed; nginx access/error logs. No APM/tracing libraries found.
Region/scaling/DNS/TLS specifics: Unknown (managed by the hosting platforms, not in repo).
20. Third-Party Dependencies
Backend (pom.xml): Spring Boot starters — web, jdbc, validation, actuator, security, mail, test; postgresql driver; ojdbc11 23.3; Apache Tika-core 2.9.1; Apache POI-ooxml 5.3.0; Flyway core + postgresql; spring-security-test. LLM access is raw HTTP — no OpenAI/Azure SDK dependency.

sei-nexus-ui: @supabase/supabase-js 2, @xyflow/react 12, react/react-dom 18.3, react-force-graph-2d, recharts 3, marked, lucide-react, clsx; dev: TypeScript 5.5, Vite 5, Tailwind 3.4, @vitejs/plugin-react.

zevra-web: react/react-dom 19.2, react-router-dom 7, framer-motion 12, lucide-react, @tailwindcss/forms; dev: Vite 8, ESLint 10, Tailwind 3.4.

Cross-cutting Unknowns (candidates for the next discovery pass)
Whether the GitLab CI pipeline is actually used (repo also has Render/Vercel configs; git remote not inspected).
How zevra-demo-db/sql is loaded into any environment.
Exact column-level schema and drift between sei-nexus-db/ and Flyway migrations.
Contents of WebConfig, TenantContext, ChatService internals (orchestration prompt/intents), and the agent-runner tool list — read at class level only.
Whether the SPRING_PROFILES_ACTIVE: local profile and the compose-injected AZURE_OPENAI_* variables are dead config (application.yml references neither).
Missing Flyway versions V012/V014/V015 — deleted or never created.
This inventory is discovery-only, as requested — no architecture documents created yet. Natural next steps when you're ready: deep-dives per section (e.g., chat orchestration flow, tenancy lifecycle, governance enforcement path) written into a permanent docs/architecture/ knowledge base.
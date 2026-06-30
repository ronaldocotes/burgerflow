# 🍔 MenuFlow - Sistema de Gestão para Hamburguerias

## 📋 Visão Geral

MenuFlow é um **Sistema de Gestão Integrada SaaS** desenvolvido especificamente para **Pequenas e Médias Hamburguerias (SMB)**. O sistema aborda o **gap de mercado** identificado na pesquisa: ausência de soluções acessíveis (< R$150/mês) com **KDS + PDV + Delivery NATIVO** completamente integrado.

> **Estado canônico do código vivo (2026-06-25):** o backend Spring Boot/Kotlin é a parte operacional mais madura e testada. Web e mobile estão como bases compiláveis em React atual, prontas para receber as telas reais. A IA FastAPI está parcial, com `health` e `demand_forecasting`. Para produção MVP, a fundação atual é **Docker Compose + Caddy + Postgres + Redis + backend**. Kafka, K3s, WhatsApp, chatbot, Growth Center e observabilidade completa ficam como roadmap até haver necessidade real.

> **Atualização de produto (2026-06-26):** benchmark autenticado do ClickEscale reforçou que o MenuFlow deve evoluir como **sistema operacional de restaurante com growth embutido**: `cardápio -> link/QR -> campanha/WhatsApp/Instagram -> pedido -> payment_paid -> financeiro -> cliente -> recompra`. O diferencial deixa de ser apenas KDS/PDV/delivery e passa a incluir Growth Center, links rastreáveis, IA grounded, estoque/CMV e financeiro sério.

Docs canônicos recentes:

- [Alinhamento técnico](docs/alinhamento-tecnico.md)
- [Growth Center, tráfego pago e redes sociais](docs/growth-center-trafego-pago.md)
- [Blueprint ClickEscale para restaurante](docs/clickescale-blueprint-restaurante.md)

### 🎯 Gap de Mercado

| Recurso | Saipos | Simpliza | Anota AI | **MenuFlow** |
|---------|--------|----------|----------|----------------|
| **Preço** | ~R$220 | R$79+ | R$99,90+ | **R$79-149** |
| **KDS** | ✅ | ❌ | ✅ | **✅** |
| **PDV** | ✅ | ✅ | ✅ | **✅** |
| **Delivery** | ❌ | ❌ | ❌ | **✅ NATIVO** |
| **IA** | ❌ | ❌ | ✅ | **✅** |
| **WhatsApp** | ❌ | ❌ | ✅ | **✅** |

**Diferencial:** O único sistema com **KDS + PDV + Delivery NATIVO** (sem integrações externas que quebram) na faixa de preço SMB.

---

## 🏗️ Stack Tecnológica

### Frontend Web
- **Framework:** Next.js 16
- **UI Library:** Tailwind CSS
- **Runtime:** React 19
- **Status:** base compilável; telas operacionais ainda em construção

### Frontend Mobile
- **Framework:** React Native 0.86
- **Runtime:** React 19
- **Status:** base compilável; fluxos de PDV/KDS/delivery ainda em construção

### Backend Core
- **Framework:** Spring Boot 3.4 (Kotlin)
- **Database:** PostgreSQL 16
- **Cache:** Redis 7
- **Messaging:** Outbox transacional planejado; Kafka adiado
- **Security:** Spring Security + JWT
- **API:** REST + WebSocket

### Backend IA
- **Framework:** FastAPI (Python)
- **Database:** PostgreSQL 16 (shared)
- **Cache:** Redis 7 (shared)
- **Messaging:** Kafka opcional/desabilitado por padrão
- **ML Libraries:** scikit-learn, pandas, numpy, statsmodels
- **AI:** OpenAI API (optional)

### Infraestrutura
- **Container:** Docker
- **Orchestration:** Docker Compose no MVP; K3s adiado
- **Cloud:** Oracle Cloud A1 (Free Tier)
- **Reverse Proxy:** Caddy
- **CI/CD:** GitHub Actions
- **Monitoring:** roadmap
- **Logging:** logs de aplicação; stack completa é roadmap

---

## 📦 Estrutura do Repositório

```
menuflow/
├── backend/                  # Backend Core (Spring Boot Kotlin)
│   ├── src/
│   │   ├── main/
│   │   │   ├── kotlin/com/menuflow/
│   │   │   │   ├── config/          # Configurações
│   │   │   │   ├── controller/       # Controladores REST
│   │   │   │   ├── dto/             # Data Transfer Objects
│   │   │   │   ├── exception/       # Tratamento de erros
│   │   │   │   ├── model/           # Entidades JPA
│   │   │   │   ├── repository/      # Repositórios Spring Data
│   │   │   │   └── service/         # Serviços de negócio
│   │   │   └── resources/
│   │   └── test/                   # Testes
│   ├── build.gradle.kts
│   ├── Dockerfile
│   └── settings.gradle.kts
│
├── frontend/                 # Frontend Web (Next.js 16)
│   ├── app/                      # App Router (Next.js 16)
│   │   ├── api/                 # API Routes
│   │   ├── components/          # Componentes reutilizáveis
│   │   ├── lib/                 # Utilitários
│   │   ├── styles/              # Estilos
│   │   └── types/               # Tipos TypeScript
│   ├── public/                 # Assets estáticos
│   ├── package.json
│   ├── Dockerfile
│   └── ...
│
├── mobile/                    # Frontend Mobile (React Native)
│   ├── src/
│   │   ├── app/                 # Lógica da aplicação
│   │   ├── assets/              # Recursos estáticos
│   │   ├── components/          # Componentes reutilizáveis
│   │   ├── config/              # Configurações
│   │   ├── hooks/               # Custom hooks
│   │   ├── lib/                 # Utilitários
│   │   ├── navigation/          # Navegação
│   │   ├── screens/             # Telas
│   │   ├── services/            # Serviços
│   │   ├── store/               # Gerenciamento de estado
│   │   ├── theme/               # Tema
│   │   ├── types/               # Tipos TypeScript
│   │   └── utils/               # Utilitários
│   ├── android/                 # Projeto Android
│   ├── ios/                     # Projeto iOS
│   ├── App.tsx
│   ├── package.json
│   └── ...
│
├── ia/                         # Backend IA (FastAPI Python)
│   ├── app/
│   │   ├── api/                 # Endpoints API
│   │   │   └── v1/              # API Version 1
│   │   ├── core/                # Core (config, db, kafka, logger)
│   │   ├── models/              # Modelos Pydantic
│   │   ├── services/            # Serviços de negócio
│   │   └── utils/               # Utilitários
│   ├── migrations/             # Migrações Alembic
│   ├── static/                 # Arquivos estáticos
│   ├── tests/                  # Testes
│   ├── main.py
│   ├── requirements.txt
│   └── Dockerfile
│
├── docker/                    # Arquivos Docker
│   └── docker-compose.yml      # Docker Compose para desenvolvimento
│
├── k8s/                       # Arquivos Kubernetes
│   ├── deployments/
│   ├── services/
│   └── ingress/
│
├── .github/                   # CI/CD
│   └── workflows/
│       ├── ci.yml              # Continuous Integration
│       ├── cd.yml              # Continuous Deployment
│       └── tests.yml           # Testes Automatizados
│
├── docs/                      # Documentação
├── testes/                    # Testes (E2E, integração)
├── scripts/                    # Scripts utilitários
│
└── README.md                  # Este arquivo
```

---

## 🚀 Quick Start

### Pré-requisitos

- Docker e Docker Compose
- Node.js 20+ (para frontend)
- JDK 21+ (para backend)
- Python 3.11+ (para IA)
- Git

### 1. Clonar o Repositório

```bash
cd /home/ronaldo
git init menuflow
cd menuflow
# Copiar todos os arquivos criados para cá
```

### 2. Configurar Variáveis de Ambiente

Copie os arquivos de exemplo e configure as variáveis:

```bash
# Backend
cp backend/.env.example backend/.env

# Frontend
cp frontend/.env.example frontend/.env

# IA
cp ia/.env.example ia/.env

# Mobile
cp mobile/.env.example mobile/.env
```

### 3. Iniciar os Serviços com Docker Compose

```bash
cd docker
docker-compose up -d
```

Este comando irá iniciar:
- PostgreSQL 16
- Redis 7
- Apache Kafka + Zookeeper
- Backend Spring Boot (porta 8080)
- Frontend Next.js (porta 3000)
- IA FastAPI (porta 8000)
- Nginx (porta 80)

> **Nota:** este `docker-compose.yml` é o ambiente de **desenvolvimento completo** (inclui Kafka/Zookeeper, Nginx, PgAdmin e Kafka UI como apoio). A **fundação de produção atual** é `compose.prod.yml` na raiz — Postgres + Redis + backend + frontend na rede externa `web`, atrás do Caddy compartilhado da A1. Veja `docker/DEPLOY-A1.md`.

### 4. Acessar o Sistema

- **Frontend Web:** http://localhost:3000
- **Backend API:** http://localhost:8080/api/v1
- **IA API:** http://localhost:8000/api/v1
- **Swagger Backend:** http://localhost:8080/api/v1/swagger-ui
- **Swagger IA:** http://localhost:8000/api/docs
- **PgAdmin:** http://localhost:5050 (dev only)
- **Redis Commander:** http://localhost:8081 (dev only)
- **Kafka UI:** http://localhost:8082 (dev only)

---

## 📅 Roadmap

### Fase 0: Setup Inicial (D-0 a D-2)
- [x] Estrutura do monorepo
- [ ] VM Oracle A1
- [ ] K3s cluster
- [ ] Repositório GitHub
- [ ] CI/CD GitHub Actions

### Sprint 1: Backend Básico (D-3 a D-10)
- [ ] Modelagem de dados Food Service
- [ ] Configuração Spring Boot
- [ ] Multi-tenant
- [ ] Autenticação JWT
- [ ] CRUD de Produtos
- [ ] CRUD de Categorias

### Sprint 2: PDV + KDS (D-11 a D-20)
- [ ] Frontend PDV (Next.js)
- [ ] Frontend KDS (Next.js)
- [ ] Mobile PDV (React Native)
- [ ] Mobile KDS (React Native)
- [ ] Real-time com WebSocket

### Sprint 3: IA (D-21 a D-28)
- [ ] Serviço FastAPI
- [ ] Previsão de demanda
- [ ] Chatbot
- [ ] Integração WhatsApp

### Sprint 4: Delivery (D-29 a D-36)
- [ ] Módulo Delivery
- [ ] Integração com mapas
- [ ] Rastreamento em tempo real

---

## 🛠️ Desenvolvimento

### Backend (Spring Boot)

```bash
cd backend
./gradlew bootRun --args='--spring.profiles.active=dev'
```

### Frontend (Next.js)

```bash
cd frontend
npm install
npm run dev
```

### Mobile (React Native)

```bash
cd mobile
npm install
npx react-native run-android  # ou run-ios
```

### IA (FastAPI)

```bash
cd ia
pip install -r requirements.txt
uvicorn main:app --reload --port 8000
```

---

## 📋 Scripts Disponíveis

### Backend
```bash
# Build
./gradlew build

# Run
./gradlew bootRun

# Test
./gradlew test

# Generate JAR
./gradlew bootJar
```

### Frontend
```bash
# Development
npm run dev

# Build
npm run build

# Start (production)
npm run start

# Lint
npm run lint

# Format
npm run format

# Type Check
npm run type-check
```

### Mobile
```bash
# Run Android
npm run android

# Run iOS
npm run ios

# Start Metro
npm run start

# Test
npm run test

# Lint
npm run lint
```

### IA
```bash
# Run
python main.py

# Run with uvicorn
uvicorn main:app --reload

# Test
pytest tests/
```

---

## 🎯 Funcionalidades Principais

### 1. PDV (Ponto de Venda)
- **Gerenciamento de Pedidos:** Criar, editar, cancelar pedidos
- **Itens Customizados:** Personalização de produtos
- **Pagamentos:** Vários métodos de pagamento
- **Impressão:** Cupons fiscais e recibos
- **Relatórios:** Vendas por período, produtos mais vendidos

### 2. KDS (Kitchen Display System)
- **Visualização em Tempo Real:** Status dos pedidos
- **Organização por Estação:** Cozinha, fritura, montagem
- **Tempos de Preparo:** Estimativas e alertas
- **Prioridades:** Gerenciamento de prioridades
- **Comunicação com PDV:** Atualizações em tempo real

### 3. Delivery Nativo
- **Gerenciamento de Entregas:** Roteirização e acompanhamento
- **Integração com Mapas:** Google Maps / Mapbox
- **Status de Entrega:** Em preparação, a caminho, entregue
- **Notificações:** Para cliente e motoboy
- **Taxas Dinâmicas:** Cálculo automático de taxa de entrega

### 4. IA e Analytics
- **Previsão de Demanda:** Machine learning para previsão de vendas
- **Chatbot:** Atendimento automático ao cliente
- **Recomendações:** Sugestões de produtos baseadas em histórico
- **WhatsApp:** Integração nativa com WhatsApp Business
- **Analytics:** Dashboards e relatórios avançados

### 5. Multi-tenant
- **Isolamento por Tenant:** Dados separados por cliente
- **Planos de Assinatura:** Basic, Pro, Enterprise
- **Limites por Plano:** Usuários, produtos, pedidos
- **Gestão Centralizada:** Painel admin para todos os tenants

---

## 🔒 Segurança

- **Autenticação:** JWT com refresh tokens
- **Autorização:** RBAC (Role-Based Access Control)
- **Criptografia:** BCrypt para senhas
- **HTTPS:** SSL/TLS obrigatório em produção
- **CORS:** Configurado para domínios permitidos
- **Input Validation:** Validação de todas as entradas
- **Rate Limiting:** Proteção contra ataques DDoS

---

## 📊 Modelagem de Dados

### Entidades Principais

#### Tenant
```typescript
{
  id: UUID
  name: string (unique)
  schemaName: string (unique)
  displayName: string
  subscriptionPlan: 'BASIC' | 'PRO' | 'ENTERPRISE'
  maxUsers: number
  maxProducts: number
  isActive: boolean
  createdAt: DateTime
  updatedAt: DateTime
  expiresAt?: DateTime
}
```

#### User
```typescript
{
  id: UUID
  tenantId: UUID
  email: string (unique)
  passwordHash: string
  firstName: string
  lastName: string
  role: 'ADMIN' | 'MANAGER' | 'STAFF' | 'CASHIER' | 'KITCHEN' | 'DELIVERY'
  isActive: boolean
  isEmailVerified: boolean
  phoneNumber?: string
  profileImageUrl?: string
  createdAt: DateTime
  updatedAt: DateTime
  lastLoginAt?: DateTime
}
```

#### Product
```typescript
{
  id: UUID
  tenantId: UUID
  categoryId: UUID
  sku: string (unique per tenant)
  name: string
  description: string
  price: Decimal
  costPrice?: Decimal
  imageUrl?: string
  isActive: boolean
  isAvailable: boolean
  stockQuantity: number
  minStockLevel: number
  displayOrder: number
  preparationTimeMinutes: number
  isFeatured: boolean
  isCombo: boolean
  createdAt: DateTime
  updatedAt: DateTime
}
```

#### Order
```typescript
{
  id: UUID
  orderNumber: string (unique)
  tenantId: UUID
  customerId?: UUID
  userId?: UUID
  orderType: 'DINE_IN' | 'TAKEAWAY' | 'DELIVERY'
  status: 'PENDING' | 'IN_PREPARATION' | 'READY_FOR_DELIVERY' | 'IN_DELIVERY' | 'COMPLETED' | 'CANCELLED'
  tableNumber?: string
  items: OrderItem[]
  subtotal: Decimal
  discount: Decimal
  deliveryFee: Decimal
  taxAmount: Decimal
  total: Decimal
  paymentMethod?: 'CASH' | 'CREDIT_CARD' | 'DEBIT_CARD' | 'PIX' | 'MERCADO_PAGO' | 'OTHER'
  paymentStatus: 'PENDING' | 'PAID' | 'FAILED' | 'REFUNDED' | 'PARTIALLY_REFUNDED'
  paymentReference?: string
  isTakeaway: boolean
  priority: 'LOW' | 'NORMAL' | 'HIGH' | 'URGENT'
  estimatedPrepTimeMinutes: number
  notes?: string
  idempotencyKey?: string
  createdAt: DateTime
  updatedAt: DateTime
  completedAt?: DateTime
  cancelledAt?: DateTime
  cancelledReason?: string
}
```

#### OrderItem
```typescript
{
  id: UUID
  orderId: UUID
  productId: UUID
  productSku: string
  productName: string
  quantity: number
  unitPrice: Decimal
  totalPrice: Decimal
  notes?: string
  status: 'PENDING' | 'IN_PREPARATION' | 'READY' | 'COMPLETED' | 'CANCELLED'
  preparationStartedAt?: DateTime
  preparationCompletedAt?: DateTime
  displayOrder: number
  isCombo: boolean
  parentItemId?: UUID
  customizations?: ProductCustomization[]
}
```

---

## 🤖 Agentes do Laboratório

### Construtor
- **Responsabilidade:** Arquitetura, planejamento, orquestração
- **Funções:** Decomposição de tarefas, coordenação entre agentes
- **Arquivos:** `conhecimento.md`, `aprendizado.md`

### Craudio
- **Responsabilidade:** Backend, performance, código
- **Funções:** Spring Boot, Kotlin, modelagem de dados
- **Arquivos:** `conhecimento.md`, `aprendizado.md`

### Gepeto
- **Responsabilidade:** IA, integrações, WhatsApp
- **Funções:** FastAPI, previsão de demanda, integração WhatsApp
- **Arquivos:** `conhecimento.md`, `aprendizado.md`

### Curador
- **Responsabilidade:** Banco de dados, multi-tenant, performance
- **Funções:** PostgreSQL, otimização de queries
- **Arquivos:** `conhecimento.md`, `aprendizado.md`

### Nick
- **Responsabilidade:** UI/UX, frontend, fluxos de usuário
- **Funções:** Next.js, React Native, UI/UX
- **Arquivos:** `conhecimento.md`, `heuristicas.md`

### Centurião
- **Responsabilidade:** Segurança, OWASP, Threat Model
- **Funções:** RBAC, auditoria, superfície de ataque
- **Arquivos:** `conhecimento.md`, `conhecimento-intrusao.md`

### Testador
- **Responsabilidade:** Testes, QA, validação
- **Funções:** Testes unitários, integração, E2E, CI/CD
- **Arquivos:** `conhecimento.md`, `aprendizado.md`

---

## 📝 Decisões de Arquitetura

### 1. Stack Tecnológica
- **Backend:** Spring Boot (Kotlin) - Robustez, tipagem estática, ecossistema maduro
- **Frontend Web:** Next.js 16 - SSR, performance, App Router
- **Frontend Mobile:** React Native - Cross-platform, compartilhamento de código
- **IA:** FastAPI (Python) - Alta performance, fácil integração com ML
- **Database:** PostgreSQL - ACID, JSON support, extensível
- **Cache:** Redis - Alta performance, sessions, rate limiting
- **Messaging:** Outbox transacional (planejado); Kafka adiado (roadmap, fora do `compose.prod`)

### 2. Arquitetura Multi-tenant
- **Estratégia:** Schema-based multi-tenancy
- **Vantagens:** Isolamento completo, fácil backup/restore, segurança
- **Implementação:** Hibernate MultiTenancyStrategy.SCHEMA

### 3. Comunicação Real-time
- **Tecnologia:** WebSocket (Spring Boot + Socket.IO)
- **Uso:** KDS updates, delivery tracking, notifications
- **Alternativa considerada:** Server-Sent Events (SSE)

### 4. Deployment
- **Estratégia:** Monorepo com Docker multi-stage builds
- **Orquestração:** Docker Compose (MVP) em Oracle Cloud A1; K3s adiado (roadmap)
- **CI/CD:** GitHub Actions com workflows separados

### 5. Segurança
- **Autenticação:** JWT com refresh tokens
- **Autorização:** RBAC com Spring Security
- **Validação:** Bean Validation (JSR 380)
- **Sanitização:** Input validation em todas as camadas

---

## 📞 Suporte e Contribuição

### Reportando Bugs
1. Verifique a documentação
2. Verifique issues abertos
3. Crie um novo issue com:
   - Descrição clara do problema
   - Passos para reproduzir
   - Screenshots (se aplicável)
   - Versão da aplicação e ambiente

### Contribuindo
1. Fork o repositório
2. Crie uma branch para sua feature (`git checkout -b feature/nova-feature`)
3. Faça commit das suas mudanças (`git commit -m 'Adiciona nova feature'`)
4. Push para a branch (`git push origin feature/nova-feature`)
5. Abra um Pull Request

### Convenções de Commit
Usamos [Conventional Commits](https://www.conventionalcommits.org/):
- `feat:` - Nova feature
- `fix:` - Correção de bug
- `docs:` - Documentação
- `style:` - Formatação e estilo
- `refactor:` - Refatoração
- `perf:` - Melhoria de performance
- `test:` - Testes
- `build:` - Mudanças em build system
- `ci:` - Mudanças em CI/CD
- `chore:` - Outras mudanças

---

## 📄 Licença

MIT License - Veja o arquivo [LICENSE](LICENSE) para mais detalhes.

---

## 🏢 Sobre o Projeto

**MenuFlow** é um projeto desenvolvido pelo **Mistral Vibe** (Agente Construtor) em colaboração com todos os agentes do laboratório (Craudio, Gepeto, Curador, Nick, Centurião, Testador).

O projeto nasceu da necessidade de preencher o gap de mercado identificado na pesquisa de sistemas para hamburguerias, oferecendo uma solução completa, integrada e acessível para pequenas e médias empresas do setor de Food Service.

---

**© 2026 MenuFlow - Todos os direitos reservados**

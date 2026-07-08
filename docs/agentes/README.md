# Agentes do MenuFlow — ecossistema de conhecimento integrado

Memória viva dos agentes, versionada junto do código. **Não são silos**: é um
ecossistema integrado. O **Construtor** é o ponto de entrada e orquestrador —
decompõe tarefas e roteia para o agente de domínio; cada agente devolve
conhecimento que realimenta os demais.

```
                          ┌───────────────┐
                          │   CONSTRUTOR  │  arquitetura • orquestração
                          └──────┬────────┘
        ┌──────────┬────────┬────┴─────┬─────────┬───────────┐
     Craudio    Curador   Gepeto      Nick    Centurião   Testador
     backend      dados     IA      frontend  segurança     QA
```

Ordem de leitura sugerida: **Construtor primeiro** (dá o mapa), depois o agente
do domínio da tarefa.

| # | Agente | Domínio | Arquivo |
|---|---|---|---|
| 0 | **Construtor** | Arquitetura, planejamento, orquestração (hub) | [construtor.md](construtor.md) |
| 1 | Craudio | Backend Spring Boot/Kotlin, modelagem, performance | [craudio.md](craudio.md) |
| 2 | Curador | Banco de dados, multi-tenant, otimização | [curador.md](curador.md) |
| 3 | Gepeto | IA/FastAPI, integrações, WhatsApp | [gepeto.md](gepeto.md) |
| 4 | Nick | UI/UX, frontend Next.js / React Native | [nick.md](nick.md) |
| 5 | Centurião | Segurança, OWASP, threat model, RBAC | [centuriao.md](centuriao.md) |
| 6 | Testador | Testes, QA, CI/CD | [testador.md](testador.md) |

> **Status:** moldes aguardando o conteúdo real (colar de
> `conhecimento.md`/`aprendizado.md`/`heuristicas.md` de cada agente).

# PreSaleAgent

PreSaleAgent 是独立的售前导购 Agent 项目，包含 Python/FastAPI 后端、Java/Spring 后端和 Vue 前端。

## 目录

```text
PreSaleAgent/
├── backend-python/   # FastAPI、多 Agent 编排、RAG、Memory、Skills、Monitor、LLM-as-Judge
├── backend-java/     # Spring Boot 对等后端实现
└── frontend/         # Vue + Vite 调试工作台
```

## 启动

```bash
cd frontend
docker compose up -d --build
```

服务入口：

- 前端网关：`http://localhost`
- Python API：`http://localhost:8000`
- Java API：`http://localhost:8080`

商品目录 API 使用以下环境变量配置：

```text
PRESALEAGENT_CATALOG_API_URL
PRESALEAGENT_CATALOG_API_TOKEN
PRESALEAGENT_CATALOG_TIMEOUT_SECONDS=3
```

旧版 `ECHOMIND_*` 变量仍可作为兼容回退，但新部署统一使用 `PRESALEAGENT_*`。

未配置商品目录时，系统会返回明确的不可用状态，不会猜测价格、库存、规格或配送信息。

-- documents 스키마 (데이터/색인 계약 — 임의 변경 금지)
-- 이 앱은 documents 테이블에 읽기만 한다. 색인/임베딩 적재는 별도(색인 레포)가 담당한다.

CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS documents (
    id        uuid DEFAULT gen_random_uuid() PRIMARY KEY,
    content   text,
    metadata  json,
    embedding vector(1536)
);

CREATE INDEX IF NOT EXISTS documents_embedding_hnsw
    ON documents USING hnsw (embedding vector_cosine_ops);

CREATE INDEX IF NOT EXISTS documents_doc_id_idx
    ON documents ((metadata->>'doc_id'));

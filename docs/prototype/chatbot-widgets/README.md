# 챗봇 집계 위젯 · 정적 프로토타입

[설계 문서](../../superpowers/specs/2026-07-09-chatbot-aggregation-widgets-design.md)의 시각 참조용 프로토타입.

- `index.html` — 대화형 데모. 실제 `chat-widget` 브랜드 위에 RESOURCE 응답(Prometheus TopN·인벤토리)을
  시각 위젯으로 렌더링. 샘플 질문 클릭 또는 위젯 입력으로 확인.
- `gallery.html` — 위젯 후보 갤러리(포함 예정 3종 + 추가 후보 6종).

## 보는 법

브라우저로 `index.html`을 직접 열거나:

```bash
cd docs/prototype/chatbot-widgets && python3 -m http.server 8790
# http://localhost:8790/  (갤러리는 /gallery.html)
```

## 주의 — 목업 데이터

백엔드/Prometheus/cb_common **연동 없는 정적 HTML**이다. 인스턴스명·수치·개수는 실제
도메인 모델(`MetricSample`, `InventoryResult`) **형태에만** 맞춘 하드코딩 값이며, 라우팅도
정규식 흉내다. "실 연동되면 이렇게 보인다"를 실제 디자인·데이터 형태로 보여주는 비주얼 프로토타입.

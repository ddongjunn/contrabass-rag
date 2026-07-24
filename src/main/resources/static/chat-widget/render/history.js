/**
 * 화면 히스토리(state.messages) → /api/chat history 페이로드.
 * loading·error 메시지는 대화가 아니므로 제외하고 role/content만 남긴다.
 * 상한의 최종 권위는 서버(historyTurns)다 — 여기 limit은 전송량만 줄인다.
 */
export function buildHistory(messages, limit = 6) {
  return messages
    .filter((m) => !m.loading && !m.error && (m.role === "user" || m.role === "assistant"))
    .map((m) => ({ role: m.role, content: m.content }))
    .filter((m) => typeof m.content === "string" && m.content.trim() !== "")
    .slice(-limit);
}

// 호스트 페이지(포털)가 세팅하는 전역 컨텍스트를 읽는다. 매 질문 전송 시점마다 다시 불려야
// 하므로(사용자가 프로젝트를 전환해도 다음 질문부터 반영), 값을 캐싱하지 않고 그때그때 읽는다.

export function resolveUserId(win, fallback) {
  const value = win.CONTRABASS_CHAT_USER_ID;
  return typeof value === "string" && value.trim() ? value : fallback;
}

export function resolveProject(win) {
  const value = win.CONTRABASS_CHAT_PROJECT;
  return typeof value === "string" && value.trim() ? value : undefined;
}

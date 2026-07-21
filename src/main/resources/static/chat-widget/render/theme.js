// 다크/라이트 테마 + 패널 크기를 localStorage에 저장/복원한다. userId 등 기존 저장 패턴과 동일하게
// 순수 함수로 둬서 real DOM/localStorage 없이도 테스트 가능하게 한다(스펙 §7).

const THEME_KEY = "contrabass.chat.theme";
const PANEL_SIZE_KEY = "contrabass.chat.panelSize";

// 패널 최소 크기(스펙 §5). 최대는 뷰포트의 90% — clampPanelSize가 그때그때 계산한다.
const PANEL_MIN = { width: 340, height: 440 };

export function getTheme(storage) {
  const raw = storage.getItem(THEME_KEY);
  return raw === "light" ? "light" : "dark"; // 다크 기본, 손상되거나 알 수 없는 값도 다크로 폴백
}

export function setTheme(storage, theme) {
  storage.setItem(THEME_KEY, theme === "light" ? "light" : "dark");
}

/** host: 테마 속성을 받을 엘리먼트(운영 경로에서는 Shadow host). 다크가 기본값이라 속성 자체를 뺀다. */
export function applyTheme(host, theme) {
  if (theme === "light") host.setAttribute("data-theme", "light");
  else host.removeAttribute("data-theme");
}

export function loadPanelSize(storage) {
  try {
    const parsed = JSON.parse(storage.getItem(PANEL_SIZE_KEY) || "null");
    if (!parsed || typeof parsed.width !== "number" || typeof parsed.height !== "number") return null;
    return parsed;
  } catch {
    return null;
  }
}

export function savePanelSize(storage, size) {
  storage.setItem(PANEL_SIZE_KEY, JSON.stringify(size));
}

/** 최소 340×440, 최대는 뷰포트의 90%(스펙 §5). */
export function clampPanelSize(width, height, viewport) {
  const maxWidth = viewport.width * 0.9;
  const maxHeight = viewport.height * 0.9;
  return {
    width: Math.min(Math.max(width, PANEL_MIN.width), maxWidth),
    height: Math.min(Math.max(height, PANEL_MIN.height), maxHeight),
  };
}

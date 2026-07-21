import { h } from "./dom.js";
import { icon, resizeGripIcon } from "./icons.js";

// index.html의 <template id="cc-chrome"> 마크업을 그대로 코드로 옮긴 것 — 클래스명/데이터
// 속성은 chat-widget.css 및 chat-widget.js의 querySelector와 1:1로 맞아야 한다.
export function buildChrome() {
  return h("section", { className: "chat-widget", attrs: { "data-open": "false", "aria-label": "CONTRABASS assistant" } }, [
    h("button", { className: "chat-launcher", attrs: { type: "button", "aria-label": "채팅 열기", "aria-expanded": "false" } }, [
      h("span", { className: "launcher-icon launcher-icon-open" }, [icon("chat", 24)]),
      h("span", { className: "launcher-icon launcher-icon-close" }, [icon("close", 24)]),
      h("span", { className: "launcher-badge", attrs: { "data-chat-badge": "", hidden: "" } }),
    ]),
    h("div", { className: "chat-panel", attrs: { role: "dialog", "aria-label": "CONTRABASS assistant" } }, [
      h("button", { className: "resize-grip", attrs: { type: "button", "data-chat-resize": "", "aria-label": "패널 크기 조절" } }, [
        resizeGripIcon(16),
      ]),
      h("header", { className: "chat-header" }, [
        h("div", {}, [
          h("p", { className: "chat-kicker", text: "CONTRABASS" }),
          h("h1", { text: "Assistant" }),
        ]),
        h("div", { className: "header-actions" }, [
          h("button", { className: "icon-button", attrs: { type: "button", "data-chat-theme-toggle": "", "aria-label": "테마 전환" } }, [
            h("span", { className: "theme-icon theme-icon-light" }, [icon("light_mode", 18)]),
            h("span", { className: "theme-icon theme-icon-dark" }, [icon("dark_mode", 18)]),
          ]),
        ]),
      ]),
      h("div", { className: "chat-messages", attrs: { "data-chat-messages": "", "aria-live": "polite" } }),
      h("form", { className: "chat-form", attrs: { "data-chat-form": "" } }, [
        h("label", { className: "sr-only", attrs: { for: "chat-input" }, text: "질문" }),
        h("textarea", { attrs: { id: "chat-input", "data-chat-input": "", rows: "1", maxlength: "1000", placeholder: "질문을 입력하세요" } }),
        h("button", { className: "send-button", attrs: { type: "submit", "aria-label": "전송" } }, [icon("send", 18)]),
      ]),
    ]),
  ]);
}

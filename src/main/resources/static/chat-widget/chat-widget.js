import { mount } from "./render/dom.js";
import { buildWidget } from "./render/dispatch.js";
import { buildChrome } from "./render/chrome.js";
import { resolveUserId, resolveProject } from "./render/context.js";
import { icon } from "./render/icons.js";
import {
  getTheme, setTheme, applyTheme, loadPanelSize, savePanelSize, clampPanelSize,
} from "./render/theme.js";

(function () {
  const storage = {
    userId: "contrabass.chat.userId",
    conversationId: "contrabass.chat.conversationId",
    messages: "contrabass.chat.messages",
  };

  // 호스트 페이지가 #contrabass-chat/템플릿을 들고 있지 않아도 되게 스스로 컨테이너를 만든다.
  // (Module Federation 등으로 같은 컴포넌트가 여러 번 마운트될 수 있어 중복 삽입을 막는다.)
  if (document.getElementById("contrabass-chat")) return;

  const host = document.createElement("div");
  host.id = "contrabass-chat";
  document.body.append(host);

  const shadow = host.attachShadow({ mode: "open" });

  const styleLink = document.createElement("link");
  styleLink.rel = "stylesheet";
  // 상대경로가 호스트 페이지(포털) 기준으로 풀리는 것을 막는다 — 이 스크립트 자신의 URL 기준으로 고정.
  styleLink.href = new URL("./chat-widget.css", import.meta.url).href;
  shadow.append(styleLink);

  shadow.append(mount(buildChrome()));

  const widget = shadow.querySelector(".chat-widget");
  const launcher = shadow.querySelector(".chat-launcher");
  const closeButton = shadow.querySelector("[data-chat-close]");
  const form = shadow.querySelector("[data-chat-form]");
  const input = shadow.querySelector("[data-chat-input]");
  const messagesEl = shadow.querySelector("[data-chat-messages]");
  const sendButton = shadow.querySelector(".send-button");
  const themeToggle = shadow.querySelector("[data-chat-theme-toggle]");
  const resizeGrip = shadow.querySelector("[data-chat-resize]");
  const badge = shadow.querySelector("[data-chat-badge]");
  const panel = shadow.querySelector(".chat-panel");

  applyTheme(host, getTheme(localStorage));

  const savedSize = loadPanelSize(localStorage);
  if (savedSize) {
    const clamped = clampPanelSize(savedSize.width, savedSize.height, { width: window.innerWidth, height: window.innerHeight });
    panel.style.width = `${clamped.width}px`;
    panel.style.height = `${clamped.height}px`;
  }

  const state = {
    userId: getOrCreateId(storage.userId, "web-user"),
    conversationId: getOrCreateId(storage.conversationId, "web-conv"),
    messages: loadMessages(),
    pending: false,
    unread: 0,
  };

  if (state.messages.length === 0) {
    state.messages.push({
      id: createId("msg"),
      role: "assistant",
      content: "무엇을 도와드릴까요?",
      sources: [],
    });
  }

  renderMessages();
  notifyUnreadIfClosed();

  launcher.addEventListener("click", () => setOpen(true));
  closeButton.addEventListener("click", () => setOpen(false));

  themeToggle.addEventListener("click", () => {
    const next = getTheme(localStorage) === "light" ? "dark" : "light";
    setTheme(localStorage, next);
    applyTheme(host, next);
  });

  resizeGrip.addEventListener("pointerdown", (event) => {
    event.preventDefault();
    const startX = event.clientX;
    const startY = event.clientY;
    const startRect = panel.getBoundingClientRect();

    function onMove(moveEvent) {
      // 그립이 왼쪽 위 모서리라 왼쪽/위로 끌수록 커져야 한다 — X/Y 델타 부호를 뒤집는다.
      const nextWidth = startRect.width - (moveEvent.clientX - startX);
      const nextHeight = startRect.height - (moveEvent.clientY - startY);
      const clamped = clampPanelSize(nextWidth, nextHeight, { width: window.innerWidth, height: window.innerHeight });
      panel.style.width = `${clamped.width}px`;
      panel.style.height = `${clamped.height}px`;
    }

    function onUp() {
      window.removeEventListener("pointermove", onMove);
      window.removeEventListener("pointerup", onUp);
      const rect = panel.getBoundingClientRect();
      savePanelSize(localStorage, { width: rect.width, height: rect.height });
    }

    window.addEventListener("pointermove", onMove);
    window.addEventListener("pointerup", onUp);
  });

  input.addEventListener("keydown", (event) => {
    if (event.key === "Enter" && !event.shiftKey) {
      event.preventDefault();
      form.requestSubmit();
    }
  });

  input.addEventListener("input", () => {
    input.style.height = "auto";
    input.style.height = `${Math.min(input.scrollHeight, 120)}px`;
  });

  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    const question = input.value.trim();
    if (!question || state.pending) return;

    input.value = "";
    input.style.height = "auto";
    await sendQuestion(question);
  });

  async function sendQuestion(question) {
    const loadingId = createId("loading");
    state.pending = true;
    updateFormState();

    appendMessage({
      id: createId("msg"),
      role: "user",
      content: question,
      sources: [],
    });
    appendMessage({
      id: loadingId,
      role: "assistant",
      content: "",
      sources: [],
      loading: true,
    });

    try {
      const response = await fetch(`${getApiBase()}/api/chat`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          question,
          userId: resolveUserId(window, state.userId),
          project: resolveProject(window),
        }),
      });

      const payload = await response.json().catch(() => ({}));
      if (!response.ok) {
        throw new Error(payload.answer || payload.error || `HTTP ${response.status}`);
      }

      replaceMessage(loadingId, {
        id: loadingId,
        role: "assistant",
        content: payload.answer || "응답이 비어 있습니다.",
        sources: Array.isArray(payload.sources) ? payload.sources : [],
        widgets: Array.isArray(payload.widgets) ? payload.widgets : [],
        followups: Array.isArray(payload.followups) ? payload.followups : [],
      });
      notifyUnreadIfClosed();
    } catch (error) {
      replaceMessage(loadingId, {
        id: loadingId,
        role: "assistant",
        content: "일시적으로 응답할 수 없습니다. 잠시 후 다시 시도해 주세요.",
        sources: [],
        error: true,
      });
    } finally {
      state.pending = false;
      updateFormState();
      input.focus();
    }
  }

  function appendMessage(message) {
    state.messages.push(message);
    trimMessages();
    persistMessages();
    renderMessages();
  }

  function replaceMessage(id, nextMessage) {
    state.messages = state.messages.map((message) =>
      message.id === id ? nextMessage : message,
    );
    trimMessages();
    persistMessages();
    renderMessages();
  }

  function renderMessages() {
    messagesEl.replaceChildren();

    state.messages.forEach((message) => {
      const row = document.createElement("div");
      row.className = "message";
      row.dataset.role = message.role;
      if (message.error) row.dataset.error = "true";

      if (message.role === "assistant") {
        const avatar = document.createElement("div");
        avatar.className = "avatar";
        avatar.setAttribute("aria-hidden", "true");
        avatar.append(mount(icon("support_agent", 18)));
        row.append(avatar);

        const body = document.createElement("div");
        body.className = "msg-body";

        const widgets = Array.isArray(message.widgets) ? message.widgets : [];

        if (message.loading) {
          body.append(renderTyping());
        } else if (widgets.length > 0) {
          const cap = document.createElement("div");
          cap.className = "cap";
          cap.textContent = message.content;
          body.append(cap);
          widgets.forEach((w) => {
            const node = buildWidget(w);
            if (node) body.append(mount(node));
          });
        } else {
          // 위젯 없음 → 기존 텍스트 버블 폴백
          const bubble = document.createElement("div");
          bubble.className = "bubble";
          bubble.textContent = message.content;
          if (message.sources && message.sources.length > 0) {
            bubble.append(renderSources(message.sources));
          }
          body.append(bubble);
        }

        if (!message.loading && Array.isArray(message.followups) && message.followups.length > 0) {
          body.append(renderFollowups(message.followups));
        }
        row.append(body);
      } else {
        const bubble = document.createElement("div");
        bubble.className = "bubble";
        bubble.textContent = message.content;
        row.append(bubble);
      }

      messagesEl.append(row);
    });

    messagesEl.scrollTop = messagesEl.scrollHeight;
  }

  function renderTyping() {
    const typing = document.createElement("div");
    typing.className = "typing";
    typing.setAttribute("aria-label", "응답 생성 중");
    typing.append(document.createElement("span"));
    typing.append(document.createElement("span"));
    typing.append(document.createElement("span"));
    return typing;
  }

  function renderFollowups(followups) {
    const wrap = document.createElement("div");
    wrap.className = "followups";
    followups.forEach((text) => {
      const chip = document.createElement("button");
      chip.type = "button";
      chip.className = "fu";
      chip.textContent = text;
      chip.addEventListener("click", () => {
        if (!state.pending) sendQuestion(text);
      });
      wrap.append(chip);
    });
    return wrap;
  }

  function renderSources(sources) {
    const wrap = document.createElement("div");
    wrap.className = "sources";
    sources.forEach((source) => {
      const chip = document.createElement("span");
      chip.className = "source-chip";
      chip.textContent = source;
      wrap.append(chip);
    });
    return wrap;
  }

  function setOpen(open) {
    widget.dataset.open = String(open);
    launcher.setAttribute("aria-expanded", String(open));
    if (open) {
      state.unread = 0;
      updateBadge();
      window.setTimeout(() => input.focus(), 0);
    }
  }

  function updateBadge() {
    if (state.unread <= 0) {
      badge.setAttribute("hidden", "");
      return;
    }
    badge.removeAttribute("hidden");
    badge.textContent = state.unread > 9 ? "9+" : String(state.unread);
  }

  function notifyUnreadIfClosed() {
    if (widget.dataset.open === "true") return;
    state.unread += 1;
    updateBadge();
  }

  function updateFormState() {
    sendButton.disabled = state.pending;
    input.disabled = state.pending;
  }

  function loadMessages() {
    try {
      const parsed = JSON.parse(localStorage.getItem(storage.messages) || "[]");
      if (!Array.isArray(parsed)) return [];
      return parsed.filter((message) => message && message.role && "content" in message);
    } catch {
      return [];
    }
  }

  function persistMessages() {
    const persisted = state.messages.filter((message) => !message.loading);
    localStorage.setItem(storage.messages, JSON.stringify(persisted));
  }

  function trimMessages() {
    if (state.messages.length > 40) {
      state.messages = state.messages.slice(state.messages.length - 40);
    }
  }

  function getApiBase() {
    const params = new URLSearchParams(window.location.search);
    const fromQuery = params.get("apiBase");
    const fromGlobal = window.CONTRABASS_CHAT_API_BASE;
    const raw = fromQuery || fromGlobal || "";
    return String(raw).replace(/\/$/, "");
  }

  function getOrCreateId(key, prefix) {
    const existing = localStorage.getItem(key);
    if (existing) return existing;
    const next = createId(prefix);
    localStorage.setItem(key, next);
    return next;
  }

  function createId(prefix) {
    if (window.crypto && typeof window.crypto.randomUUID === "function") {
      return `${prefix}-${window.crypto.randomUUID()}`;
    }
    return `${prefix}-${Date.now()}-${Math.random().toString(16).slice(2)}`;
  }
})();

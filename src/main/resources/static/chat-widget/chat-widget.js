(function () {
  const storage = {
    userId: "contrabass.chat.userId",
    conversationId: "contrabass.chat.conversationId",
    messages: "contrabass.chat.messages",
  };

  const widget = document.querySelector(".chat-widget");
  const launcher = document.querySelector(".chat-launcher");
  const closeButton = document.querySelector("[data-chat-close]");
  const form = document.querySelector("[data-chat-form]");
  const input = document.querySelector("[data-chat-input]");
  const messagesEl = document.querySelector("[data-chat-messages]");
  const sendButton = document.querySelector(".send-button");

  const state = {
    userId: getOrCreateId(storage.userId, "web-user"),
    conversationId: getOrCreateId(storage.conversationId, "web-conv"),
    messages: loadMessages(),
    pending: false,
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

  launcher.addEventListener("click", () => setOpen(true));
  closeButton.addEventListener("click", () => setOpen(false));

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
          userId: state.userId,
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
      });
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

      const bubble = document.createElement("div");
      bubble.className = "bubble";

      if (message.loading) {
        const typing = document.createElement("div");
        typing.className = "typing";
        typing.setAttribute("aria-label", "응답 생성 중");
        typing.append(document.createElement("span"));
        typing.append(document.createElement("span"));
        typing.append(document.createElement("span"));
        bubble.append(typing);
      } else {
        bubble.textContent = message.content;
        if (message.sources && message.sources.length > 0) {
          bubble.append(renderSources(message.sources));
        }
      }

      row.append(bubble);
      messagesEl.append(row);
    });

    messagesEl.scrollTop = messagesEl.scrollHeight;
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
      window.setTimeout(() => input.focus(), 0);
    }
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

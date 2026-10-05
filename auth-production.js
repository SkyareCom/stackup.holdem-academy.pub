(() => {
  "use strict";

  const SESSION_KEY = "stackup.supabase.session.v1";
  const native = window.StackUpNative;
  const api = window.StackUpProductionAuth = window.StackUpProductionAuth || {};

  const q = (s) => document.querySelector(s);
  const say = (message) => {
    try {
      if (typeof toast === "function") toast(message, 4000);
      else console.warn("[StackUp Auth]", message);
    } catch (_) {
      console.warn("[StackUp Auth]", message);
    }
  };

  function config() {
    const url = native && native.getSupabaseUrl ? String(native.getSupabaseUrl() || "").replace(/\/$/, "") : "";
    const anonKey = native && native.getSupabaseAnonKey ? String(native.getSupabaseAnonKey() || "") : "";
    return { url, anonKey };
  }

  function configured() {
    const c = config();
    return /^https:\/\/.+\.supabase\.co$/i.test(c.url) && c.anonKey.length > 20;
  }

  function headers() {
    const { anonKey } = config();
    return {
      "Content-Type": "application/json",
      "apikey": anonKey,
      "Authorization": "Bearer " + anonKey
    };
  }

  async function request(path, body, options) {
    if (!configured()) throw new Error("Supabase ainda não foi configurado neste build.");
    const { url } = config();
    const response = await fetch(url + "/auth/v1" + path, {
      method: (options && options.method) || "POST",
      headers: Object.assign({}, headers(), (options && options.headers) || {}),
      body: JSON.stringify(body || {})
    });
    let data = {};
    try { data = await response.json(); } catch (_) {}
    if (!response.ok) {
      const message = data.msg || data.message || data.error_description || data.error || ("Erro de autenticação (" + response.status + ")");
      throw new Error(String(message));
    }
    return data;
  }

  function saveSession(data) {
    const now = Math.floor(Date.now() / 1000);
    const session = {
      access_token: data.access_token || "",
      refresh_token: data.refresh_token || "",
      token_type: data.token_type || "bearer",
      expires_at: data.expires_at || (now + Number(data.expires_in || 3600)),
      user: data.user || null
    };
    localStorage.setItem(SESSION_KEY, JSON.stringify(session));
    return session;
  }

  function loadSession() {
    try {
      const raw = localStorage.getItem(SESSION_KEY);
      return raw ? JSON.parse(raw) : null;
    } catch (_) {
      return null;
    }
  }

  async function activeSession() {
    let session = loadSession();
    if (!session || !session.refresh_token) return null;
    const now = Math.floor(Date.now() / 1000);
    if (Number(session.expires_at || 0) > now + 60 && session.access_token) return session;
    try {
      const refreshed = await request("/token?grant_type=refresh_token", {
        refresh_token: session.refresh_token
      });
      return saveSession(refreshed);
    } catch (_) {
      localStorage.removeItem(SESSION_KEY);
      return null;
    }
  }

  api.activeSession = activeSession;\n\n  api.saveAcademyCoachPreference = async (preference) => {
    const session = await activeSession();
    if (!session || !session.access_token || !session.user || !session.user.id) {
      return { synced: false, reason: "no_session" };
    }
    const { url, anonKey } = config();
    const now = new Date().toISOString();
    const optIn = preference && preference.optIn === true;
    const number = String((preference && preference.number) || "").trim();
    if (!/^\+[1-9][0-9]{7,14}$/.test(number)) {
      throw new Error("Número de WhatsApp inválido.");
    }
    const payload = {
      user_id: session.user.id,
      whatsapp_number: number,
      academy_coach_opt_in: optIn,
      academy_coach_opt_in_at: optIn ? ((preference && preference.optInAt) || now) : null,
      academy_coach_updated_at: now,
      academy_coach_frequency: preference && preference.frequency === "daily" ? "daily" : "included_2_week",
      academy_coach_daily_limit: 1,
      academy_coach_weekly_limit: preference && preference.frequency === "daily" ? 7 : 2,
      academy_coach_timezone: Intl.DateTimeFormat().resolvedOptions().timeZone || null,
      updated_at: now
    };
    const response = await fetch(url + "/rest/v1/profiles?on_conflict=user_id", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "apikey": anonKey,
        "Authorization": "Bearer " + session.access_token,
        "Prefer": "resolution=merge-duplicates,return=minimal"
      },
      body: JSON.stringify(payload)
    });
    if (!response.ok) {
      let data = {};
      try { data = await response.json(); } catch (_) {}
      throw new Error(data.message || data.error || "Não foi possível salvar a configuração do Academy Coach.");
    }
    return { synced: true };
  };

  function displayName(user, fallback) {
    const meta = (user && user.user_metadata) || {};
    return meta.full_name || meta.name || fallback || (user && user.email ? user.email.split("@")[0] : "") || "Jogador";
  }

  async function ensureProfile(session) {
    if (!session || !session.access_token || !session.user || !session.user.id) return;
    const { url, anonKey } = config();
    const user = session.user;
    const payload = {
      user_id: user.id,
      display_name: displayName(user, ""),
      email: user.email || null,
      phone: user.phone || null,
      preferred_language: (typeof lang === "string" && ["pt","en","es"].includes(lang)) ? lang : "pt",
      updated_at: new Date().toISOString()
    };
    try {
      await fetch(url + "/rest/v1/profiles?on_conflict=user_id", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "apikey": anonKey,
          "Authorization": "Bearer " + session.access_token,
          "Prefer": "resolution=merge-duplicates,return=minimal"
        },
        body: JSON.stringify(payload)
      });
    } catch (_) {}
  }

  async function finishLogin(session, method, fallbackName) {
    const user = session && session.user ? session.user : {};
    await ensureProfile(session);
    const name = displayName(user, fallbackName);
    const extra = {
      userId: user.id || "",
      email: user.email || "",
      phone: user.phone || "",
      authProvider: method
    };
    if (typeof login === "function") login(name, method, extra);
  }

  api.onGoogleToken = async (idToken, email, name) => {
    try {
      const data = await request("/token?grant_type=id_token", {
        provider: "google",
        id_token: idToken
      });
      const session = saveSession(data);
      await finishLogin(session, "google", name || (email ? email.split("@")[0] : ""));
    } catch (error) {
      say(error.message || "Não foi possível entrar com o Google.");
    }
  };

  api.onNativeError = (method, message) => {
    const bio = q("#bioBtn");
    if (bio) bio.classList.remove("on", "ok");
    const bioTxt = q("#bioTxt");
    if (method === "biometric" && bioTxt && typeof t === "function") bioTxt.textContent = t("bTap");
    say(message || "Não foi possível autenticar.");
  };

  api.onBiometricResult = async (success) => {
    const bio = q("#bioBtn");
    const bioTxt = q("#bioTxt");
    if (!success) {
      if (bio) bio.classList.remove("on", "ok");
      if (bioTxt && typeof t === "function") bioTxt.textContent = t("bTap");
      return;
    }
    try {
      const session = await activeSession();
      if (!session) throw new Error("Entre primeiro com Google ou StackUp ID para ativar o acesso biométrico.");
      if (bio) {
        bio.classList.remove("on");
        bio.classList.add("ok");
      }
      if (bioTxt && typeof t === "function") bioTxt.textContent = t("bOk");
      setTimeout(() => { finishLogin(session, "biometric"); }, 350);
    } catch (error) {
      if (bio) bio.classList.remove("on", "ok");
      if (bioTxt && typeof t === "function") bioTxt.textContent = t("bTap");
      say(error.message);
    }
  };


  async function stackIdSignIn(email, password) {
    const data = await request("/token?grant_type=password", { email, password });
    const session = saveSession(data);
    await finishLogin(session, "sid", email.split("@")[0]);
  }

  async function stackIdSignUp(email, password) {
    const data = await request("/signup", { email, password });
    if (data && data.access_token) {
      const session = saveSession(data);
      await finishLogin(session, "sid", email.split("@")[0]);
      return true;
    }
    say("Stack ID criado. Confirme o e-mail para concluir o acesso.");
    return false;
  }

  async function recoverStackId(email) {
    const redirectTo = "https://skyarecom.github.io/stackup.holdem-academy.pub/";
    await request("/recover?redirect_to=" + encodeURIComponent(redirectTo), { email });
    say("Enviamos as instruções de recuperação para o seu e-mail.");
  }

  function recoveryTokenFromUrl() {
    const hash = new URLSearchParams((location.hash || "").replace(/^#/, ""));
    return hash.get("type") === "recovery" ? (hash.get("access_token") || "") : "";
  }

  async function completePasswordRecovery(accessToken, password) {
    if (!accessToken) throw new Error("Link de recuperação inválido ou expirado.");
    if (String(password || "").length < 6) throw new Error("Use pelo menos 6 caracteres.");
    const { url, anonKey } = config();
    const response = await fetch(url + "/auth/v1/user", {
      method: "PUT",
      headers: {
        "Content-Type": "application/json",
        "apikey": anonKey,
        "Authorization": "Bearer " + accessToken
      },
      body: JSON.stringify({ password })
    });
    let data = {};
    try { data = await response.json(); } catch (_) {}
    if (!response.ok) throw new Error(data.msg || data.message || "Não foi possível alterar a senha.");
    history.replaceState(null, "", location.pathname + location.search);
    return data;
  }

  function offerPasswordRecovery() {
    const token = recoveryTokenFromUrl();
    if (!token) return;
    const password = window.prompt("NOVA SENHA STACKUP ID\n\nDigite uma nova senha com pelo menos 6 caracteres:");
    if (password == null) return;
    completePasswordRecovery(token, password)
      .then(() => say("Senha alterada. Entre novamente com seu StackUp ID."))
      .catch((error) => say(error.message || "Não foi possível alterar a senha."));
  }

  function enableProductionEntry() {
    document.querySelectorAll('[data-go="google"],[data-go="sid"]').forEach((button) => {
      button.disabled = false;
      button.removeAttribute("disabled");
      button.setAttribute("aria-disabled", "false");
      button.classList.remove("auth-off");
    });

    const stackButton = q("#sBtn");
    const forgot = q("#forgot");
    const emailInput = q("#email");
    const passwordInput = q("#pass");
    const errorBox = q("#sErr");

    if (stackButton) {
      stackButton.onclick = async () => {
        const email = String((emailInput && emailInput.value) || "").trim().toLowerCase();
        const password = String((passwordInput && passwordInput.value) || "");
        if (!/^\\S+@\\S+\\.\\S+$/.test(email)) {
          if (errorBox) errorBox.textContent = typeof t === "function" ? t("emailErr") : "E-mail inválido.";
          return;
        }
        if (password.length < 6) {
          if (errorBox) errorBox.textContent = typeof t === "function" ? t("passErr") : "Use pelo menos 6 caracteres.";
          return;
        }
        if (errorBox) errorBox.textContent = "";
        try {
          if (typeof busy === "function") busy(stackButton, 1);
          await stackIdSignIn(email, password);
        } catch (error) {
          if (errorBox) errorBox.textContent = error.message || "Não foi possível entrar com o Stack ID.";
        } finally {
          if (typeof busy === "function") busy(stackButton, 0);
        }
      };
    }

    if (forgot) {
      forgot.onclick = async () => {
        const email = String((emailInput && emailInput.value) || "").trim().toLowerCase();
        if (!/^\\S+@\\S+\\.\\S+$/.test(email)) {
          if (errorBox) errorBox.textContent = typeof t === "function" ? t("emailErr") : "E-mail inválido.";
          return;
        }
        try {
          if (errorBox) errorBox.textContent = "";
          await recoverStackId(email);
        } catch (error) {
          if (errorBox) errorBox.textContent = error.message || "Não foi possível recuperar o Stack ID.";
        }
      };
    }

    const sidScreen = q("#sid");
    if (sidScreen && stackButton && !q("#stackIdCreate")) {
      const create = document.createElement("button");
      create.id = "stackIdCreate";
      create.type = "button";
      create.className = "link";
      create.textContent = "Criar Stack ID";
      create.onclick = async () => {
        const email = String((emailInput && emailInput.value) || "").trim().toLowerCase();
        const password = String((passwordInput && passwordInput.value) || "");
        if (!/^\\S+@\\S+\\.\\S+$/.test(email) || password.length < 6) {
          if (errorBox) errorBox.textContent = "Informe um e-mail válido e uma senha com pelo menos 6 caracteres.";
          return;
        }
        try {
          if (errorBox) errorBox.textContent = "";
          create.disabled = true;
          await stackIdSignUp(email, password);
        } catch (error) {
          if (errorBox) errorBox.textContent = error.message || "Não foi possível criar o Stack ID.";
        } finally {
          create.disabled = false;
        }
      };
      stackButton.insertAdjacentElement("beforebegin", create);
    }
  }

  function startGoogle() {
    if (!configured()) {
      say("Configure o projeto Supabase antes de usar o login.");
      return;
    }
    if (!native || !native.requestGoogleSignIn) {
      say("Login Google nativo indisponível.");
      return;
    }
    native.requestGoogleSignIn();
  }

  document.addEventListener("click", (event) => {
    const google = event.target.closest('[data-go="google"]');
    if (!google) return;
    event.preventDefault();
    event.stopImmediatePropagation();
    startGoogle();
  }, true);

  enableProductionEntry();
  offerPasswordRecovery();

  const bioBtn = q("#bioBtn");
  if (bioBtn) {
    bioBtn.onclick = async () => {
      if (bioBtn.classList.contains("on")) return;
      const session = await activeSession();
      if (!session) {
        say("Entre primeiro com Google ou StackUp ID. Depois a biometria poderá desbloquear sua sessão.");
        return;
      }
      if (!native || !native.requestBiometricUnlock) {
        say("Biometria nativa indisponível.");
        return;
      }
      bioBtn.classList.add("on");
      const bioTxt = q("#bioTxt");
      if (bioTxt && typeof t === "function") bioTxt.textContent = t("bScan");
      native.requestBiometricUnlock();
    };
  }

  (async () => {
    try {
      const session = await activeSession();
      const legacy = localStorage.getItem("wraps.session");
      if (session && legacy) {
        const current = JSON.parse(legacy);
        if (current && ["google", "biometric"].includes(current.method)) {
          // Existing authenticated UI session remains valid; token refresh is handled above.
        }
      }
    } catch (_) {}
  })();
})();
